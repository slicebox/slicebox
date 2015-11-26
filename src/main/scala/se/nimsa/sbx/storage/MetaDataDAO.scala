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
import se.nimsa.sbx.dicom.DicomProperty
import se.nimsa.sbx.dicom.DicomHierarchy._
import se.nimsa.sbx.dicom.DicomPropertyValue._
import scala.slick.jdbc.meta.MTable
import StorageProtocol._

class MetaDataDAO(val driver: JdbcProfile) {
  import driver.simple._

  // *** Patient *** 

  val toPatient = (id: Long, patientName: String, patientID: String, patientBirthDate: String, patientSex: String) =>
    Patient(id, PatientName(patientName), PatientID(patientID), PatientBirthDate(patientBirthDate), PatientSex(patientSex))

  val fromPatient = (patient: Patient) => Option((patient.id, patient.patientName.value, patient.patientID.value, patient.patientBirthDate.value, patient.patientSex.value))

  class Patients(tag: Tag) extends Table[Patient](tag, "Patients") {
    def id = column[Long]("id", O.PrimaryKey, O.AutoInc)
    def patientName = column[String](DicomProperty.PatientName.name)
    def patientID = column[String](DicomProperty.PatientID.name)
    def patientBirthDate = column[String](DicomProperty.PatientBirthDate.name)
    def patientSex = column[String](DicomProperty.PatientSex.name)
    def * = (id, patientName, patientID, patientBirthDate, patientSex) <> (toPatient.tupled, fromPatient)
  }

  val patientsQuery = TableQuery[Patients]

  val fromStudy = (study: Study) => Option((study.id, study.patientId, study.studyInstanceUID.value, study.studyDescription.value, study.studyDate.value, study.studyID.value, study.accessionNumber.value, study.patientAge.value))

  // *** Study *** //

  val toStudy = (id: Long, patientId: Long, studyInstanceUID: String, studyDescription: String, studyDate: String, studyID: String, accessionNumber: String, patientAge: String) =>
    Study(id, patientId, StudyInstanceUID(studyInstanceUID), StudyDescription(studyDescription), StudyDate(studyDate), StudyID(studyID), AccessionNumber(accessionNumber), PatientAge(patientAge))

  class Studies(tag: Tag) extends Table[Study](tag, "Studies") {
    def id = column[Long]("id", O.PrimaryKey, O.AutoInc)
    def patientId = column[Long]("patientId")
    def studyInstanceUID = column[String](DicomProperty.StudyInstanceUID.name)
    def studyDescription = column[String](DicomProperty.StudyDescription.name)
    def studyDate = column[String](DicomProperty.StudyDate.name)
    def studyID = column[String](DicomProperty.StudyID.name)
    def accessionNumber = column[String](DicomProperty.AccessionNumber.name)
    def patientAge = column[String](DicomProperty.PatientAge.name)
    def * = (id, patientId, studyInstanceUID, studyDescription, studyDate, studyID, accessionNumber, patientAge) <> (toStudy.tupled, fromStudy)

    def patientFKey = foreignKey("patientFKey", patientId, patientsQuery)(_.id, onUpdate = ForeignKeyAction.Cascade, onDelete = ForeignKeyAction.Cascade)
    def patientIdJoin = patientsQuery.filter(_.id === patientId)
  }

  val studiesQuery = TableQuery[Studies]

  // *** Series ***

  val toSeries = (id: Long, studyId: Long, seriesInstanceUID: String, seriesDescription: String, seriesDate: String, modality: String, protocolName: String, bodyPartExamined: String, manufacturer: String, stationName: String, frameOfReferenceUID: String) =>
    Series(id, studyId, SeriesInstanceUID(seriesInstanceUID), SeriesDescription(seriesDescription), SeriesDate(seriesDate), Modality(modality), ProtocolName(protocolName), BodyPartExamined(bodyPartExamined), Manufacturer(manufacturer), StationName(stationName), FrameOfReferenceUID(frameOfReferenceUID))

  val fromSeries = (series: Series) => Option((series.id, series.studyId, series.seriesInstanceUID.value, series.seriesDescription.value, series.seriesDate.value, series.modality.value, series.protocolName.value, series.bodyPartExamined.value, series.manufacturer.value, series.stationName.value, series.frameOfReferenceUID.value))

