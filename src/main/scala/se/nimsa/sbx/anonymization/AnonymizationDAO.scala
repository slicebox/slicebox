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

import akka.NotUsed
import akka.stream.scaladsl.Source
import se.nimsa.dicom.data.TagPath
import se.nimsa.dicom.data.TagPath.TagPathTag
import se.nimsa.sbx.anonymization.AnonymizationProtocol._
import se.nimsa.sbx.dicom.DicomProperty
import se.nimsa.sbx.metadata.MetaDataProtocol._
import se.nimsa.sbx.util.DbUtil.{checkColumnExists, createTables}
import slick.basic.DatabaseConfig
import slick.jdbc.{GetResult, JdbcProfile}

import scala.concurrent.{ExecutionContext, Future}

class AnonymizationDAO(val dbConf: DatabaseConfig[JdbcProfile])(implicit ec: ExecutionContext) {

  import dbConf.profile.api._

  val db = dbConf.db

  class AnonymizationKeyTable(tag: Tag) extends Table[AnonymizationKey](tag, AnonymizationKeyTable.name) {
    def id = column[Long]("id", O.PrimaryKey, O.AutoInc)
    def created = column[Long]("created")
    def imageId = column[Long]("imageid")
    def patientName = column[String](DicomProperty.PatientName.name)
    def anonPatientName = column[String]("anonPatientName")
    def patientID = column[String](DicomProperty.PatientID.name)
    def anonPatientID = column[String]("anonPatientID")
    def studyInstanceUID = column[String](DicomProperty.StudyInstanceUID.name)
    def anonStudyInstanceUID = column[String]("anonStudyInstanceUID")
    def seriesInstanceUID = column[String](DicomProperty.SeriesInstanceUID.name)
    def anonSeriesInstanceUID = column[String]("anonSeriesInstanceUID")
    def sopInstanceUID = column[String](DicomProperty.SOPInstanceUID.name)
    def anonSOPInstanceUID = column[String]("anonSOPInstanceUID")

    def idxPatientName = index("idx_patient_name", patientName)
    def idxPatientID = index("idx_patient_id", patientID)
    def idxStudyInstanceUID = index("idx_study_uid", studyInstanceUID)
    def idxSeriesInstanceUID = index("idx_series_uid", seriesInstanceUID)
    def idxSOPInstanceUID = index("idx_sop_uid", sopInstanceUID)
    def idxAnonPatientName = index("idx_anon_patient_name", anonPatientName)
    def idxAnonPatientID = index("idx_anon_patient_id", anonPatientID)
    def idxAnonStudyInstanceUID = index("idx_anon_study_uid", anonStudyInstanceUID)
    def idxAnonSeriesInstanceUID = index("idx_anon_series_uid", anonSeriesInstanceUID)
    def idxAnonSOPInstanceUID = index("idx_anon_sop_uid", anonSOPInstanceUID)

    def * = (id, created, imageId,
      patientName, anonPatientName, patientID, anonPatientID, studyInstanceUID, anonStudyInstanceUID,
      seriesInstanceUID, anonSeriesInstanceUID,
      sopInstanceUID, anonSOPInstanceUID) <> (AnonymizationKey.tupled, AnonymizationKey.unapply)
  }

  object AnonymizationKeyTable {
    val name = "AnonymizationKeys"
  }

  val anonymizationKeyQuery = TableQuery[AnonymizationKeyTable]

  val anonymizationKeysGetResult = GetResult(r => AnonymizationKey(r.nextLong, r.nextLong, r.nextLong, r.nextString, r.nextString, r.nextString, r.nextString, r.nextString, r.nextString, r.nextString, r.nextString, r.nextString, r.nextString))

  val toKeyValue: (Long, Long, String, String, String) => AnonymizationKeyValue =
    (id: Long, anonymizationKeyId: Long, tagPath: String, value: String, anonymizedValue: String) =>
      AnonymizationKeyValue(id, anonymizationKeyId, TagPath.parse(tagPath).asInstanceOf[TagPathTag], value, anonymizedValue)

  val fromKeyValue: AnonymizationKeyValue => Option[(Long, Long, String, String, String)] =
    (keyValue: AnonymizationKeyValue) =>
      Option((keyValue.id, keyValue.anonymizationKeyId, keyValue.tagPath.toString, keyValue.value, keyValue.anonymizedValue))

  class AnonymizationKeyValueTable(tag: Tag) extends Table[AnonymizationKeyValue](tag, AnonymizationKeyValueTable.name) {
    def id = column[Long]("id", O.PrimaryKey, O.AutoInc)
    def anonymizationKeyId = column[Long]("anonymizationkeyid")
    def tagPath = column[String]("tagpath")
    def value = column[String]("value")
    def anonymizedValue = column[String]("anonymizedvalue")
    def fkAnonymizationKey = foreignKey("fk_anonymization_key", anonymizationKeyId, anonymizationKeyQuery)(_.id, onDelete = ForeignKeyAction.Cascade)
    def * = (id, anonymizationKeyId, tagPath, value, anonymizedValue) <> (toKeyValue.tupled, fromKeyValue)
  }

