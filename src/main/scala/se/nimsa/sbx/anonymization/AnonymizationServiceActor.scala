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
          anonymizationDao.anonymizationKeys(startIndex, count, orderBy, orderAscending, filter)
            .map(AnonymizationKeys)
            .pipeTo(sender)

        case GetAnonymizationKey(anonymizationKeyId) =>
          anonymizationDao.anonymizationKeyForId(anonymizationKeyId)
            .pipeTo(sender)

        case InsertAnonymizationKeyValues(queryData, insertData) =>
          insertAnonymizationKeyValues(queryData, insertData)
            .pipeSequentiallyTo(sender)

        case GetReverseAnonymizationKeyValues(anonPatientName, anonPatientID, anonStudyInstanceUID, anonSeriesInstanceUID, anonSOPInstanceUID) =>
          queryOnAnonData(anonPatientName, anonPatientID, anonStudyInstanceUID, anonSeriesInstanceUID, anonSOPInstanceUID)
            .pipeTo(sender)

        case GetTagValuesForAnonymizationKey(anonymizationKeyId) =>
          anonymizationDao.anonymizationKeyValuesForAnonymizationKeyId(anonymizationKeyId)
            .pipeTo(sender)

        case QueryAnonymizationKeys(query) =>
          val order = query.order.map(_.orderBy)
          val orderAscending = query.order.forall(_.orderAscending)
          anonymizationDao.queryAnonymizationKeys(query.startIndex, query.count, order, orderAscending, query.queryProperties)
            .pipeTo(sender)

      }
  }

  private def queryOnAnonData(anonPatientName: String, anonPatientID: String,
                      anonStudyInstanceUID: String, anonSeriesInstanceUID: String,
                      anonSOPInstanceUID: String): Future[AnonymizationKeyValues] =
    anonymizationDao.anonymizationKeyForImageForAnonInfo(anonPatientName, anonPatientID, anonStudyInstanceUID, anonSeriesInstanceUID, anonSOPInstanceUID)
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

  private def queryOnRealData(patientName: String, patientID: String,
                      studyInstanceUID: String, seriesInstanceUID: String,
                      sopInstanceUID: String): Future[AnonymizationKeyValues] =
    anonymizationDao.anonymizationKeyForImage(patientName, patientID, studyInstanceUID, seriesInstanceUID, sopInstanceUID)
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

  /**
    * Query database for matching keys on any level based on data to be inserted. Then, harmonize data to be inserted
    * and insert. Flows would be simpler if querying, harmonizing and inserting could be done separately but to support
    * concurrent inserts this must happen under one (actor-based) lock.
    */
  private def insertAnonymizationKeyValues(imageId: Long, keyValues: Set[(AnonymizationKeyValue, DicomHierarchyLevel)]): Future[AnonymizationKeyValues] = {

    def find(keyValues: Iterable[AnonymizationKeyValue], tag: Int): Option[AnonymizationKeyValue] = keyValues.find(_.tagPath == TagPath.fromTag(tag))
    def realValue(keyValues: Iterable[AnonymizationKeyValue], tag: Int): String = find(keyValues, tag).map(_.value).getOrElse("")
    def anonValue(keyValues: Iterable[AnonymizationKeyValue], tag: Int): String = find(keyValues, tag).map(_.anonymizedValue).getOrElse("")

    val realValues = keyValues.map(_._1)

    val patientName = realValue(realValues, Tag.PatientName)
    val patientID = realValue(realValues, Tag.PatientID)
    val studyInstanceUID = realValue(realValues, Tag.StudyInstanceUID)
    val seriesInstanceUID = realValue(realValues, Tag.SeriesInstanceUID)
    val sopInstanceUID = realValue(realValues, Tag.SOPInstanceUID)

    // look for matching keys on image, series, study then patient levels.
    queryOnRealData(patientID, patientName, studyInstanceUID, seriesInstanceUID, sopInstanceUID)

      // harmonize anon information to insert based on existing results
      .flatMap { existingKeyValues =>

      val harmonizedKeyValues = keyValues
        .map {
          case (keyValue, level) =>
            if (level > existingKeyValues.matchLevel)
              keyValue
            else
              keyValue.copy(anonymizedValue = existingKeyValues.values
                .find(_.tagPath == keyValue.tagPath)
                .getOrElse(keyValue)
                .anonymizedValue)
        }

      val anonPatientName = anonValue(harmonizedKeyValues, Tag.PatientName)
      val anonPatientID = anonValue(harmonizedKeyValues, Tag.PatientID)
      val anonStudyInstanceUID = anonValue(harmonizedKeyValues, Tag.StudyInstanceUID)
      val anonSeriesInstanceUID = anonValue(harmonizedKeyValues, Tag.SeriesInstanceUID)
      val anonSOPInstanceUID = anonValue(harmonizedKeyValues, Tag.SOPInstanceUID)

      val anonKey = AnonymizationKey(-1, System.currentTimeMillis, imageId,
        patientName, anonPatientName, patientID, anonPatientID,
        studyInstanceUID, anonStudyInstanceUID,
        seriesInstanceUID, anonSeriesInstanceUID,
        sopInstanceUID, anonSOPInstanceUID)

      anonymizationDao
        .insertAnonymizationKey(anonKey)
        .flatMap { key =>
          val insertKeyValues = harmonizedKeyValues.toSeq
            .map(tv => AnonymizationKeyValue(-1, key.id, tv.tagPath, tv.value, tv.anonymizedValue))
          anonymizationDao.insertAnonymizationKeyValues(insertKeyValues)
            .map(_ => AnonymizationKeyValues(existingKeyValues.matchLevel, Some(anonKey), insertKeyValues))
        }
    }
  }

}

object AnonymizationServiceActor {
  def props(anonymizationDao: AnonymizationDAO, purgeEmptyAnonymizationKeys: Boolean)(implicit ec: ExecutionContext): Props = Props(new AnonymizationServiceActor(anonymizationDao, purgeEmptyAnonymizationKeys))
}
