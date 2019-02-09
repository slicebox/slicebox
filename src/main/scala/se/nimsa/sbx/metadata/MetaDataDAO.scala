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

package se.nimsa.sbx.metadata

import se.nimsa.sbx.dicom.DicomHierarchy._
import se.nimsa.sbx.dicom.DicomProperty
import se.nimsa.sbx.dicom.DicomPropertyValue._
import se.nimsa.sbx.metadata.MetaDataProtocol.QueryOperator._
import se.nimsa.sbx.metadata.MetaDataProtocol._
import se.nimsa.sbx.util.DbUtil._
import slick.basic.{BasicAction, BasicStreamingAction, DatabaseConfig}
import slick.jdbc.{GetResult, JdbcProfile}

import scala.concurrent.{ExecutionContext, Future}

class MetaDataDAO(val dbConf: DatabaseConfig[JdbcProfile])(implicit ec: ExecutionContext) {

  import MetaDataDAO._
  import dbConf.profile.api._

  val db = dbConf.db

  // *** Patient ***

  val toPatient: (Long, String, String, String, String) => Patient =
    (id: Long, patientName: String, patientID: String, patientBirthDate: String, patientSex: String) =>
      Patient(id, PatientName(patientName), PatientID(patientID), PatientBirthDate(patientBirthDate), PatientSex(patientSex))

  val fromPatient: Patient => Option[(Long, String, String, String, String)] =
    (patient: Patient) =>
      Option((patient.id, patient.patientName.value, patient.patientID.value, patient.patientBirthDate.value, patient.patientSex.value))

  class PatientsTable(tag: Tag) extends Table[Patient](tag, PatientsTable.name) {
    def id = column[Long]("id", O.PrimaryKey, O.AutoInc)
    def patientName = column[String](DicomProperty.PatientName.name, O.Length(512))
    def patientID = column[String](DicomProperty.PatientID.name, O.Length(128))
    def patientBirthDate = column[String](DicomProperty.PatientBirthDate.name)
    def patientSex = column[String](DicomProperty.PatientSex.name)
    def idxUniquePatient = index("idx_unique_patient", (patientName, patientID), unique = true)
    def idxPatientName = index("idx_patient_name", patientName)
    def idxPatientID = index("idx_patient_id", patientID)
    def * = (id, patientName, patientID, patientBirthDate, patientSex) <> (toPatient.tupled, fromPatient)
  }

  object PatientsTable {
    val name = "Patients"
  }

  val patientsQuery = TableQuery[PatientsTable]

  // *** Study *** //

  val fromStudy: Study => Option[(Long, Long, String, String, String, String, String, String)] = (study: Study) => Option((study.id, study.patientId, study.studyInstanceUID.value, study.studyDescription.value, study.studyDate.value, study.studyID.value, study.accessionNumber.value, study.patientAge.value))

  val toStudy: (Long, Long, String, String, String, String, String, String) => Study =
    (id: Long, patientId: Long, studyInstanceUID: String, studyDescription: String, studyDate: String, studyID: String, accessionNumber: String, patientAge: String) =>
      Study(id, patientId, StudyInstanceUID(studyInstanceUID), StudyDescription(studyDescription), StudyDate(studyDate), StudyID(studyID), AccessionNumber(accessionNumber), PatientAge(patientAge))

  class StudiesTable(tag: Tag) extends Table[Study](tag, StudiesTable.name) {
    def id = column[Long]("id", O.PrimaryKey, O.AutoInc)
    def patientId = column[Long]("patientId")
    def studyInstanceUID = column[String](DicomProperty.StudyInstanceUID.name, O.Length(128))
    def studyDescription = column[String](DicomProperty.StudyDescription.name)
    def studyDate = column[String](DicomProperty.StudyDate.name)
    def studyID = column[String](DicomProperty.StudyID.name)
    def accessionNumber = column[String](DicomProperty.AccessionNumber.name)
    def patientAge = column[String](DicomProperty.PatientAge.name)
    def idxUniqueStudy = index("idx_unique_study", (patientId, studyInstanceUID), unique = true)
    def * = (id, patientId, studyInstanceUID, studyDescription, studyDate, studyID, accessionNumber, patientAge) <> (toStudy.tupled, fromStudy)

    def patientFKey = foreignKey("patientFKey", patientId, patientsQuery)(_.id, onUpdate = ForeignKeyAction.Cascade, onDelete = ForeignKeyAction.Cascade)
  }

