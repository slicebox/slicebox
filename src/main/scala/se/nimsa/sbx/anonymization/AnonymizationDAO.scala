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

import scala.slick.driver.JdbcProfile
import org.h2.jdbc.JdbcSQLException
import scala.slick.jdbc.meta.MTable
import scala.slick.jdbc.{ GetResult, StaticQuery => Q }
import se.nimsa.sbx.dicom.DicomProperty
import AnonymizationProtocol._

class AnonymizationDAO(val driver: JdbcProfile) {
  import driver.simple._

  class AnonymizationKeyTable(tag: Tag) extends Table[AnonymizationKey](tag, "AnonymizationKeys") {
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

  val anonymizationKeyQuery = TableQuery[AnonymizationKeyTable]

  class AnonymizationKeyImageTable(tag: Tag) extends Table[AnonymizationKeyImage](tag, "AnonymizationKeyImages") {
    def id = column[Long]("id", O.PrimaryKey, O.AutoInc)
    def anonymizationKeyId = column[Long]("anonymizationkeyid")
    def imageId = column[Long]("imageid")
    def fkAnonymizationKey = foreignKey("fk_anonymization_key", anonymizationKeyId, anonymizationKeyQuery)(_.id, onDelete = ForeignKeyAction.Cascade)
    def * = (id, anonymizationKeyId, imageId) <> (AnonymizationKeyImage.tupled, AnonymizationKeyImage.unapply)
  }

  val anonymizationKeyImageQuery = TableQuery[AnonymizationKeyImageTable]

  def create(implicit session: Session): Unit = {
    if (MTable.getTables("AnonymizationKeys").list.isEmpty) anonymizationKeyQuery.ddl.create
    if (MTable.getTables("AnonymizationKeyImages").list.isEmpty) anonymizationKeyImageQuery.ddl.create
  }

  def drop(implicit session: Session): Unit =
    (anonymizationKeyQuery.ddl ++ anonymizationKeyImageQuery.ddl).drop

  def clear(implicit session: Session): Unit = {
    anonymizationKeyQuery.delete
    anonymizationKeyImageQuery.delete
  }

  def columnExists(tableName: String, columnName: String)(implicit session: Session): Boolean = {
    val tables = MTable.getTables(tableName).list
    if (tables.isEmpty)
      false
    else
      !tables(0).getColumns.list.filter(_.name == columnName).isEmpty
  }

  def checkOrderBy(orderBy: Option[String], tableNames: String*)(implicit session: Session) =
    orderBy.foreach(columnName =>
      if (!tableNames.exists(tableName =>
        columnExists(tableName, columnName)))
        throw new IllegalArgumentException(s"Property $columnName does not exist"))

  def anonymizationKeys(startIndex: Long, count: Long, orderBy: Option[String], orderAscending: Boolean, filter: Option[String])(implicit session: Session): List[AnonymizationKey] = {

    checkOrderBy(orderBy, "AnonymizationKeys")

    implicit val getResult = GetResult(r =>
      AnonymizationKey(r.nextLong, r.nextLong, r.nextString, r.nextString, r.nextString, r.nextString, r.nextString, r.nextString, r.nextString, r.nextString, r.nextString, r.nextString, r.nextString, r.nextString, r.nextString, r.nextString, r.nextString, r.nextString))

    var query = """select * from "AnonymizationKeys""""

    filter.foreach(filterValue => {
      val filterValueLike = s"'%$filterValue%'".toLowerCase
      query += s""" where 
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

    Q.queryNA(query).list
  }

  def insertAnonymizationKey(entry: AnonymizationKey)(implicit session: Session): AnonymizationKey = {
    val generatedId = (anonymizationKeyQuery returning anonymizationKeyQuery.map(_.id)) += entry
    entry.copy(id = generatedId)
  }

  def insertAnonymizationKeyImage(entry: AnonymizationKeyImage)(implicit session: Session): AnonymizationKeyImage = {
    val generatedId = (anonymizationKeyImageQuery returning anonymizationKeyImageQuery.map(_.id)) += entry
    entry.copy(id = generatedId)
  }

  def removeAnonymizationKey(anonymizationKeyId: Long)(implicit session: Session): Unit =
    anonymizationKeyQuery.filter(_.id === anonymizationKeyId).delete

  def anonymizationKeysForAnonPatient(anonPatientName: String, anonPatientID: String)(implicit session: Session): List[AnonymizationKey] =
    anonymizationKeyQuery
      .filter(_.anonPatientName === anonPatientName)
      .filter(_.anonPatientID === anonPatientID)
      .list

  def anonymizationKeysForPatient(patientName: String, patientID: String)(implicit session: Session): List[AnonymizationKey] =
    anonymizationKeyQuery
      .filter(_.patientName === patientName)
      .filter(_.patientID === patientID)
      .list

  def anonymizationKeyImagesForAnonymizationKeyId(anonymizationKeyId: Long)(implicit session: Session): List[AnonymizationKeyImage] =
    anonymizationKeyImageQuery.filter(_.anonymizationKeyId === anonymizationKeyId).list

  def anonymizationKeysForImageId(imageId: Long)(implicit session: Session): List[AnonymizationKey] = {
    val join = for {
      key <- anonymizationKeyQuery
      image <- anonymizationKeyImageQuery if image.anonymizationKeyId === key.id
    } yield (key, image)
    join.filter(_._2.imageId === imageId).map(_._1).list    
  }
  
  def removeAnonymizationKeyImagesForImageId(imageId: Long)(implicit session: Session) = 
    anonymizationKeyImageQuery.filter(_.imageId === imageId).delete
  
}
