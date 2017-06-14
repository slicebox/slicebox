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

package se.nimsa.sbx.anonymization

import akka.actor.{Actor, Props}
import akka.event.{Logging, LoggingReceive}
import akka.util.Timeout
import org.dcm4che3.data.Attributes
import se.nimsa.sbx.anonymization.AnonymizationProtocol._
import se.nimsa.sbx.anonymization.AnonymizationUtil._
import se.nimsa.sbx.app.GeneralProtocol.ImageDeleted
import se.nimsa.sbx.dicom.DicomUtil._
import se.nimsa.sbx.util.ExceptionCatching
import se.nimsa.sbx.util.FutureUtil.await

class AnonymizationServiceActor(anonymizationDao: AnonymizationDAO, purgeEmptyAnonymizationKeys: Boolean)(implicit timeout: Timeout) extends Actor with ExceptionCatching {

  val log = Logging(context.system, this)

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

          case GetAnonymizationKeysForPatient(name, id) =>
            val keys = anonymizationKeysForPatient(name, id)
            sender ! AnonymizationKeys(keys)

          case GetReverseAnonymizationKeysForPatient(anonPatientName, anonPatientID) =>
            val keys = anonymizationKeysForAnonPatient(anonPatientName, anonPatientID)
            sender ! AnonymizationKeys(keys)

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

          case AddAnonymizationKey(anonymizationKey) =>
            sender ! AnonymizationKeyAdded(addAnonymizationKey(anonymizationKey))

          case QueryAnonymizationKeys(query) =>
            sender ! queryAnonymizationKeys(query)
        }

      }
  }

  def addAnonymizationKey(anonymizationKey: AnonymizationKey): AnonymizationKey =
    await(anonymizationDao.insertAnonymizationKey(anonymizationKey))

  def maybeAddAnonymizationKeyImage(anonymizationKeyId: Long, imageId: Long): AnonymizationKeyImage =
    await(anonymizationDao.anonymizationKeyImageForAnonymizationKeyIdAndImageId(anonymizationKeyId, imageId))
      .getOrElse(
        await(anonymizationDao.insertAnonymizationKeyImage(
          AnonymizationKeyImage(-1, anonymizationKeyId, imageId))))

  def removeAnonymizationKey(anonymizationKeyId: Long) =
    await(anonymizationDao.removeAnonymizationKey(anonymizationKeyId))

  def listAnonymizationKeys(startIndex: Long, count: Long, orderBy: Option[String], orderAscending: Boolean, filter: Option[String]) =
    await(anonymizationDao.anonymizationKeys(startIndex, count, orderBy, orderAscending, filter))

  def getAnonymizationKeys(anonymizationKeyId: Long): Option[AnonymizationKey] =
    await(anonymizationDao.anonymizationKeyForId(anonymizationKeyId))

  def anonymizationKeysForAnonPatient(attributes: Attributes) = {
    val anonPatient = attributesToPatient(attributes)
    await(anonymizationDao.anonymizationKeysForAnonPatient(anonPatient.patientName.value, anonPatient.patientID.value))
  }

  def anonymizationKeysForAnonPatient(anonPatientName: String, anonPatientID: String) = {
    await(anonymizationDao.anonymizationKeysForAnonPatient(anonPatientName, anonPatientID))
  }

  def anonymizationKeysForPatient(attributes: Attributes): Seq[AnonymizationKey] = {
    val patient = attributesToPatient(attributes)
    anonymizationKeysForPatient(patient.patientName.value, patient.patientID.value)
  }

  def anonymizationKeysForPatient(patientName: String, patientID: String): Seq[AnonymizationKey] = {
    await(anonymizationDao.anonymizationKeysForPatient(patientName, patientID))
  }

  def removeImageFromAnonymizationKeyImages(imageId: Long) =
    await(anonymizationDao.removeAnonymizationKeyImagesForImageId(imageId, purgeEmptyAnonymizationKeys))

  def getAnonymizationKeyImagesByAnonymizationKeyId(anonymizationKeyId: Long) =
    await(anonymizationDao.anonymizationKeyImagesForAnonymizationKeyId(anonymizationKeyId))

  def getAnonymizationKeyForId(id: Long): Option[AnonymizationKey] =
    await(anonymizationDao.anonymizationKeyForId(id))

  def queryAnonymizationKeys(query: AnonymizationKeyQuery): Seq[AnonymizationKey] = {
    val order = query.order.map(_.orderBy)
    val orderAscending = query.order.forall(_.orderAscending)
    await(anonymizationDao.queryAnonymizationKeys(query.startIndex, query.count, order, orderAscending, query.queryProperties))
  }

}

object AnonymizationServiceActor {
  def props(anonymizationDao: AnonymizationDAO, purgeEmptyAnonymizationKeys: Boolean)(implicit timeout: Timeout): Props = Props(new AnonymizationServiceActor(anonymizationDao, purgeEmptyAnonymizationKeys))
}
