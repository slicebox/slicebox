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

package se.nimsa.sbx.anonymization

import akka.actor.Actor
import akka.event.LoggingReceive
import akka.event.Logging
import akka.actor.Props
import akka.pattern.ask
import akka.pattern.pipe
import akka.util.Timeout
import scala.concurrent.Future
import se.nimsa.sbx.app.DbProps
import se.nimsa.sbx.util.ExceptionCatching
import se.nimsa.sbx.dicom.DicomUtil._
import se.nimsa.sbx.dicom.DicomHierarchy.Image
import se.nimsa.sbx.app.GeneralProtocol.ImageDeleted
import se.nimsa.sbx.metadata.MetaDataProtocol.Images
import se.nimsa.sbx.metadata.MetaDataProtocol.GetImage
import org.dcm4che3.data.Attributes
import AnonymizationProtocol._
import AnonymizationUtil._

class AnonymizationServiceActor(dbProps: DbProps, implicit val timeout: Timeout) extends Actor with ExceptionCatching {

  val log = Logging(context.system, this)

  val db = dbProps.db
  val dao = new AnonymizationDAO(dbProps.driver)

  implicit val system = context.system
  implicit val ec = context.dispatcher

  val metaDataService = context.actorSelection("../MetaDataService")

  setupDb()

  override def preStart {
    context.system.eventStream.subscribe(context.self, classOf[ImageDeleted])
  }

  log.info("Anonymization service started")

  def receive = LoggingReceive {

    case ImageDeleted(imageId) =>
      removeImageFromAnonymizationKeyImages(imageId)

    case msg: AnonymizationRequest =>

      catchAndReport {

        msg match {
          case RemoveAnonymizationKey(anonymizationKeyId) =>
            removeAnonymizationKey(anonymizationKeyId)
            sender ! AnonymizationKeyRemoved(anonymizationKeyId)

          case GetAnonymizationKeys(startIndex, count, orderBy, orderAscending, filter) =>
            sender ! AnonymizationKeys(listAnonymizationKeys(startIndex, count, orderBy, orderAscending, filter))

          case GetAnonymizationKey(anonymizationKeyId) =>
            sender ! getAnonymizationKeyForId(anonymizationKeyId)

          case GetImagesForAnonymizationKey(anonymizationKeyId) =>
            val imageIds = getAnonymizationKeyImagesByAnonymizationKeyId(anonymizationKeyId).map(_.imageId)
            getImagesFromStorage(imageIds).pipeTo(sender)

          case ReverseAnonymization(dataset) =>
            val clonedDataset = cloneDataset(dataset)
            reverseAnonymization(anonymizationKeysForAnonPatient(clonedDataset), clonedDataset)
            sender ! clonedDataset

          case Anonymize(imageId, dataset, tagValues) =>
            val anonymizationKeys = anonymizationKeysForPatient(dataset)
            val anonDataset = anonymizeDataset(dataset)
            val harmonizedDataset = harmonizeAnonymization(anonymizationKeys, dataset, anonDataset)
            applyTagValues(harmonizedDataset, tagValues)

            val anonymizationKey = createAnonymizationKey(dataset, harmonizedDataset)
            if (!anonymizationKeys.exists(isEqual(_, anonymizationKey))) {
              val key = addAnonymizationKey(anonymizationKey)
              addAnonymizationKeyImage(AnonymizationKeyImage(-1, key.id, imageId))
            }

            sender ! harmonizedDataset

          case QueryAnonymizationKeys(query) =>
            sender ! queryAnonymizationKeys(query)
        }

      }
  }

  def setupDb(): Unit =
    db.withSession { implicit session =>
      dao.create
    }

  def addAnonymizationKey(anonymizationKey: AnonymizationKey): AnonymizationKey =
    db.withSession { implicit session =>
      dao.insertAnonymizationKey(anonymizationKey)
    }

  def addAnonymizationKeyImage(anonymizationKeyImage: AnonymizationKeyImage): AnonymizationKeyImage =
    db.withSession { implicit session =>
      dao.insertAnonymizationKeyImage(anonymizationKeyImage)
    }

  def removeAnonymizationKey(anonymizationKeyId: Long) =
    db.withSession { implicit session =>
      dao.removeAnonymizationKey(anonymizationKeyId)
    }

  def listAnonymizationKeys(startIndex: Long, count: Long, orderBy: Option[String], orderAscending: Boolean, filter: Option[String]) =
    db.withSession { implicit session =>
      dao.anonymizationKeys(startIndex, count, orderBy, orderAscending, filter)
    }

  def getAnonymizationKeys(anonymizationKeyId: Long): Option[AnonymizationKey] =
    db.withSession { implicit session =>
      dao.anonymizationKeyForId(anonymizationKeyId)
    }

  def anonymizationKeysForAnonPatient(dataset: Attributes) =
    db.withSession { implicit session =>
      val anonPatient = datasetToPatient(dataset)
      dao.anonymizationKeysForAnonPatient(anonPatient.patientName.value, anonPatient.patientID.value)
    }

  def anonymizationKeysForPatient(dataset: Attributes) =
    db.withSession { implicit session =>
      val patient = datasetToPatient(dataset)
      dao.anonymizationKeysForPatient(patient.patientName.value, patient.patientID.value)
    }

  def removeImageFromAnonymizationKeyImages(imageId: Long) =
    db.withSession { implicit session =>
      dao.removeAnonymizationKeyImagesForImageId(imageId)
    }

  def getAnonymizationKeyImagesByAnonymizationKeyId(anonymizationKeyId: Long) =
    db.withSession { implicit session =>
      dao.anonymizationKeyImagesForAnonymizationKeyId(anonymizationKeyId)
    }

  def getImagesFromStorage(imageIds: List[Long]): Future[Images] =
    Future.sequence(
      imageIds.map(imageId =>
        metaDataService.ask(GetImage(imageId)).mapTo[Option[Image]]))
      .map(imageMaybes => Images(imageMaybes.flatten))

  def getAnonymizationKeyForId(id: Long): Option[AnonymizationKey] =
    db.withSession { implicit session =>
      dao.anonymizationKeyForId(id)
    }

  def queryAnonymizationKeys(query: AnonymizationKeyQuery): List[AnonymizationKey] =
    db.withSession { implicit session =>
      val order = query.order.map(_.orderBy)
      val orderAscending = query.order.map(_.orderAscending).getOrElse(true)
      dao.queryAnonymizationKeys(query.startIndex, query.count, order, orderAscending, query.queryProperties)
    }

}

object AnonymizationServiceActor {
  def props(dbProps: DbProps, timeout: Timeout): Props = Props(new AnonymizationServiceActor(dbProps, timeout))
}
