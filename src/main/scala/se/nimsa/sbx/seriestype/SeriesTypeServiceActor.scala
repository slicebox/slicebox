/*
 * Copyright 2015 Lars Edenbrandt
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

import SeriesTypeProtocol._
import akka.actor.Actor
import akka.actor.Props
import akka.actor.actorRef2Scala
import akka.event.Logging
import akka.event.LoggingReceive
import akka.util.Timeout
import akka.pattern.ask
import se.nimsa.sbx.app.DbProps
import se.nimsa.sbx.util.ExceptionCatching
import se.nimsa.sbx.dicom.DicomUtil
import se.nimsa.sbx.dicom.DicomHierarchy.Series
import se.nimsa.sbx.metadata.MetaDataProtocol._
import scala.concurrent.Future

class SeriesTypeServiceActor(dbProps: DbProps)(implicit timeout: Timeout) extends Actor with ExceptionCatching {

  val log = Logging(context.system, this)

  implicit val system = context.system
  implicit val ec = context.dispatcher

  val db = dbProps.db
  val seriesTypeDao = new SeriesTypeDAO(dbProps.driver)

  val metaDataService = context.actorSelection("../MetaDataService")
  val seriesTypeUpdateService = context.actorOf(SeriesTypeUpdateActor.props(timeout), name = "SeriesTypeUpdate")

  log.info("Series type service started")

  updateSeriesTypesForAllSeries()

  def receive = LoggingReceive {

    case msg: SeriesTypeRequest =>

      catchAndReport {

        msg match {

          case GetSeriesTypes =>
            val seriesTypes = getSeriesTypesFromDb()
            sender ! SeriesTypes(seriesTypes)

          case AddSeriesType(seriesType) =>
            val dbSeriesType = addSeriesTypeToDb(seriesType)
            sender ! SeriesTypeAdded(dbSeriesType)

          case UpdateSeriesType(seriesType) =>
            val dbSeriesType = updateSeriesTypeInDb(seriesType)
            sender ! SeriesTypeUpdated

          case RemoveSeriesType(seriesTypeId) =>
            removeSeriesTypeFromDb(seriesTypeId)
            updateSeriesTypesForAllSeries()
            sender ! SeriesTypeRemoved(seriesTypeId)

          case GetSeriesTypeRules(seriesTypeId) =>
            val seriesTypeRules = getSeriesTypeRulesFromDb(seriesTypeId)
            sender ! SeriesTypeRules(seriesTypeRules)

          case AddSeriesTypeRule(seriesTypeRule) =>
            val dbSeriesTypeRule = addSeriesTypeRuleToDb(seriesTypeRule)
            sender ! SeriesTypeRuleAdded(dbSeriesTypeRule)

          case RemoveSeriesTypeRule(seriesTypeRuleId) =>
            removeSeriesTypeRuleFromDb(seriesTypeRuleId)
            updateSeriesTypesForAllSeries()
            sender ! SeriesTypeRuleRemoved(seriesTypeRuleId)

          case GetSeriesTypeRuleAttributes(seriesTypeRuleId) =>
            val seriesTypeRuleAttributes = getSeriesTypeRuleAttributesFromDb(seriesTypeRuleId)
            sender ! SeriesTypeRuleAttributes(seriesTypeRuleAttributes)

          case AddSeriesTypeRuleAttribute(seriesTypeRuleAttribute) =>
            val dbSeriesTypeRuleAttribute = addSeriesTypeRuleAttributeToDb(seriesTypeRuleAttribute)
            updateSeriesTypesForAllSeries()
            sender ! SeriesTypeRuleAttributeAdded(dbSeriesTypeRuleAttribute)

          case RemoveSeriesTypeRuleAttribute(seriesTypeRuleAttributeId) =>
            removeSeriesTypeRuleAttributeFromDb(seriesTypeRuleAttributeId)
            updateSeriesTypesForAllSeries()
            sender ! SeriesTypeRuleAttributeRemoved(seriesTypeRuleAttributeId)

          case AddSeriesTypeToSeries(seriesType, series) =>
            val seriesSeriesType = addSeriesTypeToSeries(SeriesSeriesType(series.id, seriesType.id))
            sender ! SeriesTypeAddedToSeries(seriesSeriesType)

          case RemoveSeriesTypesFromSeries(series) =>
            removeSeriesTypesFromSeries(series.id)
            sender ! SeriesTypesRemovedFromSeries(series)

          case GetSeriesTypesForSeries(seriesId) =>
            val seriesTypes = getSeriesTypesForSeries(seriesId)
            sender ! SeriesTypes(seriesTypes)

          case GetUpdateSeriesTypesRunningStatus =>
            seriesTypeUpdateService.forward(GetUpdateSeriesTypesRunningStatus)
        }
      }
  }

  def getSeriesTypesFromDb(): Seq[SeriesType] =
    db.withSession { implicit session =>
      seriesTypeDao.listSeriesTypes
    }

  def addSeriesTypeToDb(seriesType: SeriesType): SeriesType =
    db.withSession { implicit session =>
      seriesTypeDao.seriesTypeForName(seriesType.name).foreach(st =>
        throw new IllegalArgumentException(s"A series type with name ${seriesType.name} already exists"))
      seriesTypeDao.insertSeriesType(seriesType)
    }

  def updateSeriesTypeInDb(seriesType: SeriesType): Unit =
    db.withSession { implicit session =>
      seriesTypeDao.updateSeriesType(seriesType)
    }

  def removeSeriesTypeFromDb(seriesTypeId: Long): Unit =
    db.withSession { implicit session =>
      seriesTypeDao.removeSeriesType(seriesTypeId)
    }

  def getSeriesTypeRulesFromDb(seriesTypeId: Long): Seq[SeriesTypeRule] =
    db.withSession { implicit session =>
      seriesTypeDao.listSeriesTypeRulesForSeriesTypeId(seriesTypeId)
    }

  def getSeriesTypeRuleAttributesFromDb(seriesTypeRuleId: Long): Seq[SeriesTypeRuleAttribute] =
    db.withSession { implicit session =>
      seriesTypeDao.listSeriesTypeRuleAttributesForSeriesTypeRuleId(seriesTypeRuleId)
    }

  def addSeriesTypeRuleToDb(seriesTypeRule: SeriesTypeRule): SeriesTypeRule =
    db.withSession { implicit session =>
      seriesTypeDao.insertSeriesTypeRule(seriesTypeRule)
    }

  def removeSeriesTypeRuleFromDb(seriesTypeRuleId: Long): Unit =
    db.withSession { implicit session =>
      seriesTypeDao.removeSeriesTypeRule(seriesTypeRuleId)
    }

  def addSeriesTypeRuleAttributeToDb(seriesTypeRuleAttribute: SeriesTypeRuleAttribute): SeriesTypeRuleAttribute =
    db.withSession { implicit session =>
      val updatedAttribute = if (seriesTypeRuleAttribute.name == null || seriesTypeRuleAttribute.name.isEmpty)
        seriesTypeRuleAttribute.copy(name = DicomUtil.nameForTag(seriesTypeRuleAttribute.tag))
      else
        seriesTypeRuleAttribute
      seriesTypeDao.insertSeriesTypeRuleAttribute(updatedAttribute)
    }

  def removeSeriesTypeRuleAttributeFromDb(seriesTypeRuleAttributeId: Long): Unit =
    db.withSession { implicit session =>
      seriesTypeDao.removeSeriesTypeRuleAttribute(seriesTypeRuleAttributeId)
    }

  def getSeriesTypesForSeries(seriesId: Long) =
    db.withSession { implicit session =>
      seriesTypeDao.seriesTypesForSeries(seriesId)
    }

  def addSeriesTypeToSeries(seriesSeriesType: SeriesSeriesType) =
    db.withSession { implicit session =>
      seriesTypeDao.insertSeriesSeriesType(seriesSeriesType)
    }

  def removeSeriesTypesFromSeries(seriesId: Long) =
    db.withSession { implicit session =>
      seriesTypeDao.removeSeriesTypesForSeriesId(seriesId)
    }

  def updateSeriesTypesForAllSeries() =
    getAllSeries().map(allSeries =>
      seriesTypeUpdateService ! UpdateSeriesTypesForSeries(allSeries.map(_.id)))

  def getAllSeries(): Future[Seq[Series]] =
    metaDataService.ask(GetAllSeries).mapTo[SeriesCollection].map(_.series)

}

object SeriesTypeServiceActor {
  def props(dbProps: DbProps, timeout: Timeout): Props = Props(new SeriesTypeServiceActor(dbProps)(timeout))
}
