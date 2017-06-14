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

import se.nimsa.sbx.anonymization.AnonymizationProtocol._
import se.nimsa.sbx.dicom.DicomProperty
import se.nimsa.sbx.metadata.MetaDataProtocol._
import se.nimsa.sbx.util.DbUtil.{checkColumnExists, createTables}
import slick.basic.DatabaseConfig
import slick.jdbc.JdbcProfile
import slick.jdbc.GetResult

import scala.concurrent.{ExecutionContext, Future}

class AnonymizationDAO(val dbConf: DatabaseConfig[JdbcProfile])(implicit ec: ExecutionContext) {

  import dbConf.profile.api._

  val db = dbConf.db

  class AnonymizationKeyTable(tag: Tag) extends Table[AnonymizationKey](tag, AnonymizationKeyTable.name) {
    def id = column[Long]("id", O.PrimaryKey, O.AutoInc)
    def created = column[Long]("created")
    def patientName = column[String](DicomProperty.PatientName.name)
    def anonPatientName = column[String]("anonPatientName")
    def patientID = column[String](DicomProperty.PatientID.name)
    def anonPatientID = column[String]("anonPatientID")
    def patientBirthDate = column[String](DicomProperty.PatientBirthDate.name)
    def studyInstanceUID = column[String](DicomProperty.StudyInstanceUID.name)
    def anonStudyInstanceUID = column[String]("anonStudyInstanceUID")
    def studyDescription = column[String](DicomProperty.StudyDescription.name)
    def studyID = column[String](DicomProperty.StudyID.name)
    def accessionNumber = column[String](DicomProperty.AccessionNumber.name)
    def seriesInstanceUID = column[String](DicomProperty.SeriesInstanceUID.name)
    def anonSeriesInstanceUID = column[String]("anonSeriesInstanceUID")
    def seriesDescription = column[String](DicomProperty.SeriesDescription.name)
    def protocolName = column[String](DicomProperty.ProtocolName.name)
    def frameOfReferenceUID = column[String](DicomProperty.FrameOfReferenceUID.name)
    def anonFrameOfReferenceUID = column[String]("anonFrameOfReferenceUID")
    def * = (id, created,
      patientName, anonPatientName, patientID, anonPatientID, patientBirthDate,
      studyInstanceUID, anonStudyInstanceUID, studyDescription, studyID, accessionNumber,
      seriesInstanceUID, anonSeriesInstanceUID, seriesDescription, protocolName, frameOfReferenceUID, anonFrameOfReferenceUID) <> (AnonymizationKey.tupled, AnonymizationKey.unapply)
  }

  object AnonymizationKeyTable {
    val name = "AnonymizationKeys"
  }

  val anonymizationKeyQuery = TableQuery[AnonymizationKeyTable]

  class AnonymizationKeyImageTable(tag: Tag) extends Table[AnonymizationKeyImage](tag, AnonymizationKeyImageTable.name) {
    def id = column[Long]("id", O.PrimaryKey, O.AutoInc)
    def anonymizationKeyId = column[Long]("anonymizationkeyid")
    def imageId = column[Long]("imageid")
    def fkAnonymizationKey = foreignKey("fk_anonymization_key", anonymizationKeyId, anonymizationKeyQuery)(_.id, onDelete = ForeignKeyAction.Cascade)
    def * = (id, anonymizationKeyId, imageId) <> (AnonymizationKeyImage.tupled, AnonymizationKeyImage.unapply)
  }

  object AnonymizationKeyImageTable {
    val name = "AnonymizationKeyImages"
  }

  val anonymizationKeyImageQuery = TableQuery[AnonymizationKeyImageTable]

  def create() = createTables(dbConf, (AnonymizationKeyTable.name, anonymizationKeyQuery), (AnonymizationKeyImageTable.name, anonymizationKeyImageQuery))

  def drop() = db.run {
    (anonymizationKeyQuery.schema ++ anonymizationKeyImageQuery.schema).drop
  }

  def clear() = db.run {
    DBIO.seq(anonymizationKeyQuery.delete, anonymizationKeyImageQuery.delete)
  }

  def listAnonymizationKeys = db.run(anonymizationKeyQuery.result)

  def listAnonymizationKeyImages = db.run(anonymizationKeyImageQuery.result)

