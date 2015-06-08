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

package se.nimsa.sbx.dicom

import scala.slick.driver.JdbcProfile
import scala.slick.jdbc.{ GetResult, StaticQuery => Q }
import DicomProtocol._
import DicomHierarchy._
import DicomPropertyValue._
import scala.slick.jdbc.meta.MTable

class DicomPropertiesDAO(val driver: JdbcProfile) {
  import driver.simple._

  val metaDataDao = new DicomMetaDataDAO(driver)
  import metaDataDao._

  // *** Files ***

  private val toImageFile = (id: Long, fileName: String, sourceType: String, sourceId: Long) => ImageFile(id, FileName(fileName), SourceType.withName(sourceType), sourceId)

  private val fromImageFile = (imageFile: ImageFile) => Option((imageFile.id, imageFile.fileName.value, imageFile.sourceType.toString, imageFile.sourceId))

  private class ImageFiles(tag: Tag) extends Table[ImageFile](tag, "ImageFiles") {
    def id = column[Long]("id", O.PrimaryKey)
    def fileName = column[String]("fileName")
    def sourceType = column[String]("sourceType")
    def sourceId = column[Long]("sourceId")
    def * = (id, fileName, sourceType, sourceId) <> (toImageFile.tupled, fromImageFile)

    def imageFileToImageFKey = foreignKey("imageFileToImageFKey", id, imagesQuery)(_.id, onUpdate = ForeignKeyAction.Cascade, onDelete = ForeignKeyAction.Cascade)
    def imageIdJoin = imagesQuery.filter(_.id === id)
  }

  private val imageFilesQuery = TableQuery[ImageFiles]

  // *** Sources ***

  private val toSeriesSource = (id: Long, sourceType: String, sourceId: Long) => SeriesSource(id, SourceType.withName(sourceType), sourceId)

  private val fromSeriesSource = (seriesSource: SeriesSource) => Option((seriesSource.id, seriesSource.sourceType.toString, seriesSource.sourceId))

  private class SeriesSources(tag: Tag) extends Table[SeriesSource](tag, "SeriesSources") {
    def id = column[Long]("id", O.PrimaryKey)
    def sourceType = column[String]("sourceType")
    def sourceId = column[Long]("sourceId")
    def * = (id, sourceType, sourceId) <> (toSeriesSource.tupled, fromSeriesSource)

    def seriesSourceToImageFKey = foreignKey("seriesSourceToImageFKey", id, seriesQuery)(_.id, onUpdate = ForeignKeyAction.Cascade, onDelete = ForeignKeyAction.Cascade)
    def seriesIdJoin = seriesQuery.filter(_.id === id)
  }

  private val seriesSourceQuery = TableQuery[SeriesSources]

  def create(implicit session: Session) =
    if (MTable.getTables("ImageFiles").list.isEmpty)
      (imageFilesQuery.ddl ++ seriesSourceQuery.ddl).create

  def drop(implicit session: Session) =
    if (MTable.getTables("ImageFiles").list.size > 0)
      (imageFilesQuery.ddl ++ seriesSourceQuery.ddl).drop

  def imageFileById(imageId: Long)(implicit session: Session): Option[ImageFile] =
    imageFilesQuery.filter(_.id === imageId).list.headOption

  def insertImageFile(imageFile: ImageFile)(implicit session: Session): ImageFile = {
    imageFilesQuery += imageFile
    imageFile
  }

  def imageFiles(implicit session: Session): List[ImageFile] = imageFilesQuery.list

  def imageFilesForSource(sourceType: SourceType, sourceId: Long)(implicit session: Session): List[ImageFile] =
    imageFilesQuery
      .filter(_.sourceType === sourceType.toString)
      .filter(_.sourceId === sourceId)
      .list

  def imageFileForImage(imageId: Long)(implicit session: Session): Option[ImageFile] =
    imageFilesQuery
      .filter(_.id === imageId)
      .list.headOption

  def imageFilesForSeries(seriesId: Long)(implicit session: Session): List[ImageFile] =
    imagesForSeries(0, 100000, seriesId)
      .map(image => imageFileForImage(image.id)).flatten.toList