  object StudiesTable {
    val name = "Studies"
  }

  val studiesQuery = TableQuery[StudiesTable]

  // *** Series ***

  val toSeries: (Long, Long, String, String, String, String, String, String, String, String, String) => Series =
    (id: Long, studyId: Long, seriesInstanceUID: String, seriesDescription: String, seriesDate: String, modality: String, protocolName: String, bodyPartExamined: String, manufacturer: String, stationName: String, frameOfReferenceUID: String) =>
      Series(id, studyId, SeriesInstanceUID(seriesInstanceUID), SeriesDescription(seriesDescription), SeriesDate(seriesDate), Modality(modality), ProtocolName(protocolName), BodyPartExamined(bodyPartExamined), Manufacturer(manufacturer), StationName(stationName), FrameOfReferenceUID(frameOfReferenceUID))

  val fromSeries: Series => Option[(Long, Long, String, String, String, String, String, String, String, String, String)] =
    (series: Series) =>
      Option((series.id, series.studyId, series.seriesInstanceUID.value, series.seriesDescription.value, series.seriesDate.value, series.modality.value, series.protocolName.value, series.bodyPartExamined.value, series.manufacturer.value, series.stationName.value, series.frameOfReferenceUID.value))

  class SeriesTable(tag: Tag) extends Table[Series](tag, SeriesTable.name) {
    def id = column[Long]("id", O.PrimaryKey, O.AutoInc)
    def studyId = column[Long]("studyId")
    def seriesInstanceUID = column[String](DicomProperty.SeriesInstanceUID.name, O.Length(128))
    def seriesDescription = column[String](DicomProperty.SeriesDescription.name)
    def seriesDate = column[String](DicomProperty.SeriesDate.name)
    def modality = column[String](DicomProperty.Modality.name)
    def protocolName = column[String](DicomProperty.ProtocolName.name)
    def bodyPartExamined = column[String](DicomProperty.BodyPartExamined.name)
    def manufacturer = column[String](DicomProperty.Manufacturer.name)
    def stationName = column[String](DicomProperty.StationName.name)
    def frameOfReferenceUID = column[String](DicomProperty.FrameOfReferenceUID.name)
    def idxUniqueStudy = index("idx_unique_series", (studyId, seriesInstanceUID), unique = true)
    def * = (id, studyId, seriesInstanceUID, seriesDescription, seriesDate, modality, protocolName, bodyPartExamined, manufacturer, stationName, frameOfReferenceUID) <> (toSeries.tupled, fromSeries)

    def studyFKey = foreignKey("studyFKey", studyId, studiesQuery)(_.id, onUpdate = ForeignKeyAction.Cascade, onDelete = ForeignKeyAction.Cascade)
  }

  object SeriesTable {
    val name = "Series"
  }

  val seriesQuery = TableQuery[SeriesTable]

  // *** Image ***

  val toImage: (Long, Long, String, String, String) => Image =
    (id: Long, seriesId: Long, sopInstanceUID: String, imageType: String, instanceNumber: String) =>
      Image(id, seriesId, SOPInstanceUID(sopInstanceUID), ImageType(imageType), InstanceNumber(instanceNumber))

  val fromImage: Image => Option[(Long, Long, String, String, String)] =
    (image: Image) =>
      Option((image.id, image.seriesId, image.sopInstanceUID.value, image.imageType.value, image.instanceNumber.value))

  class ImagesTable(tag: Tag) extends Table[Image](tag, ImagesTable.name) {
    def id = column[Long]("id", O.PrimaryKey, O.AutoInc)
    def seriesId = column[Long]("seriesId")
    def sopInstanceUID = column[String](DicomProperty.SOPInstanceUID.name, O.Length(128))
    def imageType = column[String](DicomProperty.ImageType.name)
    def instanceNumber = column[String](DicomProperty.InstanceNumber.name)
    def idxUniqueImage = index("idx_unique_image", (seriesId, sopInstanceUID), unique = true)
    def idxSopInstanceUid = index("idx_sop_instance_uid", sopInstanceUID)
    def * = (id, seriesId, sopInstanceUID, imageType, instanceNumber) <> (toImage.tupled, fromImage)