  class SeriesTable(tag: Tag) extends Table[Series](tag, "Series") {
    def id = column[Long]("id", O.PrimaryKey, O.AutoInc)
    def studyId = column[Long]("studyId")
    def seriesInstanceUID = column[String](DicomProperty.SeriesInstanceUID.name)
    def seriesDescription = column[String](DicomProperty.SeriesDescription.name)
    def seriesDate = column[String](DicomProperty.SeriesDate.name)
    def modality = column[String](DicomProperty.Modality.name)
    def protocolName = column[String](DicomProperty.ProtocolName.name)
    def bodyPartExamined = column[String](DicomProperty.BodyPartExamined.name)
    def manufacturer = column[String](DicomProperty.Manufacturer.name)
    def stationName = column[String](DicomProperty.StationName.name)
    def frameOfReferenceUID = column[String](DicomProperty.FrameOfReferenceUID.name)
    def * = (id, studyId, seriesInstanceUID, seriesDescription, seriesDate, modality, protocolName, bodyPartExamined, manufacturer, stationName, frameOfReferenceUID) <> (toSeries.tupled, fromSeries)

    def studyFKey = foreignKey("studyFKey", studyId, studiesQuery)(_.id, onUpdate = ForeignKeyAction.Cascade, onDelete = ForeignKeyAction.Cascade)
    def studyIdJoin = studiesQuery.filter(_.id === studyId)
  }

  val seriesQuery = TableQuery[SeriesTable]

  // *** Image ***

  val toImage = (id: Long, seriesId: Long, sopInstanceUID: String, imageType: String, instanceNumber: String) =>
    Image(id, seriesId, SOPInstanceUID(sopInstanceUID), ImageType(imageType), InstanceNumber(instanceNumber))

  val fromImage = (image: Image) => Option((image.id, image.seriesId, image.sopInstanceUID.value, image.imageType.value, image.instanceNumber.value))

  class Images(tag: Tag) extends Table[Image](tag, "Images") {
    def id = column[Long]("id", O.PrimaryKey, O.AutoInc)
    def seriesId = column[Long]("seriesId")
    def sopInstanceUID = column[String](DicomProperty.SOPInstanceUID.name)
    def imageType = column[String](DicomProperty.ImageType.name)
    def instanceNumber = column[String](DicomProperty.InstanceNumber.name)
    def * = (id, seriesId, sopInstanceUID, imageType, instanceNumber) <> (toImage.tupled, fromImage)

    def seriesFKey = foreignKey("seriesFKey", seriesId, seriesQuery)(_.id, onUpdate = ForeignKeyAction.Cascade, onDelete = ForeignKeyAction.Cascade)
    def seriesIdJoin = seriesQuery.filter(_.id === seriesId)
  }

  val imagesQuery = TableQuery[Images]

  def create(implicit session: Session) = {
    if (MTable.getTables("Patients").list.isEmpty) patientsQuery.ddl.create
    if (MTable.getTables("Studies").list.isEmpty) studiesQuery.ddl.create
    if (MTable.getTables("Series").list.isEmpty) seriesQuery.ddl.create
    if (MTable.getTables("Images").list.isEmpty) imagesQuery.ddl.create
  }

  def drop(implicit session: Session) =
    if (MTable.getTables("Patients").list.size > 0)
      (patientsQuery.ddl ++
        studiesQuery.ddl ++
        seriesQuery.ddl ++
        imagesQuery.ddl).drop

  def clear(implicit session: Session) = {
    patientsQuery.delete
    studiesQuery.delete
    seriesQuery.delete
    imagesQuery.delete
  }

  def columnExists(tableName: String, columnName: String)(implicit session: Session): Boolean = {
    val tables = MTable.getTables(tableName).list
    if (tables.isEmpty)
      false
    else
      !tables(0).getColumns.list.filter(_.name == columnName).isEmpty
  }

  def checkColumnExists(columnName: String, tableNames: String*)(implicit session: Session) =
    if (!tableNames.exists(tableName => columnExists(tableName, columnName)))
      throw new IllegalArgumentException(s"Property $columnName does not exist")

