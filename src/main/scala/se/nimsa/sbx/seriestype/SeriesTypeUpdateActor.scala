/*
 * Copyright 2014 Lars Edenbrandt
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package se.nimsa.sbx.seriestype

import akka.actor.{Actor, Props}
import akka.event.{Logging, LoggingReceive}
import akka.pattern.ask
import akka.stream.ActorMaterializer
import akka.util.Timeout
import org.dcm4che3.data.Attributes
import se.nimsa.dcm4che.streams.DicomFlows._
import se.nimsa.dcm4che.streams.{DicomAttributesSink, DicomPartFlow}
import se.nimsa.sbx.app.GeneralProtocol.ImageAdded
import se.nimsa.sbx.dicom.DicomHierarchy.{Image, Series}
import se.nimsa.sbx.dicom.DicomUtil
import se.nimsa.sbx.metadata.MetaDataProtocol._
import se.nimsa.sbx.seriestype.SeriesTypeProtocol._
import se.nimsa.sbx.storage.StorageService

import scala.concurrent.Future

class SeriesTypeUpdateActor(storage: StorageService)(implicit val materializer: ActorMaterializer, timeout: Timeout) extends Actor {

  val log = Logging(context.system, this)

  implicit val system = context.system
  implicit val ec = context.dispatcher

  val storageService = context.actorSelection("../../StorageService")
  val metaDataService = context.actorSelection("../../MetaDataService")
  val seriesTypeService = context.parent

  val seriesToUpdate = scala.collection.mutable.Set.empty[Long]
  val seriesBeingUpdated = scala.collection.mutable.Set.empty[Long]

  override def preStart {
    context.system.eventStream.subscribe(context.self, classOf[ImageAdded])
  }

  log.info("Series type update service started")

  def receive = LoggingReceive {

    case UpdateSeriesTypesForSeries(seriesId) =>
      addToUpdateQueueIfNotPresent(seriesId)
      self ! PollSeriesTypesUpdateQueue

    case UpdateSeriesTypesForAllSeries =>
      getAllSeries.map { allSeries =>
        self ! AddToUpdateQueue(allSeries.map(_.id))
      }

    case AddToUpdateQueue(seriesIds) =>
      seriesIds.map(addToUpdateQueueIfNotPresent)
      self ! PollSeriesTypesUpdateQueue

    case GetUpdateSeriesTypesRunningStatus =>
      sender ! UpdateSeriesTypesRunningStatus(seriesToUpdate.nonEmpty || seriesBeingUpdated.nonEmpty)

    case MarkSeriesAsProcessed(seriesId) =>
      seriesBeingUpdated -= seriesId

    case ImageAdded(image, _, _) =>
      self ! UpdateSeriesTypesForSeries(image.seriesId)

    case PollSeriesTypesUpdateQueue => pollSeriesTypesUpdateQueue()
  }

  def addToUpdateQueueIfNotPresent(seriesId: Long) =
    seriesToUpdate.add(seriesId)

  def pollSeriesTypesUpdateQueue() =
    if (seriesToUpdate.nonEmpty)
      nextSeriesNotBeingUpdated().foreach { seriesId =>
        val marked = markSeriesAsBeingUpdated(seriesId)
        if (marked)
          getSeries(seriesId).flatMap(_.map(series =>
            updateSeriesTypesForSeries(series))
            .getOrElse(Future.failed(new IllegalArgumentException(s"Series with id $seriesId not found"))))
            .onComplete {
              _ => self ! MarkSeriesAsProcessed(seriesId)
            }
      }

  def nextSeriesNotBeingUpdated() = seriesToUpdate.find(!seriesBeingUpdated.contains(_))

  def markSeriesAsBeingUpdated(seriesId: Long) =
    if (seriesToUpdate.remove(seriesId))
      seriesBeingUpdated.add(seriesId)
    else
      false

  def updateSeriesTypesForSeries(series: Series): Future[Seq[Boolean]] = {
    removeAllSeriesTypesForSeries(series).flatMap { _ =>
      getSeriesTypesInfo.flatMap { seriesTypesInfo =>

        if (seriesTypesInfo.nonEmpty) {
          val futureImageMaybe = getImageForSeries(series)

          futureImageMaybe.flatMap {
            case Some(image) =>

              val tags = getInvolvedTags(seriesTypesInfo)

              val futureAttributes = storage.fileSource(image)
                .via(new DicomPartFlow(stopTag = Some(tags.max + 1)))
                .via(whitelistFilter(tags.contains _))
                .via(attributeFlow)
                .runWith(DicomAttributesSink.attributesSink)

              val updateSeriesTypes = futureAttributes.flatMap {
                case (_, datasetMaybe) =>
                  datasetMaybe
                    .map(dataset => updateSeriesTypesForDicomData(series, seriesTypesInfo, dataset))
                    .getOrElse(Future.successful(Seq.empty))
              }

              updateSeriesTypes onComplete {
                _ => self ! PollSeriesTypesUpdateQueue
              }

              updateSeriesTypes

            case None =>
              self ! PollSeriesTypesUpdateQueue
              Future.successful(Seq.empty)
          }
        } else
          Future.successful(Seq.empty)
      }
    }
  }

  def getSeriesTypesInfo: Future[Map[SeriesType, Map[SeriesTypeRule, Seq[SeriesTypeRuleAttribute]]]] =
    getSeriesTypes
      .flatMap(seriesTypes => Future.sequence(seriesTypes.map(seriesType => getSeriesTypeRules(seriesType).flatMap(rules => Future.sequence(rules
        .map(rule => getSeriesTypeRuleAttributes(rule).map(attributes => (rule, attributes))))
        .map(_.toMap))
        .map(m => (seriesType, m))))
        .map(_.toMap))

  def getInvolvedTags(seriesTypesInfo: Map[SeriesType, Map[SeriesTypeRule, Seq[SeriesTypeRuleAttribute]]]): Set[Int] =
    seriesTypesInfo.values.flatMap(_.values.flatMap(_.map(_.tag))).toSet

  def updateSeriesTypesForDicomData(series: Series, seriesTypesInfo: Map[SeriesType, Map[SeriesTypeRule, Seq[SeriesTypeRuleAttribute]]], dataset: Attributes): Future[Seq[Boolean]] = {
    Future.sequence(
      seriesTypesInfo.map {
        case (seriesType, rulesInfo) => evaluateSeriesTypeForSeries(seriesType, rulesInfo, series, dataset)
      }
    ).map(_.toSeq)
  }

  def evaluateSeriesTypeForSeries(seriesType: SeriesType, rulesInfo: Map[SeriesTypeRule, Seq[SeriesTypeRuleAttribute]], series: Series, dataset: Attributes): Future[Boolean] = {
    val ruleEvals = rulesInfo.map {
      case (rule, ruleAttributes) => evaluateRuleForSeries(rule, ruleAttributes, series, dataset)
    }.toSeq

    if (ruleEvals.contains(true))
      addSeriesTypeForSeries(seriesType, series).map(_ => true)
    else
      Future.successful(false)
  }

  def evaluateRuleForSeries(rule: SeriesTypeRule, ruleAttributes: Seq[SeriesTypeRuleAttribute], series: Series, dataset: Attributes): Boolean =
    ruleAttributes.foldLeft(true) { (acc, attribute) =>
      if (!acc) {
        false // A previous attribute already failed matching the dicom data so no need to evaluate more attributes
      } else {
        evaluateRuleAttribute(attribute, dataset)
      }
    }

  def evaluateRuleAttribute(attribute: SeriesTypeRuleAttribute, dataset: Attributes): Boolean = {
    val attrs = attribute.tagPath.map(pathString => {
      try {
        val pathTags = pathString.split(",").map(_.toInt)
        pathTags.foldLeft(dataset)((nested, tag) => nested.getNestedDataset(tag))
      } catch {
        case _: Exception =>
          dataset
      }
    }).getOrElse(dataset)
    DicomUtil.concatenatedStringForTag(attrs, attribute.tag) == attribute.values
  }

  def getSeries(seriesId: Long): Future[Option[Series]] =
    metaDataService.ask(GetSingleSeries(seriesId)).mapTo[Option[Series]]

  def getAllSeries: Future[Seq[Series]] =
    metaDataService.ask(GetAllSeries).mapTo[SeriesCollection].map(_.series)

  def getImageForSeries(series: Series): Future[Option[Image]] =
    metaDataService.ask(GetImages(0, 1, series.id)).mapTo[Images]
      .map(_.images.headOption)

  def getSeriesTypes: Future[Seq[SeriesType]] =
    seriesTypeService.ask(GetSeriesTypes(0, 10000000)).mapTo[SeriesTypes].map(_.seriesTypes)

  def getSeriesTypeRules(seriesType: SeriesType): Future[Seq[SeriesTypeRule]] =
    seriesTypeService.ask(GetSeriesTypeRules(seriesType.id)).mapTo[SeriesTypeRules].map(_.seriesTypeRules)

  def getSeriesTypeRuleAttributes(seriesTypeRule: SeriesTypeRule): Future[Seq[SeriesTypeRuleAttribute]] =
    seriesTypeService.ask(GetSeriesTypeRuleAttributes(seriesTypeRule.id)).mapTo[SeriesTypeRuleAttributes].map(_.seriesTypeRuleAttributes)

  def addSeriesTypeForSeries(seriesType: SeriesType, series: Series): Future[SeriesTypeAddedToSeries] =
    seriesTypeService.ask(AddSeriesTypeToSeries(series, seriesType)).mapTo[SeriesTypeAddedToSeries]

  def removeAllSeriesTypesForSeries(series: Series): Future[SeriesTypesRemovedFromSeries] =
    seriesTypeService.ask(RemoveSeriesTypesFromSeries(series.id)).mapTo[SeriesTypesRemovedFromSeries]

}

object SeriesTypeUpdateActor {
  def props(storage: StorageService)(implicit materializer: ActorMaterializer, timeout: Timeout): Props = Props(new SeriesTypeUpdateActor(storage))
}