  object AnonymizationKeyValueTable {
    val name = "AnonymizationKeyValues"
  }

  val anonymizationKeyValueQuery = TableQuery[AnonymizationKeyValueTable]

  def create(): Future[Unit] = createTables(dbConf, (AnonymizationKeyTable.name, anonymizationKeyQuery), (AnonymizationKeyValueTable.name, anonymizationKeyValueQuery))

  def drop(): Future[Unit] = db.run {
    (anonymizationKeyQuery.schema ++ anonymizationKeyValueQuery.schema).drop
  }

  def clear(): Future[Unit] = db.run {
    DBIO.seq(anonymizationKeyQuery.delete, anonymizationKeyValueQuery.delete)
  }

  def listAnonymizationKeys: Future[Seq[AnonymizationKey]] = db.run(anonymizationKeyQuery.result)

  def listAnonymizationKeyValues: Future[Seq[AnonymizationKeyValue]] = db.run(anonymizationKeyValueQuery.result)

  def anonymizationKeys(startIndex: Long, count: Long, orderBy: Option[String], orderAscending: Boolean, filter: Option[String]): Future[Seq[AnonymizationKey]] =
    checkColumnExists(dbConf, orderBy, AnonymizationKeyTable.name).flatMap { _ =>
      db.run {

        implicit val getResult: GetResult[AnonymizationKey] = anonymizationKeysGetResult

        var query = """select * from "AnonymizationKeys""""

        filter.foreach { filterValue =>
          val filterValueLike = s"'$filterValue%'".toLowerCase
          query +=
            s""" where
                  lcase("patientName") like $filterValueLike or
                    lcase("anonPatientName") like $filterValueLike or
                      lcase("patientID") like $filterValueLike or
                        lcase("anonPatientID") like $filterValueLike or
                          lcase("studyInstanceUID") like $filterValueLike or
                            lcase("anonStudyInstanceUID") like $filterValueLike or
                              lcase("seriesInstanceUID") like $filterValueLike or
                                lcase("anonSeriesInstanceUID") like $filterValueLike or
                                  lcase("sopInstanceUID") like $filterValueLike or
                                    lcase("anonSOPInstanceUID") like $filterValueLike"""
        }

        orderBy.foreach(orderByValue =>
          query += s""" order by "$orderByValue" ${if (orderAscending) "asc" else "desc"}""")

        query += s""" limit $count offset $startIndex"""

        sql"#$query".as[AnonymizationKey]
      }
    }

  def anonymizationKeyForId(id: Long): Future[Option[AnonymizationKey]] = db.run {
    anonymizationKeyQuery.filter(_.id === id).result.headOption
  }

  def insertAnonymizationKey(entry: AnonymizationKey): Future[AnonymizationKey] = db.run {
    (anonymizationKeyQuery returning anonymizationKeyQuery.map(_.id) += entry)
      .map(generatedId => entry.copy(id = generatedId))
  }

  def insertAnonymizationKeyValues(values: Seq[AnonymizationKeyValue]): Future[Unit] = db.run {
    (anonymizationKeyValueQuery ++= values).map(_ => {})
  }

  def deleteAnonymizationKey(anonymizationKeyId: Long): Future[Unit] = db.run {
    anonymizationKeyQuery.filter(_.id === anonymizationKeyId).delete.map(_ => Unit)
  }

  def deleteAnonymizationKeysForImageIds(imageIds: Seq[Long]): Future[Unit] = db.run {
    anonymizationKeyQuery.filter(_.imageId inSetBind imageIds).delete.map(_ => {})
  }

  def anonymizationKeyForImage(patientName: String, patientID: String,
                               studyInstanceUID: String, seriesInstanceUID: String,
                               sopInstanceUID: String): Future[Option[AnonymizationKey]] = db.run {
    anonymizationKeyQuery
      .filter(_.patientName === patientName)
      .filter(_.patientID === patientID)
      .filter(_.studyInstanceUID === studyInstanceUID)
      .filter(_.seriesInstanceUID === seriesInstanceUID)
      .filter(_.sopInstanceUID === sopInstanceUID)
      .sortBy(_.created.desc)
      .result
      .headOption
  }

  def anonymizationKeyForSeries(patientName: String, patientID: String,
                                studyInstanceUID: String, seriesInstanceUID: String): Future[Option[AnonymizationKey]] = db.run {
    anonymizationKeyQuery
      .filter(_.patientName === patientName)
      .filter(_.patientID === patientID)
      .filter(_.studyInstanceUID === studyInstanceUID)
      .filter(_.seriesInstanceUID === seriesInstanceUID)
      .sortBy(_.created.desc)
      .result
      .headOption
  }

