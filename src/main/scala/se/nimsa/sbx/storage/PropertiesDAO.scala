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

package se.nimsa.sbx.storage

import scala.slick.driver.JdbcProfile
import scala.slick.jdbc.{ GetResult, StaticQuery => Q }
import se.nimsa.sbx.dicom.DicomHierarchy._
import se.nimsa.sbx.dicom.DicomPropertyValue._
import se.nimsa.sbx.seriestype.SeriesTypeDAO
import se.nimsa.sbx.seriestype.SeriesTypeProtocol._
import scala.slick.jdbc.meta.MTable
import StorageProtocol._

class PropertiesDAO(val driver: JdbcProfile) {
  import driver.simple._

  val metaDataDao = new MetaDataDAO(driver)
  val seriesTypeDao = new SeriesTypeDAO(driver)

  // *** Files ***

  private val toImageFile = (id: Long, fileName: String, sourceType: String, sourceId: Long) => ImageFile(id, FileName(fileName), SourceTypeId(SourceType.withName(sourceType), sourceId))

  private val fromImageFile = (imageFile: ImageFile) => Option((imageFile.id, imageFile.fileName.value, imageFile.sourceTypeId.sourceType.toString, imageFile.sourceTypeId.sourceId))

  private class ImageFiles(tag: Tag) extends Table[ImageFile](tag, "ImageFiles") {
    def id = column[Long]("id", O.PrimaryKey)
    def fileName = column[String]("fileName")
    def sourceType = column[String]("sourceType")
    def sourceId = column[Long]("sourceId")
    def * = (id, fileName, sourceType, sourceId) <> (toImageFile.tupled, fromImageFile)

    def imageFileToImageFKey = foreignKey("imageFileToImageFKey", id, metaDataDao.imagesQuery)(_.id, onUpdate = ForeignKeyAction.Cascade, onDelete = ForeignKeyAction.Cascade)
    def imageIdJoin = metaDataDao.imagesQuery.filter(_.id === id)
  }

  private val imageFilesQuery = TableQuery[ImageFiles]

  // *** Sources ***

  private val toSeriesSource = (id: Long, sourceType: String, sourceId: Long) => SeriesSource(id, SourceTypeId(SourceType.withName(sourceType), sourceId))

  private val fromSeriesSource = (seriesSource: SeriesSource) => Option((seriesSource.id, seriesSource.sourceTypeId.sourceType.toString, seriesSource.sourceTypeId.sourceId))

  private class SeriesSources(tag: Tag) extends Table[SeriesSource](tag, "SeriesSources") {
    def id = column[Long]("id", O.PrimaryKey)
    def sourceType = column[String]("sourceType")
    def sourceId = column[Long]("sourceId")
    def * = (id, sourceType, sourceId) <> (toSeriesSource.tupled, fromSeriesSource)

    def seriesSourceToImageFKey = foreignKey("seriesSourceToImageFKey", id, metaDataDao.seriesQuery)(_.id, onUpdate = ForeignKeyAction.Cascade, onDelete = ForeignKeyAction.Cascade)
    def seriesIdJoin = metaDataDao.seriesQuery.filter(_.id === id)
  }

  private val seriesSourceQuery = TableQuery[SeriesSources]

  // *** Series types ***

  private val toSeriesSeriesTypesRule = (seriesId: Long, seriesTypeId: Long) => SeriesSeriesType(seriesId, seriesTypeId)

  private val fromSeriesSeriesTypesRule = (seriesSeriesType: SeriesSeriesType) => Option((seriesSeriesType.seriesId, seriesSeriesType.seriesTypeId))

  private class SeriesSeriesTypeTable(tag: Tag) extends Table[SeriesSeriesType](tag, "SeriesSeriesTypes") {
    def seriesId = column[Long]("seriesid")
    def seriesTypeId = column[Long]("seriestypeid")
    def pk = primaryKey("pk_a", (seriesId, seriesTypeId))
    def fkSeries = foreignKey("fk_series_series", seriesId, metaDataDao.seriesQuery)(_.id, onDelete = ForeignKeyAction.Cascade)
    def fkSeriesType = foreignKey("fk_series_series_type", seriesTypeId, seriesTypeDao.seriesTypeQuery)(_.id, onDelete = ForeignKeyAction.Cascade)
    def * = (seriesId, seriesTypeId) <> (toSeriesSeriesTypesRule.tupled, fromSeriesSeriesTypesRule)
  }

  private val seriesSeriesTypeQuery = TableQuery[SeriesSeriesTypeTable]

  // Setup

  def create(implicit session: Session) =
    if (MTable.getTables("ImageFiles").list.isEmpty)
      (imageFilesQuery.ddl ++ seriesSourceQuery.ddl ++ seriesSeriesTypeQuery.ddl).create

  def drop(implicit session: Session) =
    if (MTable.getTables("ImageFiles").list.size > 0)
      (imageFilesQuery.ddl ++ seriesSourceQuery.ddl ++ seriesSeriesTypeQuery.ddl).drop