  // *** Complete listings
  
  def patients(implicit session: Session): List[Patient] = patientsQuery.list

  def studies(implicit session: Session): List[Study] = studiesQuery.list
  
  def series(implicit session: Session): List[Series] = seriesQuery.list

  def images(implicit session: Session): List[Image] = imagesQuery.list

  // *** Get entities by id

  def patientById(id: Long)(implicit session: Session): Option[Patient] =
    patientsQuery.filter(_.id === id).firstOption

  def studyById(id: Long)(implicit session: Session): Option[Study] =
    studiesQuery.filter(_.id === id).firstOption

  def seriesById(id: Long)(implicit session: Session): Option[Series] =
    seriesQuery.filter(_.id === id).firstOption

  def imageById(id: Long)(implicit session: Session): Option[Image] =
    imagesQuery.filter(_.id === id).firstOption

  // *** Inserts ***

  def insert(patient: Patient)(implicit session: Session): Patient = {
    val generatedId = (patientsQuery returning patientsQuery.map(_.id)) += patient
    patient.copy(id = generatedId)
  }

  def insert(study: Study)(implicit session: Session): Study = {
    val generatedId = (studiesQuery returning studiesQuery.map(_.id)) += study
    study.copy(id = generatedId)
  }

  def insert(series: Series)(implicit session: Session): Series = {
    val generatedId = (seriesQuery returning seriesQuery.map(_.id)) += series
    series.copy(id = generatedId)
  }

  def insert(image: Image)(implicit session: Session): Image = {
    val generatedId = (imagesQuery returning imagesQuery.map(_.id)) += image
    image.copy(id = generatedId)
  }

  // *** Listing all patients, studies etc ***

  val patientsGetResult = GetResult(r =>
    Patient(r.nextLong, PatientName(r.nextString), PatientID(r.nextString), PatientBirthDate(r.nextString), PatientSex(r.nextString)))

  def patients(startIndex: Long, count: Long, orderBy: Option[String], orderAscending: Boolean, filter: Option[String])(implicit session: Session): List[Patient] = {

    orderBy.foreach(checkColumnExists(_, "Patients"))

    implicit val getResult = patientsGetResult

    val query =
      patientsBasePart +
        wherePart(filter) +
        patientsFilterPart(filter) +
        orderByPart(orderBy, orderAscending) +
        pagePart(startIndex, count)

    Q.queryNA(query).list
  }

  val patientsBasePart = """select * from "Patients""""

  def wherePart(whereParts: Option[String]*) =
    if (whereParts.exists(_.isDefined)) " where" else ""

  def patientsFilterPart(filter: Option[String]) =
    filter.map(filterValue => {
      val filterValueLike = s"'%$filterValue%'".toLowerCase
      s""" (lcase("PatientName") like $filterValueLike or 
           lcase("PatientID") like $filterValueLike or 
           lcase("PatientBirthDate") like $filterValueLike or 
           lcase("PatientSex") like $filterValueLike)"""
    })
      .getOrElse("")

  def orderByPart(orderBy: Option[String], orderAscending: Boolean) =
    orderBy.map(orderByValue =>
      s""" order by "$orderByValue" ${if (orderAscending) "asc" else "desc"}""")
      .getOrElse("")

  def pagePart(startIndex: Long, count: Long) = s""" limit $count offset $startIndex"""

  val queryPatientsSelectPart = """select distinct("Patients"."id"),
      "Patients"."PatientName",
      "Patients"."PatientID",
      "Patients"."PatientBirthDate",
      "Patients"."PatientSex" from "Patients"
      left join "Studies" on "Studies"."patientId" = "Patients"."id"
      left join "Series" on "Series"."studyId" = "Studies"."id""""

  def queryPatients(startIndex: Long, count: Long, orderBy: Option[String], orderAscending: Boolean, queryProperties: Seq[QueryProperty])(implicit session: Session): List[Patient] = {

    orderBy.foreach(checkColumnExists(_, "Patients"))
    queryProperties.foreach(qp => checkColumnExists(qp.propertyName, "Patients", "Studies", "Series"))

    implicit val getResult = patientsGetResult

    val query = queryPatientsSelectPart +
      wherePart(queryPart(queryProperties)) +
      orderByPart(orderBy, orderAscending) +
      pagePart(startIndex, count)

    Q.queryNA(query).list
  }