    def seriesFKey = foreignKey("seriesFKey", seriesId, seriesQuery)(_.id, onUpdate = ForeignKeyAction.Cascade, onDelete = ForeignKeyAction.Cascade)
  }

  object ImagesTable {
    val name = "Images"
  }

  val imagesQuery = TableQuery[ImagesTable]

  def create(): Future[Unit] = createTables(dbConf, (PatientsTable.name, patientsQuery), (StudiesTable.name, studiesQuery), (SeriesTable.name, seriesQuery), (ImagesTable.name, imagesQuery))

  def drop(): Future[Unit] = db.run {
    (patientsQuery.schema ++ studiesQuery.schema ++ seriesQuery.schema ++ imagesQuery.schema).drop
  }

  def clear(): Future[Unit] = db.run {
    DBIO.seq(patientsQuery.delete, studiesQuery.delete, seriesQuery.delete, imagesQuery.delete)
  }

  // *** Complete listings

  def patients: Future[Seq[Patient]] = db.run {
    patientsQuery.result
  }

  def studies: Future[Seq[Study]] = db.run {
    studiesQuery.result
  }

  def series: Future[Seq[Series]] = db.run {
    seriesQuery.result
  }

  def images: Future[Seq[Image]] = db.run {
    imagesQuery.result
  }

  // *** Get entities by id

  def patientById(id: Long): Future[Option[Patient]] = db.run(patientsQuery.filter(_.id === id).result.headOption)

  def studyById(id: Long): Future[Option[Study]] = db.run(studiesQuery.filter(_.id === id).result.headOption)

  def seriesById(id: Long): Future[Option[Series]] = db.run(seriesQuery.filter(_.id === id).result.headOption)

  def imageById(id: Long): Future[Option[Image]] = db.run(imagesQuery.filter(_.id === id).result.headOption)

  // *** Inserts ***

  def insertPatientAction(patient: Patient): DBIOAction[Patient, NoStream, Effect.Write] =
    (patientsQuery returning patientsQuery.map(_.id) += patient
      .copy(patientName = patient.patientName.copy(value = patient.patientName.value.take(180))))
      .map(generatedId => patient.copy(id = generatedId))

  def insert(patient: Patient): Future[Patient] = db.run(insertPatientAction(patient))

  def insertStudyAction(study: Study): DBIOAction[Study, NoStream, Effect.Write] =
    (studiesQuery returning studiesQuery.map(_.id) += study)
      .map(generatedId => study.copy(id = generatedId))

  def insert(study: Study): Future[Study] = db.run(insertStudyAction(study))

  def insertSeriesAction(series: Series): DBIOAction[Series, NoStream, Effect.Write] =
    (seriesQuery returning seriesQuery.map(_.id) += series)
      .map(generatedId => series.copy(id = generatedId))

  def insert(series: Series): Future[Series] = db.run(insertSeriesAction(series))

  def insertImageAction(image: Image): DBIOAction[Image, NoStream, Effect.Write] =
    (imagesQuery returning imagesQuery.map(_.id) += image)
      .map(generatedId => image.copy(id = generatedId))

  def insert(image: Image): Future[Image] = db.run(insertImageAction(image))

  // *** Listing all patients, studies etc ***

  val patientsGetResult = GetResult(r =>
    Patient(r.nextLong, PatientName(r.nextString), PatientID(r.nextString), PatientBirthDate(r.nextString), PatientSex(r.nextString)))

  def patients(startIndex: Long, count: Long, orderBy: Option[String], orderAscending: Boolean, filter: Option[String]): Future[Seq[Patient]] =

