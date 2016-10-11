/*
 * Copyright 2016 Lars Edenbrandt
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

import akka.actor.{Actor, Props}
import akka.event.{Logging, LoggingReceive}
import org.dcm4che3.data.Attributes
import se.nimsa.sbx.anonymization.AnonymizationProtocol._
import se.nimsa.sbx.anonymization.AnonymizationUtil._
import se.nimsa.sbx.app.DbProps
import se.nimsa.sbx.app.GeneralProtocol.ImageDeleted
import se.nimsa.sbx.dicom.DicomUtil._
import se.nimsa.sbx.util.ExceptionCatching

class AnonymizationServiceActor(dbProps: DbProps, purgeEmptyAnonymizationKeys: Boolean) extends Actor with ExceptionCatching {

  val log = Logging(context.system, this)

  val db = dbProps.db
  val dao = new AnonymizationDAO(dbProps.driver)

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

          case GetImageIdsForAnonymizationKey(anonymizationKeyId) =>
            val imageIds = getAnonymizationKeyImagesByAnonymizationKeyId(anonymizationKeyId).map(_.imageId)
            sender ! imageIds


          case ReverseAnonymization(attributes) =>
            if (isAnonymous(attributes)) {
              val clonedAttributes = cloneAttributes(attributes)
              reverseAnonymization(anonymizationKeysForAnonPatient(clonedAttributes), clonedAttributes)
              sender ! clonedAttributes
            } else
              sender ! attributes

          case Anonymize(imageId, attributes, tagValues) =>
            if (isAnonymous(attributes) && tagValues.isEmpty)
              sender ! attributes
            else {
              val anonymizationKeys = anonymizationKeysForPatient(attributes)
              val anonAttributes = anonymizeAttributes(attributes)
              val harmonizedAttributes = harmonizeAnonymization(anonymizationKeys, attributes, anonAttributes)
              applyTagValues(harmonizedAttributes, tagValues)

              val anonymizationKey = createAnonymizationKey(attributes, harmonizedAttributes)
              val dbAnonymizationKey = anonymizationKeys.find(isEqual(_, anonymizationKey))
                .getOrElse(addAnonymizationKey(anonymizationKey))

              maybeAddAnonymizationKeyImage(dbAnonymizationKey.id, imageId)

              sender ! harmonizedAttributes
            }

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

  def maybeAddAnonymizationKeyImage(anonymizationKeyId: Long, imageId: Long): AnonymizationKeyImage =
    db.withSession { implicit session =>
      dao.anonymizationKeyImageForAnonymizationKeyIdAndImageId(anonymizationKeyId, imageId)
        .getOrElse(
          dao.insertAnonymizationKeyImage(
            AnonymizationKeyImage(-1, anonymizationKeyId, imageId)))
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

  def anonymizationKeysForAnonPatient(attributes: Attributes) =
    db.withSession { implicit session =>
      val anonPatient = attributesToPatient(attributes)
      dao.anonymizationKeysForAnonPatient(anonPatient.patientName.value, anonPatient.patientID.value)
    }

  def anonymizationKeysForPatient(attributes: Attributes) =
    db.withSession { implicit session =>
      val patient = attributesToPatient(attributes)
      dao.anonymizationKeysForPatient(patient.patientName.value, patient.patientID.value)
    }

  def removeImageFromAnonymizationKeyImages(imageId: Long) =
    db.withSession { implicit session =>
      dao.removeAnonymizationKeyImagesForImageId(imageId, purgeEmptyAnonymizationKeys)
    }

  def getAnonymizationKeyImagesByAnonymizationKeyId(anonymizationKeyId: Long) =
    db.withSession { implicit session =>
      dao.anonymizationKeyImagesForAnonymizationKeyId(anonymizationKeyId)
    }

  def getAnonymizationKeyForId(id: Long): Option[AnonymizationKey] =
    db.withSession { implicit session =>
      dao.anonymizationKeyForId(id)
    }

  def queryAnonymizationKeys(query: AnonymizationKeyQuery): List[AnonymizationKey] =
    db.withSession { implicit session =>
      val order = query.order.map(_.orderBy)
      val orderAscending = query.order.forall(_.orderAscending)
      dao.queryAnonymizationKeys(query.startIndex, query.count, order, orderAscending, query.queryProperties)
    }

}

object AnonymizationServiceActor {
  def props(dbProps: DbProps, purgeEmptyAnonymizationKeys: Boolean): Props = Props(new AnonymizationServiceActor(dbProps, purgeEmptyAnonymizationKeys))
}