  val studiesGetResult = GetResult(r =>
    Study(r.nextLong, r.nextLong, StudyInstanceUID(r.nextString), StudyDescription(r.nextString), StudyDate(r.nextString), StudyID(r.nextString), AccessionNumber(r.nextString), PatientAge(r.nextString)))

  val queryStudiesSelectPart = """select distinct("Studies"."id"),
      "Studies"."patientId",
      "Studies"."StudyInstanceUID",
      "Studies"."StudyDescription",
      "Studies"."StudyDate",
      "Studies"."StudyID",
      "Studies"."AccessionNumber",
      "Studies"."PatientAge" from "Studies"
      left join "Patients" on "Patients"."id" = "Studies"."patientId"
      left join "Series" on "Series"."studyId" = "Studies"."id""""

  def queryStudies(startIndex: Long, count: Long, orderBy: Option[String], orderAscending: Boolean, queryProperties: Seq[QueryProperty])(implicit session: Session): List[Study] = {

    implicit val getResult = studiesGetResult

    orderBy.foreach(checkColumnExists(_, "Studies"))
    queryProperties.foreach(qp => checkColumnExists(qp.propertyName, "Patients", "Studies", "Series"))

    val query = queryStudiesSelectPart +
      wherePart(queryPart(queryProperties)) +
      orderByPart(orderBy, orderAscending) +
      pagePart(startIndex, count)

    Q.queryNA(query).list
  }

  val seriesGetResult = GetResult(r =>
    Series(r.nextLong, r.nextLong, SeriesInstanceUID(r.nextString), SeriesDescription(r.nextString), SeriesDate(r.nextString), Modality(r.nextString), ProtocolName(r.nextString), BodyPartExamined(r.nextString), Manufacturer(r.nextString), StationName(r.nextString), FrameOfReferenceUID(r.nextString)))

  val querySeriesSelectPart = """select distinct("Series"."id"),
      "Series"."studyId",
      "Series"."SeriesInstanceUID",
      "Series"."SeriesDescription",
      "Series"."SeriesDate",
      "Series"."Modality",
      "Series"."ProtocolName",
      "Series"."BodyPartExamined",
      "Series"."Manufacturer",
      "Series"."StationName",
      "Series"."FrameOfReferenceUID" from "Series"
      left join "Studies" on "Studies"."id" = "Series"."studyId"
      left join "Patients" on "Patients"."id" = "Studies"."patientId""""

  def querySeries(startIndex: Long, count: Long, orderBy: Option[String], orderAscending: Boolean, queryProperties: Seq[QueryProperty])(implicit session: Session): List[Series] = {

    orderBy.foreach(checkColumnExists(_, "Series"))
    queryProperties.foreach(qp => checkColumnExists(qp.propertyName, "Patients", "Studies", "Series"))

    implicit val getResult = seriesGetResult

    val query = querySeriesSelectPart +
      wherePart(queryPart(queryProperties)) +
      orderByPart(orderBy, orderAscending) +
      pagePart(startIndex, count)

    Q.queryNA(query).list
  }

  val imagesGetResult = GetResult(r =>
    Image(r.nextLong, r.nextLong, SOPInstanceUID(r.nextString), ImageType(r.nextString), InstanceNumber(r.nextString)))

  val queryImagesSelectPart = """select distinct("Images"."id"),
      "Images"."seriesId",
      "Images"."SOPInstanceUID",
      "Images"."ImageType",
      "Images"."InstanceNumber" from "Images"
      left join "Series" on "Series"."id" = "Images"."seriesId"
      left join "Studies" on "Studies"."id" = "Series"."studyId"
      left join "Patients" on "Patients"."id" = "Studies"."patientId""""

  def queryImages(startIndex: Long, count: Long, orderBy: Option[String], orderAscending: Boolean, queryProperties: Seq[QueryProperty])(implicit session: Session): List[Image] = {

    orderBy.foreach(checkColumnExists(_, "Images"))
    queryProperties.foreach(qp => checkColumnExists(qp.propertyName, "Patients", "Studies", "Series", "Images"))

    implicit val getResult = imagesGetResult

    val query = queryImagesSelectPart +
      wherePart(queryPart(queryProperties)) +
      orderByPart(orderBy, orderAscending) +
      pagePart(startIndex, count)

    Q.queryNA(query).list
  }