  def anonymizationKeys(startIndex: Long, count: Long, orderBy: Option[String], orderAscending: Boolean, filter: Option[String]): Future[Seq[AnonymizationKey]] =
    checkColumnExists(dbConf, orderBy, AnonymizationKeyTable.name).flatMap { _ =>
      db.run {

        implicit val getResult = GetResult(r =>
          AnonymizationKey(r.nextLong, r.nextLong, r.nextString, r.nextString, r.nextString, r.nextString, r.nextString, r.nextString, r.nextString, r.nextString, r.nextString, r.nextString, r.nextString, r.nextString, r.nextString, r.nextString, r.nextString, r.nextString))

        var query = """select * from "AnonymizationKeys""""

        filter.foreach(filterValue => {
          val filterValueLike = s"'%$filterValue%'".toLowerCase
          query +=
            s""" where
        lcase("patientName") like $filterValueLike or 
          lcase("anonPatientName") like $filterValueLike or 
            lcase("patientID") like $filterValueLike or 
              lcase("anonPatientID") like $filterValueLike or
                lcase("studyDescription") like $filterValueLike or
                  lcase("accessionNumber") like $filterValueLike or
                    lcase("seriesDescription") like $filterValueLike or
                      lcase("protocolName") like $filterValueLike"""
        })

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

  def insertAnonymizationKeyImage(entry: AnonymizationKeyImage): Future[AnonymizationKeyImage] = db.run {
    (anonymizationKeyImageQuery returning anonymizationKeyImageQuery.map(_.id) += entry)
      .map(generatedId => entry.copy(id = generatedId))
  }

  def removeAnonymizationKeyAction(anonymizationKeyId: Long) =
    anonymizationKeyQuery.filter(_.id === anonymizationKeyId).delete.map(_ => {})

  def removeAnonymizationKey(anonymizationKeyId: Long): Future[Unit] = db.run(removeAnonymizationKeyAction(anonymizationKeyId))

  def anonymizationKeysForAnonPatient(anonPatientName: String, anonPatientID: String): Future[Seq[AnonymizationKey]] =
    db.run {
      anonymizationKeyQuery
        .filter(_.anonPatientName === anonPatientName)
        .filter(_.anonPatientID === anonPatientID)
        .result
    }

  def anonymizationKeysForPatient(patientName: String, patientID: String): Future[Seq[AnonymizationKey]] =
    db.run {
      anonymizationKeyQuery
        .filter(_.patientName === patientName)
        .filter(_.patientID === patientID)
        .result
    }

  def anonymizationKeyImagesForAnonymizationKeyIdAction(anonymizationKeyId: Long) =
    anonymizationKeyImageQuery.filter(_.anonymizationKeyId === anonymizationKeyId).result

  def anonymizationKeyImagesForAnonymizationKeyId(anonymizationKeyId: Long): Future[Seq[AnonymizationKeyImage]] =
    db.run(anonymizationKeyImagesForAnonymizationKeyIdAction(anonymizationKeyId))

  def anonymizationKeyImageForAnonymizationKeyIdAndImageId(anonymizationKeyId: Long, imageId: Long): Future[Option[AnonymizationKeyImage]] =
    db.run {
      anonymizationKeyImageQuery
        .filter(_.anonymizationKeyId === anonymizationKeyId)
        .filter(_.imageId === imageId)
        .result.headOption
    }

  def anonymizationKeysForImageIdAction(imageId: Long) = {
    val join = for {
      key <- anonymizationKeyQuery
      image <- anonymizationKeyImageQuery if image.anonymizationKeyId === key.id
    } yield (key, image)
    join.filter(_._2.imageId === imageId).map(_._1).result
  }

  def anonymizationKeysForImageId(imageId: Long): Future[Seq[AnonymizationKey]] = db.run(anonymizationKeysForImageIdAction(imageId))

  def removeAnonymizationKeyImagesForImageId(imageId: Long, purgeEmptyAnonymizationKeys: Boolean) = db.run {
    val action =
      if (purgeEmptyAnonymizationKeys)
        anonymizationKeysForImageIdAction(imageId).flatMap { keysForImage =>
          deleteAnonymizationKeyImagesForImageIdAction(imageId).flatMap { _ =>
            DBIO.sequence(keysForImage.map { key =>
              anonymizationKeyImagesForAnonymizationKeyIdAction(key.id).flatMap { keyImages =>
                if (keyImages.isEmpty)
                  removeAnonymizationKeyAction(key.id)
                else
                  DBIO.successful({})
              }
            })
          }
        }
      else
        deleteAnonymizationKeyImagesForImageIdAction(imageId)
    action.transactionally
  }

  private def deleteAnonymizationKeyImagesForImageIdAction(imageId: Long) =
    anonymizationKeyImageQuery.filter(_.imageId === imageId).delete.map(_ => {})

  val anonymizationKeysGetResult = GetResult(r =>
    AnonymizationKey(r.nextLong, r.nextLong, r.nextString, r.nextString, r.nextString, r.nextString, r.nextString, r.nextString, r.nextString, r.nextString, r.nextString, r.nextString, r.nextString, r.nextString, r.nextString, r.nextString, r.nextString, r.nextString))

  val queryAnonymizationKeysSelectPart = s"""select * from "${AnonymizationKeyTable.name}""""

  def queryAnonymizationKeys(startIndex: Long, count: Long, orderBy: Option[String], orderAscending: Boolean, queryProperties: Seq[QueryProperty]): Future[Seq[AnonymizationKey]] =
    checkColumnExists(dbConf, orderBy, AnonymizationKeyTable.name).flatMap { _ =>
      Future.sequence(queryProperties.map(qp => checkColumnExists(dbConf, qp.propertyName, AnonymizationKeyTable.name))).flatMap { _ =>
        db.run {
          import se.nimsa.sbx.metadata.MetaDataDAO._

          implicit val getResult = anonymizationKeysGetResult

          val query = queryAnonymizationKeysSelectPart +
            wherePart(queryPart(queryProperties)) +
            orderByPart(orderBy, orderAscending) +
            pagePart(startIndex, count)

          sql"#$query".as[AnonymizationKey]
        }
      }
    }
}
