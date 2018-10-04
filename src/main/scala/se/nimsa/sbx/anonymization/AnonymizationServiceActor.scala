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
import se.nimsa.dicom.data.{Tag, TagPath}
import se.nimsa.sbx.anonymization.AnonymizationProtocol._
import se.nimsa.sbx.app.GeneralProtocol.ImagesDeleted
import se.nimsa.sbx.dicom.DicomHierarchy.DicomHierarchyLevel
import se.nimsa.sbx.util.SequentialPipeToSupport

import scala.concurrent.{ExecutionContext, Future}

class AnonymizationServiceActor(anonymizationDao: AnonymizationDAO, purgeEmptyAnonymizationKeys: Boolean)
                               (implicit ec: ExecutionContext) extends Actor with Stash with SequentialPipeToSupport {

  val log = Logging(context.system, this)

  override def preStart {
    context.system.eventStream.subscribe(context.self, classOf[ImagesDeleted])
  }

  log.info("Anonymization service started")

  def receive = LoggingReceive {

    case ImagesDeleted(imageIds) =>
      if (purgeEmptyAnonymizationKeys) anonymizationDao.deleteAnonymizationKeysForImageIds(imageIds)

    case msg: AnonymizationRequest =>

      msg match {
        case AddAnonymizationKey(anonymizationKey) =>
          anonymizationDao.insertAnonymizationKey(anonymizationKey)
            .map(AnonymizationKeyAdded)
            .pipeSequentiallyTo(sender)

        case RemoveAnonymizationKey(anonymizationKeyId) =>
          anonymizationDao.deleteAnonymizationKey(anonymizationKeyId)
            .map(_ => AnonymizationKeyRemoved(anonymizationKeyId))
            .pipeSequentiallyTo(sender)

        case GetAnonymizationKeys(startIndex, count, orderBy, orderAscending, filter) =>
          pipe(anonymizationDao.anonymizationKeys(startIndex, count, orderBy, orderAscending, filter).map(AnonymizationKeys)).to(sender)

        case GetAnonymizationKey(anonymizationKeyId) =>
          pipe(anonymizationDao.anonymizationKeyForId(anonymizationKeyId)).to(sender)

        case GetAnonymizationKeyValues(patientName, patientID, studyInstanceUID, seriesInstanceUID, sopInstanceUID) =>
          // look for matching keys on image, series, study then patient levels.
          val f = anonymizationDao.anonymizationKeyForImage(patientName, patientID, studyInstanceUID, seriesInstanceUID, sopInstanceUID)
            .flatMap(_
              .map(key => anonymizationDao.anonymizationKeyValuesForAnonymizationKeyId(key.id)
                .map(values => AnonymizationKeyValues(DicomHierarchyLevel.IMAGE, Some(key), values)))
              .getOrElse(anonymizationDao.anonymizationKeyForSeries(patientName, patientID, studyInstanceUID, seriesInstanceUID)
                .flatMap(_
                  .map(key => anonymizationDao.anonymizationKeyValuesForAnonymizationKeyId(key.id)
                    .map(values => AnonymizationKeyValues(DicomHierarchyLevel.SERIES, Some(key), values)))
                  .getOrElse(anonymizationDao.anonymizationKeyForStudy(patientName, patientID, studyInstanceUID)
                    .flatMap(_
                      .map(key => anonymizationDao.anonymizationKeyValuesForAnonymizationKeyId(key.id)
                        .map(values => AnonymizationKeyValues(DicomHierarchyLevel.STUDY, Some(key), values)))
                      .getOrElse(anonymizationDao.anonymizationKeyForPatient(patientName, patientID)
                        .flatMap(_
                          .map(key => anonymizationDao.anonymizationKeyValuesForAnonymizationKeyId(key.id)
                            .map(values => AnonymizationKeyValues(DicomHierarchyLevel.PATIENT, Some(key), values)))
                          .getOrElse(Future.successful(AnonymizationKeyValues.empty)))))))))
          pipe(f).to(sender)

        case GetReverseAnonymizationKeyValues(anonPatientName, anonPatientID, anonStudyInstanceUID, anonSeriesInstanceUID, anonSOPInstanceUID) =>
          // look for matching keys on image, series, study then patient levels.
          val f = anonymizationDao.anonymizationKeyForImageForAnonInfo(anonPatientName, anonPatientID, anonStudyInstanceUID, anonSeriesInstanceUID, anonSOPInstanceUID)
            .flatMap(_
              .map(key => anonymizationDao.anonymizationKeyValuesForAnonymizationKeyId(key.id)
                .map(values => AnonymizationKeyValues(DicomHierarchyLevel.IMAGE, Some(key), values)))
              .getOrElse(anonymizationDao.anonymizationKeyForSeriesForAnonInfo(anonPatientName, anonPatientID, anonStudyInstanceUID, anonSeriesInstanceUID)
                .flatMap(_
                  .map(key => anonymizationDao.anonymizationKeyValuesForAnonymizationKeyId(key.id)
                    .map(values => AnonymizationKeyValues(DicomHierarchyLevel.SERIES, Some(key), values)))
                  .getOrElse(anonymizationDao.anonymizationKeyForStudyForAnonInfo(anonPatientName, anonPatientID, anonStudyInstanceUID)
                    .flatMap(_
                      .map(key => anonymizationDao.anonymizationKeyValuesForAnonymizationKeyId(key.id)
                        .map(values => AnonymizationKeyValues(DicomHierarchyLevel.STUDY, Some(key), values)))
                      .getOrElse(anonymizationDao.anonymizationKeyForPatientForAnonInfo(anonPatientName, anonPatientID)
                        .flatMap(_
                          .map(key => anonymizationDao.anonymizationKeyValuesForAnonymizationKeyId(key.id)
                            .map(values => AnonymizationKeyValues(DicomHierarchyLevel.PATIENT, Some(key), values)))
                          .getOrElse(Future.successful(AnonymizationKeyValues.empty)))))))))
          pipe(f).to(sender)

        case InsertAnonymizationKey(imageId, tagValues) =>

          val patientNameValue = tagValues.find(_.tagPath == TagPath.fromTag(Tag.PatientName))
          val patientIDValue = tagValues.find(_.tagPath == TagPath.fromTag(Tag.PatientID))
          val studyInstanceUIDValue = tagValues.find(_.tagPath == TagPath.fromTag(Tag.StudyInstanceUID))
          val seriesInstanceUIDValue = tagValues.find(_.tagPath == TagPath.fromTag(Tag.SeriesInstanceUID))
          val sopInstanceUIDValue = tagValues.find(_.tagPath == TagPath.fromTag(Tag.SOPInstanceUID))

          val patientName = patientNameValue.map(_.value).getOrElse("")
          val anonPatientName = patientNameValue.map(_.anonymizedValue).getOrElse("")
          val patientID = patientIDValue.map(_.value).getOrElse("")
          val anonPatientID = patientIDValue.map(_.anonymizedValue).getOrElse("")
          val studyInstanceUID = studyInstanceUIDValue.map(_.value).getOrElse("")
          val anonStudyInstanceUID = studyInstanceUIDValue.map(_.anonymizedValue).getOrElse("")
          val seriesInstanceUID = seriesInstanceUIDValue.map(_.value).getOrElse("")
          val anonSeriesInstanceUID = seriesInstanceUIDValue.map(_.anonymizedValue).getOrElse("")
          val sopInstanceUID = sopInstanceUIDValue.map(_.value).getOrElse("")
          val anonSOPInstanceUID = sopInstanceUIDValue.map(_.anonymizedValue).getOrElse("")

          val anonKey = AnonymizationKey(-1, System.currentTimeMillis, imageId,
            patientName, anonPatientName, patientID, anonPatientID,
            studyInstanceUID, anonStudyInstanceUID,
            seriesInstanceUID, anonSeriesInstanceUID,
            sopInstanceUID, anonSOPInstanceUID)

          anonymizationDao
            .insertAnonymizationKey(anonKey)
            .flatMap { key =>
              val keyValues = tagValues.toSeq
                .map(tv => AnonymizationKeyValue(key.id, tv.tagPath.toString, tv.value, tv.anonymizedValue))
              anonymizationDao.insertAnonymizationKeyValues(keyValues)
            }
            .pipeSequentiallyTo(sender)

        case GetTagValuesForAnonymizationKey(anonymizationKeyId) =>
          val tagValues = anonymizationDao.anonymizationKeyValuesForAnonymizationKeyId(anonymizationKeyId)
          pipe(tagValues).to(sender)

        case QueryAnonymizationKeys(query) =>
          val order = query.order.map(_.orderBy)
          val orderAscending = query.order.forall(_.orderAscending)
          pipe(anonymizationDao.queryAnonymizationKeys(query.startIndex, query.count, order, orderAscending, query.queryProperties)).to(sender)

      }
  }

}

object AnonymizationServiceActor {
  def props(anonymizationDao: AnonymizationDAO, purgeEmptyAnonymizationKeys: Boolean)(implicit ec: ExecutionContext): Props = Props(new AnonymizationServiceActor(anonymizationDao, purgeEmptyAnonymizationKeys))
}