  def queryFlatSeries(startIndex: Long, count: Long, orderBy: Option[String], orderAscending: Boolean, queryProperties: Seq[QueryProperty])(implicit session: Session): List[FlatSeries] = {

    orderBy.foreach(checkColumnExists(_, "Patients", "Studies", "Series"))
    queryProperties.foreach(qp => checkColumnExists(qp.propertyName, "Patients", "Studies", "Series"))

    implicit val getResult = flatSeriesGetResult

    val query = flatSeriesBasePart +
      wherePart(queryPart(queryProperties)) +
      orderByPart(orderBy, orderAscending) +
      pagePart(startIndex, count)

    Q.queryNA(query).list
  }

  def queryPart(queryProperties: Seq[QueryProperty]): String =
    queryProperties.map(queryPropertyToPart).mkString(" and ")

  def queryPropertyToPart(queryProperty: QueryProperty) =
    s""""${queryProperty.propertyName}" ${queryProperty.operator.toString()} '${queryProperty.propertyValue}'"""

  def wherePart(part: String): String =
    if (part.length > 0) s" where $part" else ""

  val flatSeriesBasePart = """select distinct("Series"."id"), 
      "Patients"."id","Patients"."PatientName","Patients"."PatientID","Patients"."PatientBirthDate","Patients"."PatientSex", 
      "Studies"."id","Studies"."patientId","Studies"."StudyInstanceUID","Studies"."StudyDescription","Studies"."StudyDate","Studies"."StudyID","Studies"."AccessionNumber","Studies"."PatientAge",
      "Series"."id","Series"."studyId","Series"."SeriesInstanceUID","Series"."SeriesDescription","Series"."SeriesDate","Series"."Modality","Series"."ProtocolName","Series"."BodyPartExamined","Series"."Manufacturer","Series"."StationName","Series"."FrameOfReferenceUID"
       from "Series" 
       inner join "Studies" on "Series"."studyId" = "Studies"."id" 
       inner join "Patients" on "Studies"."patientId" = "Patients"."id""""

  val flatSeriesGetResult = GetResult(r =>
    FlatSeries(r.nextLong,
      Patient(r.nextLong, PatientName(r.nextString), PatientID(r.nextString), PatientBirthDate(r.nextString), PatientSex(r.nextString)),
      Study(r.nextLong, r.nextLong, StudyInstanceUID(r.nextString), StudyDescription(r.nextString), StudyDate(r.nextString), StudyID(r.nextString), AccessionNumber(r.nextString), PatientAge(r.nextString)),
      Series(r.nextLong, r.nextLong, SeriesInstanceUID(r.nextString), SeriesDescription(r.nextString), SeriesDate(r.nextString), Modality(r.nextString), ProtocolName(r.nextString), BodyPartExamined(r.nextString), Manufacturer(r.nextString), StationName(r.nextString), FrameOfReferenceUID(r.nextString))))

  def flatSeries(startIndex: Long, count: Long, orderBy: Option[String], orderAscending: Boolean, filter: Option[String])(implicit session: Session): List[FlatSeries] = {

    orderBy.foreach(checkColumnExists(_, "Patients", "Studies", "Series"))

    implicit val getResult = flatSeriesGetResult

    val query = flatSeriesBasePart +
      wherePart(filter) +
      flatSeriesFilterPart(filter) +
      orderByPart(orderBy, orderAscending) +
      pagePart(startIndex, count)

    Q.queryNA(query).list
  }