  def imageFilesForStudy(studyId: Long)(implicit session: Session): List[ImageFile] =
    seriesForStudy(0, Integer.MAX_VALUE, studyId)
      .map(series => imagesForSeries(0, 100000, series.id)
        .map(image => imageFileForImage(image.id)).flatten).flatten

  def imageFilesForPatient(patientId: Long)(implicit session: Session): List[ImageFile] =
    studiesForPatient(0, Integer.MAX_VALUE, patientId)
      .map(study => seriesForStudy(0, Integer.MAX_VALUE, study.id)
        .map(series => imagesForSeries(0, 100000, series.id)
          .map(image => imageFileForImage(image.id)).flatten).flatten).flatten

  def imageFileByFileName(imageFile: ImageFile)(implicit session: Session): Option[ImageFile] =
    imageFilesQuery
      .filter(_.fileName === imageFile.fileName.value)
      .list.headOption

  def deleteImageFile(imageId: Long)(implicit session: Session): Int = {
    imageFilesQuery
      .filter(_.id === imageId)
      .delete
  }

  def insertSeriesSource(seriesSource: SeriesSource)(implicit session: Session): SeriesSource = {
    seriesSourceQuery += seriesSource
    seriesSource
  }

  def seriesSourceById(seriesId: Long)(implicit session: Session): Option[SeriesSource] =
    seriesSourceQuery.filter(_.id === seriesId).list.headOption

  def seriesSources(implicit session: Session): List[SeriesSource] = seriesSourceQuery.list

  val flatSeriesQuery = """select "Series"."id", 
      "Patients"."id", "Patients"."PatientName", "Patients"."PatientID", "Patients"."PatientBirthDate","Patients"."PatientSex", 
      "Studies"."id", "Studies"."patientId", "Studies"."StudyInstanceUID", "Studies"."StudyDescription", "Studies"."StudyDate", "Studies"."StudyID", "Studies"."AccessionNumber", "Studies"."PatientAge",
      "Equipments"."id", "Equipments"."Manufacturer", "Equipments"."StationName",
      "FrameOfReferences"."id", "FrameOfReferences"."FrameOfReferenceUID",
      "Series"."id", "Series"."studyId", "Series"."equipmentId", "Series"."frameOfReferenceId", "Series"."SeriesInstanceUID", "Series"."SeriesDescription", "Series"."SeriesDate", "Series"."Modality", "Series"."ProtocolName", "Series"."BodyPartExamined"
       from "Series" 
       inner join "Studies" on "Series"."studyId" = "Studies"."id" 
       inner join "Equipments" on "Series"."equipmentId" = "Equipments"."id"
       inner join "FrameOfReferences" on "Series"."frameOfReferenceId" = "FrameOfReferences"."id"
       inner join "Patients" on "Studies"."patientId" = "Patients"."id"
       inner join "SeriesSources" on "Series"."id" = "SeriesSources"."id"
       """

  def flatSeriesGetResult = GetResult(r =>
    FlatSeries(r.nextLong,
      Patient(r.nextLong, PatientName(r.nextString), PatientID(r.nextString), PatientBirthDate(r.nextString), PatientSex(r.nextString)),
      Study(r.nextLong, r.nextLong, StudyInstanceUID(r.nextString), StudyDescription(r.nextString), StudyDate(r.nextString), StudyID(r.nextString), AccessionNumber(r.nextString), PatientAge(r.nextString)),
      Equipment(r.nextLong, Manufacturer(r.nextString), StationName(r.nextString)),
      FrameOfReference(r.nextLong, FrameOfReferenceUID(r.nextString)),
      Series(r.nextLong, r.nextLong, r.nextLong, r.nextLong, SeriesInstanceUID(r.nextString), SeriesDescription(r.nextString), SeriesDate(r.nextString), Modality(r.nextString), ProtocolName(r.nextString), BodyPartExamined(r.nextString))))