    checkColumnExists(dbConf, orderBy, PatientsTable.name).flatMap { _ =>
      db.run {
        implicit val getResult: GetResult[Patient] = patientsGetResult

        val query =
          patientsBasePart +
            wherePart(filter) +
            patientsFilterPart(filter) +
            orderByPart(orderBy.map(o => s""""Patients"."$o""""), orderAscending) +
            pagePart(startIndex, count)

        sql"#$query".as[Patient]
      }
    }

  val patientsBasePart = """select * from "Patients""""

  def patientsFilterPart(filter: Option[String]): String =
    filter.map(filterValue => {
      val filterValueLike = s"'%$filterValue%'".toLowerCase
      s""" (lcase("Patients"."patientName") like $filterValueLike or
           lcase("Patients"."patientID") like $filterValueLike or
           lcase("Patients"."patientBirthDate") like $filterValueLike or
           lcase("Patients"."patientSex") like $filterValueLike)"""
    })
      .getOrElse("")

  val queryPatientsSelectPart =
    """select distinct("Patients"."id"),
      "Patients"."patientName",
      "Patients"."patientID",
      "Patients"."patientBirthDate",
      "Patients"."patientSex" from "Patients"
      left join "Studies" on "Studies"."patientId" = "Patients"."id"
      left join "Series" on "Series"."studyId" = "Studies"."id""""

  def queryPatients(startIndex: Long, count: Long, orderBy: Option[String], orderAscending: Boolean, queryProperties: Seq[QueryProperty]): Future[Seq[Patient]] =

    checkColumnExists(dbConf, orderBy, PatientsTable.name, StudiesTable.name, SeriesTable.name).flatMap { _ =>
      Future.sequence(queryProperties.map(qp => checkColumnExists(dbConf, qp.propertyName, PatientsTable.name, StudiesTable.name, SeriesTable.name))).flatMap { _ =>
        db.run {
          implicit val getResult: GetResult[Patient] = patientsGetResult

          val query = queryPatientsSelectPart +
            wherePart(queryPart(queryProperties)) +
            orderByPart(orderBy.map(o => s""""Patients"."$o""""), orderAscending) +
            pagePart(startIndex, count)

          sql"#$query".as[Patient]
        }
      }
    }

  val studiesGetResult = GetResult(r =>
    Study(r.nextLong, r.nextLong, StudyInstanceUID(r.nextString), StudyDescription(r.nextString), StudyDate(r.nextString), StudyID(r.nextString), AccessionNumber(r.nextString), PatientAge(r.nextString)))

  val queryStudiesSelectPart =
    """select distinct("Studies"."id"),
      "Studies"."patientId",
      "Studies"."studyInstanceUID",
      "Studies"."studyDescription",
      "Studies"."studyDate",
      "Studies"."studyID",
      "Studies"."accessionNumber",
      "Studies"."patientAge" from "Studies"
      left join "Patients" on "Patients"."id" = "Studies"."patientId"
      left join "Series" on "Series"."studyId" = "Studies"."id""""

  def queryStudies(startIndex: Long, count: Long, orderBy: Option[String], orderAscending: Boolean, queryProperties: Seq[QueryProperty]): Future[Seq[Study]] =

    checkColumnExists(dbConf, orderBy, PatientsTable.name, StudiesTable.name, SeriesTable.name).flatMap { _ =>
      Future.sequence(queryProperties.map(qp => checkColumnExists(dbConf, qp.propertyName, PatientsTable.name, StudiesTable.name, SeriesTable.name))).flatMap { _ =>
        db.run {
          implicit val getResult: GetResult[Study] = studiesGetResult

          val query = queryStudiesSelectPart +
            wherePart(queryPart(queryProperties)) +
            orderByPart(orderBy.map(o => s""""Studies"."$o""""), orderAscending) +
            pagePart(startIndex, count)

          sql"#$query".as[Study]
        }
      }
    }

  val seriesGetResult = GetResult(r =>
    Series(r.nextLong, r.nextLong, SeriesInstanceUID(r.nextString), SeriesDescription(r.nextString), SeriesDate(r.nextString), Modality(r.nextString), ProtocolName(r.nextString), BodyPartExamined(r.nextString), Manufacturer(r.nextString), StationName(r.nextString), FrameOfReferenceUID(r.nextString)))

  val querySeriesSelectPart =
    """select distinct("Series"."id"),
      "Series"."studyId",
      "Series"."seriesInstanceUID",
      "Series"."seriesDescription",
      "Series"."seriesDate",
      "Series"."modality",
      "Series"."protocolName",
      "Series"."bodyPartExamined",
      "Series"."manufacturer",
      "Series"."stationName",
      "Series"."frameOfReferenceUID" from "Series"
      left join "Studies" on "Studies"."id" = "Series"."studyId"
      left join "Patients" on "Patients"."id" = "Studies"."patientId""""

  def querySeries(startIndex: Long, count: Long, orderBy: Option[String], orderAscending: Boolean, queryProperties: Seq[QueryProperty]): Future[Seq[Series]] =

    checkColumnExists(dbConf, orderBy, PatientsTable.name, StudiesTable.name, SeriesTable.name).flatMap { _ =>
      Future.sequence(queryProperties.map(qp => checkColumnExists(dbConf, qp.propertyName, PatientsTable.name, StudiesTable.name, SeriesTable.name))).flatMap { _ =>
        db.run {
          implicit val getResult: GetResult[Series] = seriesGetResult

          val query = querySeriesSelectPart +
            wherePart(queryPart(queryProperties)) +
            orderByPart(orderBy.map(o => s""""Series"."$o""""), orderAscending) +
            pagePart(startIndex, count)

          sql"#$query".as[Series]
        }
      }
    }

  val imagesGetResult = GetResult(r =>
    Image(r.nextLong, r.nextLong, SOPInstanceUID(r.nextString), ImageType(r.nextString), InstanceNumber(r.nextString)))

  val queryImagesSelectPart =
    """select distinct("Images"."id"),
      "Images"."seriesId",
      "Images"."sopInstanceUID",
      "Images"."imageType",
      "Images"."instanceNumber" from "Images"
      left join "Series" on "Series"."id" = "Images"."seriesId"
      left join "Studies" on "Studies"."id" = "Series"."studyId"
      left join "Patients" on "Patients"."id" = "Studies"."patientId""""

  def queryImages(startIndex: Long, count: Long, orderBy: Option[String], orderAscending: Boolean, queryProperties: Seq[QueryProperty]): Future[Seq[Image]] =

    checkColumnExists(dbConf, orderBy, PatientsTable.name, StudiesTable.name, SeriesTable.name, ImagesTable.name).flatMap { _ =>
      Future.sequence(queryProperties.map(qp => checkColumnExists(dbConf, qp.propertyName, PatientsTable.name, StudiesTable.name, SeriesTable.name, ImagesTable.name))).flatMap { _ =>
        db.run {
          implicit val getResult: GetResult[Image] = imagesGetResult

          val query = queryImagesSelectPart +
            wherePart(queryPart(queryProperties)) +
            orderByPart(orderBy.map(o => s""""Images"."$o""""), orderAscending) +
            pagePart(startIndex, count)

          sql"#$query".as[Image]
        }
      }
    }

  def queryFlatSeries(startIndex: Long, count: Long, orderBy: Option[String], orderAscending: Boolean, queryProperties: Seq[QueryProperty]): Future[Seq[FlatSeries]] =
    checkColumnExists(dbConf, orderBy, PatientsTable.name, StudiesTable.name, SeriesTable.name).flatMap { _ =>
      Future.sequence(queryProperties.map(qp => checkColumnExists(dbConf, qp.propertyName, PatientsTable.name, StudiesTable.name, SeriesTable.name))).flatMap { _ =>
        db.run {
          implicit val getResult: GetResult[FlatSeries] = flatSeriesGetResult

          val query = flatSeriesBasePart +
            wherePart(queryPart(queryProperties)) +
            orderByPart(flatSeriesOrderBy(orderBy), orderAscending) +
            pagePart(startIndex, count)

          sql"#$query".as[FlatSeries]
        }
      }
    }

  def flatSeriesOrderBy(orderBy: Option[String]): Option[String] =
    orderBy.map {
      case "id" => """"Series"."id""""
      case "patientID" => """"Patients"."patientID""""
      case "studyID" => """"Studies"."studyID""""
      case s => s""""$s""""
    }

  val flatSeriesBasePart =
    """select distinct("Series"."id"),
      "Patients"."id","Patients"."patientName","Patients"."patientID","Patients"."patientBirthDate","Patients"."patientSex",
      "Studies"."id","Studies"."patientId","Studies"."studyInstanceUID","Studies"."studyDescription","Studies"."studyDate","Studies"."studyID","Studies"."accessionNumber","Studies"."patientAge",
      "Series"."id","Series"."studyId","Series"."seriesInstanceUID","Series"."seriesDescription","Series"."seriesDate","Series"."modality","Series"."protocolName","Series"."bodyPartExamined","Series"."manufacturer","Series"."stationName","Series"."frameOfReferenceUID"
       from "Series"
       inner join "Studies" on "Series"."studyId" = "Studies"."id"
       inner join "Patients" on "Studies"."patientId" = "Patients"."id""""

  val flatSeriesGetResult = GetResult(r =>
    FlatSeries(r.nextLong,
      Patient(r.nextLong, PatientName(r.nextString), PatientID(r.nextString), PatientBirthDate(r.nextString), PatientSex(r.nextString)),
      Study(r.nextLong, r.nextLong, StudyInstanceUID(r.nextString), StudyDescription(r.nextString), StudyDate(r.nextString), StudyID(r.nextString), AccessionNumber(r.nextString), PatientAge(r.nextString)),
      Series(r.nextLong, r.nextLong, SeriesInstanceUID(r.nextString), SeriesDescription(r.nextString), SeriesDate(r.nextString), Modality(r.nextString), ProtocolName(r.nextString), BodyPartExamined(r.nextString), Manufacturer(r.nextString), StationName(r.nextString), FrameOfReferenceUID(r.nextString))))

  def flatSeries(startIndex: Long, count: Long, orderBy: Option[String], orderAscending: Boolean, filter: Option[String]): Future[Seq[FlatSeries]] =

    checkColumnExists(dbConf, orderBy, PatientsTable.name, StudiesTable.name, SeriesTable.name).flatMap { _ =>
      db.run {
        implicit val getResult: GetResult[FlatSeries] = flatSeriesGetResult

        val query = flatSeriesBasePart +
          wherePart(filter) +
          flatSeriesFilterPart(filter) +
          orderByPart(flatSeriesOrderBy(orderBy), orderAscending) +
          pagePart(startIndex, count)

        sql"#$query".as[FlatSeries]
      }
    }

  def flatSeriesFilterPart(filter: Option[String]): String =
    filter.map(filterValue => {
      val filterValueLike = s"'%$filterValue%'".toLowerCase
      s""" (lcase("Series"."id") like $filterValueLike or
           lcase("Patients"."patientName") like $filterValueLike or
           lcase("Patients"."patientID") like $filterValueLike or
           lcase("Patients"."patientBirthDate") like $filterValueLike or
           lcase("Patients"."patientSex") like $filterValueLike or
             lcase("Studies"."studyDescription") like $filterValueLike or
             lcase("Studies"."studyDate") like $filterValueLike or
             lcase("Studies"."studyID") like $filterValueLike or
             lcase("Studies"."accessionNumber") like $filterValueLike or
             lcase("Studies"."patientAge") like $filterValueLike or
                 lcase("Series"."seriesDescription") like $filterValueLike or
                 lcase("Series"."seriesDate") like $filterValueLike or
                 lcase("Series"."modality") like $filterValueLike or
                 lcase("Series"."protocolName") like $filterValueLike or
                 lcase("Series"."bodyPartExamined") like $filterValueLike or
                 lcase("Series"."manufacturer") like $filterValueLike or
                 lcase("Series"."stationName") like $filterValueLike)"""
    })
      .getOrElse("")

  def flatSeriesById(seriesId: Long): Future[Option[FlatSeries]] = db.run {
    implicit val getResult: GetResult[FlatSeries] = flatSeriesGetResult
    val query = flatSeriesBasePart + s""" where "Series"."id" = $seriesId"""
    sql"#$query".as[FlatSeries].headOption
  }

  // *** Grouped listings ***

  def studiesForPatient(startIndex: Long, count: Long, patientId: Long): Future[Seq[Study]] = db.run {
    studiesQuery
      .filter(_.patientId === patientId)
      .drop(startIndex)
      .take(count)
      .result
  }

  def seriesForStudy(startIndex: Long, count: Long, studyId: Long): Future[Seq[Series]] = db.run {
    seriesQuery
      .filter(_.studyId === studyId)
      .drop(startIndex)
      .take(count)
      .result
  }

  val imagesBasePart = """select * from "Images""""

  def imagesFilterPart(filter: Option[String]): String =
    filter.map(filterValue => {
      val filterValueLike = s"'%$filterValue%'".toLowerCase
      s""" (lcase("Images"."sopInstanceUID") like $filterValueLike or
           lcase("Images"."imageType") like $filterValueLike or
           lcase("Images"."instanceNumber") like $filterValueLike)"""
    })
      .getOrElse("")

  def imagesForSeries(startIndex: Long, count: Long, seriesId: Long, orderBy: Option[String], orderAscending: Boolean, filter: Option[String]): Future[Seq[Image]] =
    checkColumnExists(dbConf, orderBy, ImagesTable.name).flatMap { _ =>
      db.run {
        implicit val getResult: GetResult[Image] = imagesGetResult

        val imagesForSeriesPart = s""" "seriesId" = $seriesId"""

        val query =
          imagesBasePart +
            " where" +
            imagesForSeriesPart +
            filter.map(_ => " and").getOrElse("") +
            imagesFilterPart(filter) +
            orderByPart(orderBy.map(o => s""""Images"."$o""""), orderAscending) +
            pagePart(startIndex, count)

        sql"#$query".as[Image]
      }
    }

  def patientsByNameAndIDAction(patient: Patient): BasicStreamingAction[Seq[Patient], Patient, Effect.Read] =
    patientsQuery
      .filter(_.patientName === patient.patientName.value.take(180))
      .filter(_.patientID === patient.patientID.value)
      .result

  def patientByNameAndID(patient: Patient): Future[Option[Patient]] = db.run(patientsByNameAndIDAction(patient).headOption)

  def studiesByUidAndPatientAction(study: Study, patient: Patient): BasicStreamingAction[Seq[Study], Study, Effect.Read] =
    studiesQuery
      .filter(_.studyInstanceUID === study.studyInstanceUID.value)
      .filter(_.patientId === patient.id)
      .result

  def studyByUidAndPatient(study: Study, patient: Patient): Future[Option[Study]] =
    db.run(studiesByUidAndPatientAction(study, patient).headOption)

  def seriesByUidAndStudyAction(series: Series, study: Study): BasicStreamingAction[Seq[Series], Series, Effect.Read] =
    seriesQuery
      .filter(_.seriesInstanceUID === series.seriesInstanceUID.value)
      .filter(_.studyId === study.id)
      .result

  def seriesByUidAndStudy(series: Series, study: Study): Future[Option[Series]] =
    db.run(seriesByUidAndStudyAction(series, study).headOption)

  def imagesByUidAndSeriesAction(image: Image, series: Series): BasicStreamingAction[Seq[Image], Image, Effect.Read] =
    imagesQuery
      .filter(_.sopInstanceUID === image.sopInstanceUID.value)
      .filter(_.seriesId === series.id)
      .result

  def imageByUidAndSeries(image: Image, series: Series): Future[Option[Image]] =
    db.run(imagesByUidAndSeriesAction(image, series).headOption)

  // *** Updates ***

  def updatePatientAction(patient: Patient): BasicAction[Int, NoStream, Effect.Write] =
    patientsQuery.filter(_.id === patient.id).update(patient)

  def updatePatient(patient: Patient): Future[Int] = db.run(updatePatientAction(patient))

  def updateStudyAction(study: Study): BasicAction[Int, NoStream, Effect.Write] =
    studiesQuery.filter(_.id === study.id).update(study)

  def updateStudy(study: Study): Future[Int] = db.run(updateStudyAction(study))

  def updateSeriesAction(series: Series): BasicAction[Int, NoStream, Effect.Write] =
    seriesQuery.filter(_.id === series.id).update(series)

  def updateSeries(series: Series): Future[Int] = db.run(updateSeriesAction(series))

  def updateImageAction(image: Image): BasicAction[Int, NoStream, Effect.Write] =
    imagesQuery.filter(_.id === image.id).update(image)

  def updateImage(image: Image): Future[Int] = db.run(updateImageAction(image))

}

object MetaDataDAO {

  def wherePart(whereParts: Option[String]*): String =
    if (whereParts.exists(_.isDefined)) " where" else ""

  def orderByPart(orderBy: Option[String], orderAscending: Boolean): String =
    orderBy
      .map(orderByValue => s""" order by $orderByValue ${if (orderAscending) "asc" else "desc"}""")
      .getOrElse("")

  def pagePart(startIndex: Long, count: Long) = s""" limit $count offset $startIndex"""

  def wherePart(part: String): String =
    if (part.length > 0) s" where $part" else ""

  def queryPart(queryProperties: Seq[QueryProperty]): String =
    queryProperties.map(queryPropertyToPart).mkString(" and ")

  def queryPropertyToPart(queryProperty: QueryProperty): String = {
    val valuePart =
      if (queryProperty.operator == EQUALS)
        s"'${queryProperty.propertyValue}'"
      else
        s"'%${queryProperty.propertyValue}%'"
    s""""${queryProperty.propertyName}" ${queryProperty.operator.toString()} $valuePart"""
  }
}