  def anonymizationKeyForStudy(patientName: String, patientID: String,
                               studyInstanceUID: String): Future[Option[AnonymizationKey]] = db.run {
    anonymizationKeyQuery
      .filter(_.patientName === patientName)
      .filter(_.patientID === patientID)
      .filter(_.studyInstanceUID === studyInstanceUID)
      .sortBy(_.created.desc)
      .result
      .headOption
  }

  def anonymizationKeyForPatient(patientName: String, patientID: String): Future[Option[AnonymizationKey]] = db.run {
    anonymizationKeyQuery
      .filter(_.patientName === patientName)
      .filter(_.patientID === patientID)
      .sortBy(_.created.desc)
      .result
      .headOption
  }

  def anonymizationKeyForImageForAnonInfo(anonPatientName: String, anonPatientID: String,
                                          anonStudyInstanceUID: String, anonSeriesInstanceUID: String,
                                          anonSOPInstanceUID: String): Future[Option[AnonymizationKey]] = db.run {
    anonymizationKeyQuery
      .filter(_.anonPatientName === anonPatientName)
      .filter(_.anonPatientID === anonPatientID)
      .filter(_.anonStudyInstanceUID === anonStudyInstanceUID)
      .filter(_.anonSeriesInstanceUID === anonSeriesInstanceUID)
      .filter(_.anonSOPInstanceUID === anonSOPInstanceUID)
      .sortBy(_.created.desc)
      .result
      .headOption
  }

  def anonymizationKeyForSeriesForAnonInfo(anonPatientName: String, anonPatientID: String,
                                           anonStudyInstanceUID: String, anonSeriesInstanceUID: String): Future[Option[AnonymizationKey]] = db.run {
    anonymizationKeyQuery
      .filter(_.anonPatientName === anonPatientName)
      .filter(_.anonPatientID === anonPatientID)
      .filter(_.anonStudyInstanceUID === anonStudyInstanceUID)
      .filter(_.anonSeriesInstanceUID === anonSeriesInstanceUID)
      .sortBy(_.created.desc)
      .result
      .headOption
  }

  def anonymizationKeyForStudyForAnonInfo(anonPatientName: String, anonPatientID: String,
                                          anonStudyInstanceUID: String): Future[Option[AnonymizationKey]] = db.run {
    anonymizationKeyQuery
      .filter(_.anonPatientName === anonPatientName)
      .filter(_.anonPatientID === anonPatientID)
      .filter(_.anonStudyInstanceUID === anonStudyInstanceUID)
      .sortBy(_.created.desc)
      .result
      .headOption
  }

  def anonymizationKeyForPatientForAnonInfo(anonPatientName: String, anonPatientID: String): Future[Option[AnonymizationKey]] = db.run {
    anonymizationKeyQuery
      .filter(_.anonPatientName === anonPatientName)
      .filter(_.anonPatientID === anonPatientID)
      .sortBy(_.created.desc)
      .result
      .headOption
  }

  def anonymizationKeyValuesForAnonymizationKeyId(anonymizationKeyId: Long): Future[Seq[AnonymizationKeyValue]] = {
    val query =
      for {
        anonKey <- anonymizationKeyQuery if anonKey.id === anonymizationKeyId
        keyValue <- anonymizationKeyValueQuery if keyValue.anonymizationKeyId === anonKey.id
      } yield keyValue
    db.run(query.result)
  }

  private val queryAnonymizationKeysSelectPart = s"""select * from "${AnonymizationKeyTable.name}""""

  def queryAnonymizationKeys(startIndex: Long, count: Long, orderBy: Option[String], orderAscending: Boolean, queryProperties: Seq[QueryProperty]): Future[Seq[AnonymizationKey]] =
    checkColumnExists(dbConf, orderBy, AnonymizationKeyTable.name).flatMap { _ =>
      Future.sequence(queryProperties.map(qp => checkColumnExists(dbConf, qp.propertyName, AnonymizationKeyTable.name))).flatMap { _ =>
        db.run {
          import se.nimsa.sbx.metadata.MetaDataDAO._

          implicit val getResult: GetResult[AnonymizationKey] = anonymizationKeysGetResult

          val query = queryAnonymizationKeysSelectPart +
            wherePart(queryPart(queryProperties)) +
            orderByPart(orderBy, orderAscending) +
            pagePart(startIndex, count)

          sql"#$query".as[AnonymizationKey]
        }
      }
    }

  def anonymizationKeyValueSource: Source[(AnonymizationKey, AnonymizationKeyValue), NotUsed] =
    Source.fromPublisher(db.stream(
      anonymizationKeyQuery.joinLeft(anonymizationKeyValueQuery).on(_.id === _.anonymizationKeyId).result
    )).map {
      case (anonKey, maybeKeyValue) => (anonKey, maybeKeyValue.getOrElse(AnonymizationKeyValue(-1, anonKey.id, TagPath.fromTag(0), "", "")))
    }

}