  def flatSeries(startIndex: Long, count: Long, orderBy: Option[String], orderAscending: Boolean, filter: Option[String], sourceType: Option[SourceType], sourceId: Option[Long])(implicit session: Session): List[FlatSeries] = {

    if (sourceType.isEmpty || sourceId.isEmpty)
      metaDataDao.flatSeries(startIndex, count, orderBy, orderAscending, filter)

    else {
      checkOrderBy(orderBy, "Patients", "Studies", "Equipments", "FrameOfReferences", "Series")

      implicit val getResult = flatSeriesGetResult

      var query = flatSeriesQuery

      query += s"""where
        "SeriesSources"."sourceType" = '${sourceType.get.toString}' and "SeriesSources"."sourceId" = ${sourceId.get}
        """

      filter.foreach(filterValue => {
        val filterValueLike = s"'%$filterValue%'".toLowerCase
        query += s"""and (
        lcase("Series"."id") like $filterValueLike or
          lcase("PatientName") like $filterValueLike or 
          lcase("PatientID") like $filterValueLike or 
          lcase("PatientBirthDate") like $filterValueLike or 
          lcase("PatientSex") like $filterValueLike or
            lcase("StudyDescription") like $filterValueLike or
            lcase("StudyDate") like $filterValueLike or
            lcase("StudyID") like $filterValueLike or
            lcase("AccessionNumber") like $filterValueLike or
            lcase("PatientAge") like $filterValueLike or
              lcase("Manufacturer") like $filterValueLike or
              lcase("StationName") like $filterValueLike or
                lcase("SeriesDescription") like $filterValueLike or
                lcase("SeriesDate") like $filterValueLike or
                lcase("Modality") like $filterValueLike or
                lcase("ProtocolName") like $filterValueLike or
                lcase("BodyPartExamined") like $filterValueLike)
                """
      })

      orderBy.foreach(orderByValue =>
        query += s"""order by "$orderByValue" ${if (orderAscending) "asc" else "desc"}
      """)

      query += s"""limit $count offset $startIndex"""

      Q.queryNA(query).list
    }
  }

  def flatSeriesById(seriesId: Long)(implicit session: Session): Option[FlatSeries] = {

    implicit val getResult = flatSeriesGetResult
    val query = flatSeriesQuery + s""" where "Series"."id" = $seriesId"""

    Q.queryNA(query).list.headOption
  }

  def patients(startIndex: Long, count: Long, orderBy: Option[String], orderAscending: Boolean, filter: Option[String], sourceType: Option[SourceType], sourceId: Option[Long])(implicit session: Session): List[Patient] = {

    if (sourceType.isEmpty || sourceId.isEmpty)
      metaDataDao.patients(startIndex, count, orderBy, orderAscending, filter)

    else {
      checkOrderBy(orderBy, "Patients")

      implicit val getResult = patientsGetResult

      var query = s"""select 
      "Patients"."id", "Patients"."PatientName", "Patients"."PatientID", "Patients"."PatientBirthDate","Patients"."PatientSex"
       from "Series" 
       inner join "SeriesSources" on "Series"."id" = "SeriesSources"."id"
       inner join "Studies" on "Series"."studyId" = "Studies"."id" 
       inner join "Patients" on "Studies"."patientId" = "Patients"."id"
       where
       "SeriesSources"."sourceType" = '${sourceType.get.toString}' and "SeriesSources"."sourceId" = ${sourceId.get}
       """

      filter.foreach(filterValue => {
        val filterValueLike = s"'%$filterValue%'".toLowerCase
        query += s"""and (
        lcase("PatientName") like $filterValueLike or 
          lcase("PatientID") like $filterValueLike or 
            lcase("PatientBirthDate") like $filterValueLike or 
              lcase("PatientSex") like $filterValueLike)
              """
      })

      orderBy.foreach(orderByValue =>
        query += s"""order by "$orderByValue" ${if (orderAscending) "asc" else "desc"}
        """)

      query += s"""group by "Patients"."id"
        limit $count offset $startIndex
        """

      Q.queryNA(query).list
    }
  }
}