  def flatSeriesFilterPart(filter: Option[String]) =
    filter.map(filterValue => {
      val filterValueLike = s"'%$filterValue%'".toLowerCase
      s""" (lcase("Series"."id") like $filterValueLike or
           lcase("PatientName") like $filterValueLike or 
           lcase("PatientID") like $filterValueLike or 
           lcase("PatientBirthDate") like $filterValueLike or 
           lcase("PatientSex") like $filterValueLike or
             lcase("StudyDescription") like $filterValueLike or
             lcase("StudyDate") like $filterValueLike or
             lcase("StudyID") like $filterValueLike or
             lcase("AccessionNumber") like $filterValueLike or
             lcase("PatientAge") like $filterValueLike or
                 lcase("SeriesDescription") like $filterValueLike or
                 lcase("SeriesDate") like $filterValueLike or
                 lcase("Modality") like $filterValueLike or
                 lcase("ProtocolName") like $filterValueLike or
                 lcase("BodyPartExamined") like $filterValueLike or
                 lcase("Manufacturer") like $filterValueLike or
                 lcase("StationName") like $filterValueLike)"""
    })
      .getOrElse("")

  def flatSeriesById(seriesId: Long)(implicit session: Session): Option[FlatSeries] = {

    implicit val getResult = flatSeriesGetResult
    val query = flatSeriesBasePart + s""" where "Series"."id" = $seriesId"""

    Q.queryNA(query).firstOption
  }

  // *** Grouped listings ***

  def studiesForPatient(startIndex: Long, count: Long, patientId: Long)(implicit session: Session): List[Study] =
    studiesQuery
      .filter(_.patientId === patientId)
      .drop(startIndex)
      .take(count)
      .list

  def seriesForStudy(startIndex: Long, count: Long, studyId: Long)(implicit session: Session): List[Series] =
    seriesQuery
      .filter(_.studyId === studyId)
      .drop(startIndex)
      .take(count)
      .list

  def imagesForSeries(startIndex: Long, count: Long, seriesId: Long)(implicit session: Session): List[Image] =
    imagesQuery
      .filter(_.seriesId === seriesId)
      .drop(startIndex)
      .take(count)
      .list

  def patientByNameAndID(patient: Patient)(implicit session: Session): Option[Patient] =
    patientsQuery
      .filter(_.patientName === patient.patientName.value)
      .filter(_.patientID === patient.patientID.value)
      .firstOption

  def studyByUidAndPatient(study: Study, patient: Patient)(implicit session: Session): Option[Study] =
    studiesQuery
      .filter(_.studyInstanceUID === study.studyInstanceUID.value)
      .filter(_.patientId === patient.id)
      .firstOption

  def seriesByUidAndStudy(series: Series, study: Study)(implicit session: Session): Option[Series] =
    seriesQuery
      .filter(_.seriesInstanceUID === series.seriesInstanceUID.value)
      .filter(_.studyId === study.id)
      .firstOption

  def imageByUidAndSeries(image: Image, series: Series)(implicit session: Session): Option[Image] =
    imagesQuery
      .filter(_.sopInstanceUID === image.sopInstanceUID.value)
      .filter(_.seriesId === series.id)
      .firstOption

  // *** Deletes ***

  def deletePatient(patientId: Long)(implicit session: Session): Int = {
    patientsQuery
      .filter(_.id === patientId)
      .delete
  }

  def deleteStudy(studyId: Long)(implicit session: Session): Int = {
    studiesQuery
      .filter(_.id === studyId)
      .delete
  }

  def deleteSeries(seriesId: Long)(implicit session: Session): Int = {
    seriesQuery
      .filter(_.id === seriesId)
      .delete
  }

  def deleteImage(imageId: Long)(implicit session: Session): Int = {
    imagesQuery
      .filter(_.id === imageId)
      .delete
  }

  def deleteFully(image: Image)(implicit session: Session): Unit = {
    deleteImage(image.id)
    seriesById(image.seriesId).foreach(series =>
      if (imagesForSeries(0, 2, series.id).isEmpty)
        deleteFully(series))
  }

  def deleteFully(series: Series)(implicit session: Session): Unit = {
    deleteSeries(series.id)
    studyById(series.studyId).foreach(study =>
      if (seriesForStudy(0, 2, study.id).isEmpty)
        deleteFully(study))
  }

  def deleteFully(study: Study)(implicit session: Session): Unit = {
    deleteStudy(study.id)
    patientById(study.patientId).foreach(patient =>
      if (studiesForPatient(0, 2, patient.id).isEmpty)
        deletePatient(patient.id))
  }

}