  def clear(implicit session: Session) = {
    imageFilesQuery.delete
    seriesSourceQuery.delete
    seriesSeriesTypeQuery.delete
  }

  // Functions

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
    metaDataDao.imagesForSeries(0, 100000, seriesId)
      .map(image => imageFileForImage(image.id)).flatten.toList

  def imageFilesForStudy(studyId: Long)(implicit session: Session): List[ImageFile] =
    metaDataDao.seriesForStudy(0, Integer.MAX_VALUE, studyId)
      .map(series => metaDataDao.imagesForSeries(0, 100000, series.id)
        .map(image => imageFileForImage(image.id)).flatten).flatten

  def imageFilesForPatient(patientId: Long)(implicit session: Session): List[ImageFile] =
    metaDataDao.studiesForPatient(0, Integer.MAX_VALUE, patientId)
      .map(study => metaDataDao.seriesForStudy(0, Integer.MAX_VALUE, study.id)
        .map(series => metaDataDao.imagesForSeries(0, 100000, series.id)
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

  def flatSeries(startIndex: Long, count: Long, orderBy: Option[String], orderAscending: Boolean, filter: Option[String], sourceTypeIds: Array[SourceTypeId], seriesTypeIds: Array[Long])(implicit session: Session): List[FlatSeries] = {

    if (isWithAdvancedFiltering(sourceTypeIds, seriesTypeIds)) {

      metaDataDao.checkOrderBy(orderBy, "Patients", "Studies", "Equipments", "FrameOfReferences", "Series")

      implicit val getResult = metaDataDao.flatSeriesGetResult

      val query =
        metaDataDao.flatSeriesBasePart +
          propertiesJoinPart(sourceTypeIds, seriesTypeIds) +
          " where" +
          metaDataDao.flatSeriesFilterPart(filter) +
          andPart(filter, sourceTypeIds) +
          sourcesPart(sourceTypeIds) +
          andPart(filter, sourceTypeIds, seriesTypeIds) +
          seriesTypesPart(seriesTypeIds) +
          metaDataDao.orderByPart(orderBy, orderAscending) +
          metaDataDao.pagePart(startIndex, count)

      Q.queryNA(query).list

    } else
      metaDataDao.flatSeries(startIndex, count, orderBy, orderAscending, filter)
  }

  def propertiesJoinPart(sourceTypeIds: Array[SourceTypeId], seriesTypeIds: Array[Long]) =
    singlePropertyJoinPart(sourceTypeIds, """ inner join "SeriesSources" on "Series"."id" = "SeriesSources"."id"""") +
      singlePropertyJoinPart(seriesTypeIds, """ inner join "SeriesSeriesTypes" on "Series"."id" = "SeriesSeriesTypes"."seriesid"""")

  def singlePropertyJoinPart(property: Array[_ <: Any], part: String) = if (property.isEmpty) "" else part

  def patients(startIndex: Long, count: Long, orderBy: Option[String], orderAscending: Boolean, filter: Option[String], sourceTypeIds: Array[SourceTypeId], seriesTypeIds: Array[Long])(implicit session: Session): List[Patient] = {

    if (isWithAdvancedFiltering(sourceTypeIds, seriesTypeIds)) {

      metaDataDao.checkOrderBy(orderBy, "Patients")

      implicit val getResult = metaDataDao.patientsGetResult

      val query =
        patientsBasePart +
          propertiesJoinPart(sourceTypeIds, seriesTypeIds) +
          " where" +
          metaDataDao.patientsFilterPart(filter) +
          andPart(filter, sourceTypeIds) +
          sourcesPart(sourceTypeIds) +
          andPart(filter, sourceTypeIds, seriesTypeIds) +
          seriesTypesPart(seriesTypeIds) +
          metaDataDao.orderByPart(orderBy, orderAscending) +
          metaDataDao.pagePart(startIndex, count)

      Q.queryNA(query).list

    } else
      metaDataDao.patients(startIndex, count, orderBy, orderAscending, filter)
  }

  def isWithAdvancedFiltering(arrays: Array[_ <: Any]*) = arrays.exists(!_.isEmpty)

  def patientsBasePart = s"""select distinct 
      "Patients"."id","Patients"."PatientName","Patients"."PatientID","Patients"."PatientBirthDate","Patients"."PatientSex"
       from "Series" 
       inner join "Patients" on "Studies"."patientId" = "Patients"."id"
       inner join "Studies" on "Series"."studyId" = "Studies"."id""""

  def andPart(target: Array[_ <: Any]) = if (!target.isEmpty) " and" else ""

  def andPart(option: Option[Any], target: Array[_ <: Any]) = if (option.isDefined && !target.isEmpty) " and" else ""

  def andPart(option: Option[Any], array: Array[_ <: Any], target: Array[_ <: Any]) = if ((option.isDefined || !array.isEmpty) && !target.isEmpty) " and" else ""

  def sourcesPart(sourceTypeIds: Array[SourceTypeId]) =
    if (sourceTypeIds.isEmpty)
      ""
    else
      " (" + sourceTypeIds.map(sourceTypeId =>
        s""""SeriesSources"."sourceType" = '${sourceTypeId.sourceType}' and "SeriesSources"."sourceId" = ${sourceTypeId.sourceId}""")
        .mkString(" or ") + ")"

  def seriesTypesPart(seriesTypeIds: Array[Long]) =
    if (seriesTypeIds.isEmpty)
      ""
    else
      " (" + seriesTypeIds.map(seriesTypeId =>
        s""""SeriesSeriesTypes"."seriestypeid" = $seriesTypeId""")
        .mkString(" or ") + ")"

  def studiesGetResult = GetResult(r =>
    Study(r.nextLong, r.nextLong, StudyInstanceUID(r.nextString), StudyDescription(r.nextString), StudyDate(r.nextString), StudyID(r.nextString), AccessionNumber(r.nextString), PatientAge(r.nextString)))

  def studiesForPatient(startIndex: Long, count: Long, patientId: Long, sourceTypeIds: Array[SourceTypeId], seriesTypeIds: Array[Long])(implicit session: Session): List[Study] = {

    if (isWithAdvancedFiltering(sourceTypeIds, seriesTypeIds)) {

      implicit val getResult = studiesGetResult

      val basePart = s"""select distinct 
        "Studies"."id","Studies"."patientId","Studies"."StudyInstanceUID","Studies"."StudyDescription","Studies"."StudyDate","Studies"."StudyID","Studies"."AccessionNumber","Studies"."PatientAge"
        from "Series" 
        inner join "Studies" on "Series"."studyId" = "Studies"."id""""

      val wherePart = s"""
        where
        "Studies"."patientId" = $patientId"""

      val query = basePart +
        propertiesJoinPart(sourceTypeIds, seriesTypeIds) +
        wherePart +
        andPart(sourceTypeIds) +
        sourcesPart(sourceTypeIds) +
        andPart(seriesTypeIds) +
        seriesTypesPart(seriesTypeIds) +
        metaDataDao.pagePart(startIndex, count)

      Q.queryNA(query).list

    } else
      metaDataDao.studiesForPatient(startIndex, count, patientId)
  }

  def seriesGetResult = GetResult(r =>
    Series(r.nextLong, r.nextLong, r.nextLong, r.nextLong, SeriesInstanceUID(r.nextString), SeriesDescription(r.nextString), SeriesDate(r.nextString), Modality(r.nextString), ProtocolName(r.nextString), BodyPartExamined(r.nextString)))

  def seriesForStudy(startIndex: Long, count: Long, studyId: Long, sourceTypeIds: Array[SourceTypeId], seriesTypeIds: Array[Long])(implicit session: Session): List[Series] = {

    if (isWithAdvancedFiltering(sourceTypeIds, seriesTypeIds)) {

      implicit val getResult = seriesGetResult

      val basePart = s"""select distinct 
        "Series"."id", "Series"."studyId", "Series"."equipmentId", "Series"."frameOfReferenceId", "Series"."SeriesInstanceUID", "Series"."SeriesDescription", "Series"."SeriesDate", "Series"."Modality", "Series"."ProtocolName", "Series"."BodyPartExamined"
        from "Series""""

      val wherePart = s"""
        where
        "Series"."studyId" = $studyId"""

      val query = basePart +
        propertiesJoinPart(sourceTypeIds, seriesTypeIds) +
        wherePart +
        andPart(sourceTypeIds) +
        sourcesPart(sourceTypeIds) +
        andPart(seriesTypeIds) +
        seriesTypesPart(seriesTypeIds) +
        metaDataDao.pagePart(startIndex, count)

      Q.queryNA(query).list

    } else
      metaDataDao.seriesForStudy(startIndex, count, studyId)
  }

  def deleteFully(imageFile: ImageFile)(implicit session: Session): Unit = {
    deleteImageFile(imageFile.id)
    metaDataDao.imageById(imageFile.id).foreach(image =>
      metaDataDao.deleteFully(image))
  }

  def insertSeriesSeriesType(seriesSeriesType: SeriesSeriesType)(implicit session: Session): SeriesSeriesType = {
    seriesSeriesTypeQuery += seriesSeriesType
    seriesSeriesType
  }

  def listSeriesSeriesTypesForSeriesId(seriesId: Long)(implicit session: Session): List[SeriesSeriesType] =
    seriesSeriesTypeQuery.filter(_.seriesId === seriesId).list

  def removeSeriesTypesForSeriesId(seriesId: Long)(implicit session: Session): Unit =
    seriesSeriesTypeQuery.filter(_.seriesId === seriesId).delete

  def seriesTypesForSeries(seriesId: Long)(implicit session: Session) =
    seriesSeriesTypeQuery.filter(_.seriesId === seriesId)
      .innerJoin(seriesTypeDao.seriesTypeQuery).on(_.seriesTypeId === _.id)
      .map(_._2).list

}
