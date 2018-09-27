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

import akka.actor.{Actor, Props, Stash}
import akka.event.{Logging, LoggingReceive}
import akka.pattern.pipe
import se.nimsa.dicom.data.Tag
import se.nimsa.sbx.anonymization.AnonymizationProtocol._
import se.nimsa.sbx.app.GeneralProtocol.ImagesDeleted
import se.nimsa.sbx.util.SequentialPipeToSupport

import scala.concurrent.{ExecutionContext, Future}

class AnonymizationServiceActor(anonymizationDao: AnonymizationDAO, purgeEmptyAnonymizationKeys: Boolean)
                               (implicit ec: ExecutionContext) extends Actor with Stash with SequentialPipeToSupport {

  import AnonymizationUtil._

  val log = Logging(context.system, this)

  override def preStart {
    context.system.eventStream.subscribe(context.self, classOf[ImagesDeleted])
  }

  log.info("Anonymization service started")

  def receive = LoggingReceive {

    case ImagesDeleted(imageIds) =>
      anonymizationDao.removeAnonymizationKeyImagesForImageId(imageIds, purgeEmptyAnonymizationKeys)

    case msg: AnonymizationRequest =>

      msg match {
        case AddAnonymizationKey(anonymizationKey) =>
          anonymizationDao.insertAnonymizationKey(anonymizationKey)
            .map(AnonymizationKeyAdded)
            .pipeSequentiallyTo(sender)

        case RemoveAnonymizationKey(anonymizationKeyId) =>
          anonymizationDao.removeAnonymizationKey(anonymizationKeyId)
            .map(_ => AnonymizationKeyRemoved(anonymizationKeyId))
            .pipeSequentiallyTo(sender)

        case GetAnonymizationKeys(startIndex, count, orderBy, orderAscending, filter) =>
          pipe(anonymizationDao.anonymizationKeys(startIndex, count, orderBy, orderAscending, filter).map(AnonymizationKeys)).to(sender)

        case GetAnonymizationKey(anonymizationKeyId) =>
          pipe(anonymizationDao.anonymizationKeyForId(anonymizationKeyId)).to(sender)

        case GetImageIdsForAnonymizationKey(anonymizationKeyId) =>
          pipe(anonymizationDao.anonymizationKeyImagesForAnonymizationKeyId(anonymizationKeyId).map(_.map(_.imageId))).to(sender)

        case GetAnonymizationKeysForPatient(patientName, patientID) =>
          pipe(anonymizationDao.anonymizationKeysForPatient(patientName, patientID).map(AnonymizationKeys)).to(sender)

        case GetReverseAnonymizationKeysForPatient(anonPatientName, anonPatientID) =>
          pipe(anonymizationDao.anonymizationKeysForAnonPatient(anonPatientName, anonPatientID).map(AnonymizationKeys)).to(sender)

        case QueryAnonymizationKeys(query) =>
          val order = query.order.map(_.orderBy)
          val orderAscending = query.order.forall(_.orderAscending)
          pipe(anonymizationDao.queryAnonymizationKeys(query.startIndex, query.count, order, orderAscending, query.queryProperties)).to(sender)

        case GetOrCreateAnonymizationKey(patientNameMaybe, patientIDMaybe, patientSexMaybe, patientBirthDateMaybe,
        patientAgeMaybe, studyInstanceUIDMaybe, studyDescriptionMaybe, studyIDMaybe, accessionNumberMaybe,
        seriesInstanceUIDMaybe, seriesDescriptionMaybe, protocolNameMaybe, frameOfReferenceUIDMaybe, tagValues) =>
          val maybeFutureKeys = for {
            patientName <- patientNameMaybe
            patientID <- patientIDMaybe
          } yield {
            anonymizationDao.anonymizationKeysForPatient(patientName, patientID)
          }

          val futureKeys = maybeFutureKeys.getOrElse(Future.successful(Seq.empty))

          val futureAnonKey = futureKeys.flatMap { patientKeys =>
            val patientKeyMaybe = patientKeys.headOption
            val studyKeys = studyInstanceUIDMaybe.map(studyInstanceUID => patientKeys.filter(_.studyInstanceUID == studyInstanceUID)).getOrElse(Seq.empty)
            val studyKeyMaybe = studyKeys.headOption
            val seriesKeys = seriesInstanceUIDMaybe.map(seriesInstanceUID => studyKeys.filter(_.seriesInstanceUID == seriesInstanceUID)).getOrElse(Seq.empty)
            val seriesKeyMaybe = seriesKeys.headOption

            seriesKeyMaybe
              .map(key => Future.successful(applyTagValues(key, tagValues)))
              .getOrElse {

                val patientName = patientNameMaybe.getOrElse("")
                val anonPatientName = patientKeyMaybe.map(_.anonPatientName).getOrElse(createAnonymousPatientName(patientSexMaybe, patientAgeMaybe))
                val patientID = patientIDMaybe.getOrElse("")
                val anonPatientID = patientKeyMaybe.map(_.anonPatientID).getOrElse(createUid(""))
                val patientBirthDate = patientBirthDateMaybe.getOrElse("")
                val studyInstanceUID = studyInstanceUIDMaybe.getOrElse("")
                val anonStudyInstanceUID = studyKeyMaybe.map(_.anonStudyInstanceUID).getOrElse(if (studyInstanceUID.isEmpty) "" else createUid(""))
                val studyDescription = studyDescriptionMaybe.getOrElse("")
                val studyID = studyIDMaybe.getOrElse("")
                val accessionNumber = accessionNumberMaybe.getOrElse("")
                val seriesInstanceUID = seriesInstanceUIDMaybe.getOrElse("")
                val anonSeriesInstanceUID = seriesKeyMaybe.map(_.anonSeriesInstanceUID).getOrElse(if (seriesInstanceUID.isEmpty) "" else createUid(""))
                val seriesDescription = seriesDescriptionMaybe.getOrElse("")
                val protocolName = protocolNameMaybe.getOrElse("")
                val frameOfReferenceUID = frameOfReferenceUIDMaybe.getOrElse("")
                val anonFrameOfReferenceUID = seriesKeyMaybe.map(_.anonFrameOfReferenceUID).getOrElse(if (frameOfReferenceUID.isEmpty) "" else createUid(frameOfReferenceUID))

                val anonKey = AnonymizationKey(-1, System.currentTimeMillis,
                  patientName, anonPatientName, patientID, anonPatientID, patientBirthDate,
                  studyInstanceUID, anonStudyInstanceUID, studyDescription, studyID, accessionNumber,
                  seriesInstanceUID, anonSeriesInstanceUID, seriesDescription, protocolName,
                  frameOfReferenceUID, anonFrameOfReferenceUID)

                anonymizationDao.insertAnonymizationKey(applyTagValues(anonKey, tagValues))

              }
          }

          futureAnonKey.pipeSequentiallyTo(sender)
      }
  }

  private def applyTagValues(key: AnonymizationKey, tagValues: Seq[TagValue]): AnonymizationKey = {
    key.copy(anonPatientName = tagValues.filter(_.tagPath.isRoot).find(_.tagPath.tag == Tag.PatientName).map(_.value).getOrElse(key.anonPatientName),
      anonPatientID = tagValues.filter(_.tagPath.isRoot).find(_.tagPath.tag == Tag.PatientID).map(_.value).getOrElse(key.anonPatientID),
      anonStudyInstanceUID = tagValues.filter(_.tagPath.isRoot).find(_.tagPath.tag == Tag.StudyInstanceUID).map(_.value).getOrElse(key.anonStudyInstanceUID),
      anonSeriesInstanceUID = tagValues.filter(_.tagPath.isRoot).find(_.tagPath.tag == Tag.SeriesInstanceUID).map(_.value).getOrElse(key.anonSeriesInstanceUID),
      anonFrameOfReferenceUID = tagValues.filter(_.tagPath.isRoot).find(_.tagPath.tag == Tag.FrameOfReferenceUID).map(_.value).getOrElse(key.anonFrameOfReferenceUID))
  }

}

object AnonymizationServiceActor {
  def props(anonymizationDao: AnonymizationDAO, purgeEmptyAnonymizationKeys: Boolean)(implicit ec: ExecutionContext): Props = Props(new AnonymizationServiceActor(anonymizationDao, purgeEmptyAnonymizationKeys))
}
