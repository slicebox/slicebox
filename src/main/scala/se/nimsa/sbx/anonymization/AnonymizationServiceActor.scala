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
import se.nimsa.sbx.anonymization.AnonymizationProtocol.{AnonymizationKeyValue, _}
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

        case InsertAnonymizationKeyValues(imageId, keyValueData) =>
          insertAnonymizationKeyValues(imageId, keyValueData)
            .pipeSequentiallyTo(sender)

        case QueryReverseAnonymizationKeyValues(anonPatientName, anonPatientID, anonStudyInstanceUID, anonSeriesInstanceUID, anonSOPInstanceUID) =>
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
                              anonSOPInstanceUID: String): Future[AnonymizationKeyOpResult] = {
    val fr = anonymizationDao.anonymizationKeyForImageForAnonInfo(anonPatientName, anonPatientID, anonStudyInstanceUID, anonSeriesInstanceUID, anonSOPInstanceUID)
      .flatMap(_
        .map(key => anonymizationDao.anonymizationKeyValuesForAnonymizationKeyId(key.id)
          .map(values => AnonymizationKeyOpResult(DicomHierarchyLevel.IMAGE, Some(key), values)))
        .getOrElse(anonymizationDao.anonymizationKeyForSeriesForAnonInfo(anonPatientName, anonPatientID, anonStudyInstanceUID, anonSeriesInstanceUID)
          .flatMap(_
            .map(key => anonymizationDao.anonymizationKeyValuesForAnonymizationKeyId(key.id)
              .map(values => AnonymizationKeyOpResult(DicomHierarchyLevel.SERIES, Some(key), values)))
            .getOrElse(anonymizationDao.anonymizationKeyForStudyForAnonInfo(anonPatientName, anonPatientID, anonStudyInstanceUID)
              .flatMap(_
                .map(key => anonymizationDao.anonymizationKeyValuesForAnonymizationKeyId(key.id)
                  .map(values => AnonymizationKeyOpResult(DicomHierarchyLevel.STUDY, Some(key), values)))
                .getOrElse(anonymizationDao.anonymizationKeyForPatientForAnonInfo(anonPatientName, anonPatientID)
                  .flatMap(_
                    .map(key => anonymizationDao.anonymizationKeyValuesForAnonymizationKeyId(key.id)
                      .map(values => AnonymizationKeyOpResult(DicomHierarchyLevel.PATIENT, Some(key), values)))
                    .getOrElse(Future.successful(AnonymizationKeyOpResult.empty)))))))))

    // add values stored in key to list of key values (makes code simpler elsewhere)
    fr.map(r => r.anonymizationKeyMaybe.map(key => r.copy(values = r.values ++ keyValuesFromKey(key))).getOrElse(r))
  }

  private def queryOnRealData(patientName: String, patientID: String,
                              studyInstanceUID: String, seriesInstanceUID: String,
                              sopInstanceUID: String): Future[AnonymizationKeyOpResult] = {
    val fr = anonymizationDao.anonymizationKeyForImage(patientName, patientID, studyInstanceUID, seriesInstanceUID, sopInstanceUID)
      .flatMap(_
        .map(key => anonymizationDao.anonymizationKeyValuesForAnonymizationKeyId(key.id)
          .map(values => AnonymizationKeyOpResult(DicomHierarchyLevel.IMAGE, Some(key), values)))
        .getOrElse(anonymizationDao.anonymizationKeyForSeries(patientName, patientID, studyInstanceUID, seriesInstanceUID)
          .flatMap(_
            .map(key => anonymizationDao.anonymizationKeyValuesForAnonymizationKeyId(key.id)
              .map(values => AnonymizationKeyOpResult(DicomHierarchyLevel.SERIES, Some(key), values)))
            .getOrElse(anonymizationDao.anonymizationKeyForStudy(patientName, patientID, studyInstanceUID)
              .flatMap(_
                .map(key => anonymizationDao.anonymizationKeyValuesForAnonymizationKeyId(key.id)
                  .map(values => AnonymizationKeyOpResult(DicomHierarchyLevel.STUDY, Some(key), values)))
                .getOrElse(anonymizationDao.anonymizationKeyForPatient(patientName, patientID)
                  .flatMap(_
                    .map(key => anonymizationDao.anonymizationKeyValuesForAnonymizationKeyId(key.id)
                      .map(values => AnonymizationKeyOpResult(DicomHierarchyLevel.PATIENT, Some(key), values)))
                    .getOrElse(Future.successful(AnonymizationKeyOpResult.empty)))))))))

    // add values stored in key to list of key values (makes code simpler elsewhere)
    fr.map(r => r.anonymizationKeyMaybe.map(key => r.copy(values = r.values ++ keyValuesFromKey(key))).getOrElse(r))
  }

  /**
    * Query database for matching keys on any level based on data to be inserted. Then, harmonize data to be inserted
    * and insert. Flows would be simpler if querying, harmonizing and inserting could be done separately but to support
    * concurrent inserts this must happen under one (actor-based) lock.
    */
  private def insertAnonymizationKeyValues(imageId: Long, keyValueData: Set[AnonymizationKeyValueData]): Future[AnonymizationKeyOpResult] = {

    def find(keyValueData: Iterable[AnonymizationKeyValueData], tag: Int): Option[AnonymizationKeyValueData] = keyValueData.find(_.tagPath == TagPath.fromTag(tag))

    def realValue(keyValueData: Iterable[AnonymizationKeyValueData], tag: Int): String = find(keyValueData, tag).map(_.value).getOrElse("")

    def anonValue(keyValueData: Iterable[AnonymizationKeyValueData], tag: Int): String = find(keyValueData, tag).map(_.anonymizedValue).getOrElse("")

    val patientName = realValue(keyValueData, Tag.PatientName)
    val patientID = realValue(keyValueData, Tag.PatientID)
    val studyInstanceUID = realValue(keyValueData, Tag.StudyInstanceUID)
    val seriesInstanceUID = realValue(keyValueData, Tag.SeriesInstanceUID)
    val sopInstanceUID = realValue(keyValueData, Tag.SOPInstanceUID)

    // look for matching keys on image, series, study then patient levels.
    queryOnRealData(patientName, patientID, studyInstanceUID, seriesInstanceUID, sopInstanceUID)

      // harmonize anon information to insert based on existing results
      .flatMap { existingKeyValues =>

      val harmonizedKeyValues = keyValueData
        .map { keyValueData =>
          if (keyValueData.level > existingKeyValues.matchLevel)
            keyValueData
          else
            keyValueData.copy(
              anonymizedValue = existingKeyValues.values
                .find(_.tagPath == keyValueData.tagPath)
                .map(_.anonymizedValue)
                .getOrElse(keyValueData.anonymizedValue)
            )
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
          val returnKeyValues = harmonizedKeyValues.toSeq
            .map(tv => AnonymizationKeyValue(-1, key.id, tv.tagPath, tv.value, tv.anonymizedValue))

          // filter out key values already saved as part of key to reduce size of db
          val insertKeyValues = returnKeyValues.filter {
            case kv if kv.tagPath == TagPath.fromTag(Tag.PatientName) => false
            case kv if kv.tagPath == TagPath.fromTag(Tag.PatientID) => false
            case kv if kv.tagPath == TagPath.fromTag(Tag.StudyInstanceUID) => false
            case kv if kv.tagPath == TagPath.fromTag(Tag.SeriesInstanceUID) => false
            case kv if kv.tagPath == TagPath.fromTag(Tag.SOPInstanceUID) => false
            case _ => true
          }

          anonymizationDao.insertAnonymizationKeyValues(insertKeyValues)
            .map(_ => AnonymizationKeyOpResult(existingKeyValues.matchLevel, Some(key), returnKeyValues))
        }
    }
  }

  private def keyValuesFromKey(key: AnonymizationKey): Seq[AnonymizationKeyValue] = Seq(
    AnonymizationKeyValue(-1, key.id, TagPath.fromTag(Tag.PatientName), key.patientName, key.anonPatientName),
    AnonymizationKeyValue(-1, key.id, TagPath.fromTag(Tag.PatientID), key.patientID, key.anonPatientID),
    AnonymizationKeyValue(-1, key.id, TagPath.fromTag(Tag.StudyInstanceUID), key.studyInstanceUID, key.anonStudyInstanceUID),
    AnonymizationKeyValue(-1, key.id, TagPath.fromTag(Tag.SeriesInstanceUID), key.seriesInstanceUID, key.anonSeriesInstanceUID),
    AnonymizationKeyValue(-1, key.id, TagPath.fromTag(Tag.SOPInstanceUID), key.sopInstanceUID, key.anonSOPInstanceUID),
  )


}

object AnonymizationServiceActor {
  def props(anonymizationDao: AnonymizationDAO, purgeEmptyAnonymizationKeys: Boolean)(implicit ec: ExecutionContext): Props = Props(new AnonymizationServiceActor(anonymizationDao, purgeEmptyAnonymizationKeys))
}
