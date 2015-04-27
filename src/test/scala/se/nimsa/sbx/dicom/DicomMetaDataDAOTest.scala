package se.nimsa.sbx.dicom

import scala.slick.driver.H2Driver
import scala.slick.jdbc.JdbcBackend.Database
import org.scalatest.FlatSpec
import org.scalatest.Matchers
import DicomPropertyValue._
import DicomHierarchy._
import DicomProtocol._
import org.h2.jdbc.JdbcSQLException

class DicomMetaDataDAOTest extends FlatSpec with Matchers {

  private val db = Database.forURL("jdbc:h2:mem:dicommetadatadaotest;DB_CLOSE_DELAY=-1", driver = "org.h2.Driver")

  val dao = new DicomMetaDataDAO(H2Driver)

  db.withSession { implicit session =>
    dao.create
  }

  val pat1 = Patient(-1, PatientName("p1"), PatientID("s1"), PatientBirthDate("2000-01-01"), PatientSex("M"))
  val study1 = Study(-1, -1, StudyInstanceUID("stuid1"), StudyDescription("stdesc1"), StudyDate("19990101"), StudyID("stid1"), AccessionNumber("acc1"), PatientAge("12Y"))
  val study2 = Study(-1, -1, StudyInstanceUID("stuid2"), StudyDescription("stdesc2"), StudyDate("19990102"), StudyID("stid2"), AccessionNumber("acc2"), PatientAge("14Y"))
  val equipment1 = Equipment(-1, Manufacturer("manu1"), StationName("station1"))
  val equipment2 = Equipment(-1, Manufacturer("manu2"), StationName("station2"))
  val equipment3 = Equipment(-1, Manufacturer("manu3"), StationName("station3"))
  val for1 = FrameOfReference(-1, FrameOfReferenceUID("frid1"))
  val for2 = FrameOfReference(-1, FrameOfReferenceUID("frid2"))
  val series1 = Series(-1, -1, -1, -1, SeriesInstanceUID("seuid1"), SeriesDescription("sedesc1"), SeriesDate("19990101"), Modality("NM"), ProtocolName("prot1"), BodyPartExamined("bodypart1"))
  val series2 = Series(-1, -1, -1, -1, SeriesInstanceUID("seuid2"), SeriesDescription("sedesc2"), SeriesDate("19990102"), Modality("NM"), ProtocolName("prot2"), BodyPartExamined("bodypart2"))
  val series3 = Series(-1, -1, -1, -1, SeriesInstanceUID("seuid3"), SeriesDescription("sedesc3"), SeriesDate("19990103"), Modality("NM"), ProtocolName("prot3"), BodyPartExamined("bodypart1"))
  val series4 = Series(-1, -1, -1, -1, SeriesInstanceUID("seuid4"), SeriesDescription("sedesc4"), SeriesDate("19990104"), Modality("NM"), ProtocolName("prot4"), BodyPartExamined("bodypart2"))
  val image1 = Image(-1, -1, SOPInstanceUID("souid1"), ImageType("PRIMARY/RECON/TOMO"), InstanceNumber("1"))
  val image2 = Image(-1, -1, SOPInstanceUID("souid2"), ImageType("PRIMARY/RECON/TOMO"), InstanceNumber("1"))
  val image3 = Image(-1, -1, SOPInstanceUID("souid3"), ImageType("PRIMARY/RECON/TOMO"), InstanceNumber("1"))
  val image4 = Image(-1, -1, SOPInstanceUID("souid4"), ImageType("PRIMARY/RECON/TOMO"), InstanceNumber("1"))
  val image5 = Image(-1, -1, SOPInstanceUID("souid5"), ImageType("PRIMARY/RECON/TOMO"), InstanceNumber("1"))
  val image6 = Image(-1, -1, SOPInstanceUID("souid6"), ImageType("PRIMARY/RECON/TOMO"), InstanceNumber("1"))
  val image7 = Image(-1, -1, SOPInstanceUID("souid7"), ImageType("PRIMARY/RECON/TOMO"), InstanceNumber("1"))
  val image8 = Image(-1, -1, SOPInstanceUID("souid8"), ImageType("PRIMARY/RECON/TOMO"), InstanceNumber("1"))
  val imageFile1 = ImageFile(-1, FileName("file1"))
  val imageFile2 = ImageFile(-1, FileName("file2"))
  val imageFile3 = ImageFile(-1, FileName("file3"))
  val imageFile4 = ImageFile(-1, FileName("file4"))
  val imageFile5 = ImageFile(-1, FileName("file5"))
  val imageFile6 = ImageFile(-1, FileName("file6"))
  val imageFile7 = ImageFile(-1, FileName("file7"))
  val imageFile8 = ImageFile(-1, FileName("file8"))

  val pat1_copy = Patient(-1, PatientName("p1"), PatientID("s1"), PatientBirthDate("1000-01-01"), PatientSex("F"))
  val study3 = Study(-1, -1, StudyInstanceUID("stuid3"), StudyDescription("stdesc3"), StudyDate("19990103"), StudyID("stid3"), AccessionNumber("acc3"), PatientAge("13Y"))
  // the following series has a copied SeriesInstanceUID but unique parent study so should no be treated as copy
  val series1_copy = Series(-1, -1, -1, -1, SeriesInstanceUID("souid1"), SeriesDescription("sedesc1"), SeriesDate("19990101"), Modality("NM"), ProtocolName("prot1"), BodyPartExamined("bodypart1"))
  val image9 = Image(-1, -1, SOPInstanceUID("souid9"), ImageType("PRIMARY/RECON/TOMO"), InstanceNumber("1"))
  val imageFile9 = ImageFile(-1, FileName("file9"))

  "The meta data db" should "be emtpy before anything has been added" in {
    db.withSession { implicit session =>
      dao.imageFiles.size should be(0)
    }
  }

  it should "contain one entry in each table after inserting one patient, study, series, equipent, frame of referenc, image and image file" in {
    db.withSession { implicit session =>
      val dbPat = dao.insert(pat1)
      val dbStudy = dao.insert(study1.copy(patientId = dbPat.id))
      val dbEquipment = dao.insert(equipment1)
      val dbFor = dao.insert(for1)
      val dbSeries = dao.insert(series1.copy(studyId = dbStudy.id, equipmentId = dbEquipment.id, frameOfReferenceId = dbFor.id))
      val dbImage = dao.insert(image1.copy(seriesId = dbSeries.id))
      dao.insert(imageFile1.copy(id = dbImage.id))

      dao.imageFiles.size should be(1)
      dao.images.size should be(1)
      dao.series.size should be(1)
      dao.equipments.size should be(1)
      dao.frameOfReferences.size should be(1)
      dao.studies.size should be(1)
      dao.patients(0, 20, None, false, None).size should be(1)

      dbPat.id should be >= (0L)
      dbStudy.id should be >= (0L)
      dbEquipment.id should be >= (0L)
      dbFor.id should be >= (0L)
      dbSeries.id should be >= (0L)
      dbImage.id should be >= (0L)
    }
  }

  it should "cascade delete linked studies, series, images and image files when a patient is deleted" in {
    db.withSession { implicit session =>
      dao.patientByNameAndID(pat1).foreach(dbPat => {
        dao.deletePatient(dbPat.id)
        dao.imageFiles.size should be(0)
        dao.images.size should be(0)
        dao.series.size should be(0)
        dao.studies.size should be(0)
        dao.patients(0, 20, None, false, None).size should be(0)
        dao.equipments.size should be(1)
        dao.frameOfReferences.size should be(1)
        dao.frameOfReferenceByUid(for1).foreach(dbFor => {
          dao.deleteFrameOfReference(dbFor.id)
          dao.frameOfReferences.size should be(0)
        })
        dao.equipmentByManufacturerAndStationName(equipment1).foreach(dbEquipment => {
          dao.deleteEquipment(dbEquipment.id)
          dao.equipments.size should be(0)
        })
      })
    }
  }

  it should "not support adding a study which links to a non-existant patient" in {
    db.withSession { implicit session =>
      intercept[JdbcSQLException] {
        dao.insert(study1)
      }
    }
  }

  it should "return the correct number of entities by each category" in {
    db.withSession { implicit session =>
      val dbPat = dao.insert(pat1)
      val dbStudy1 = dao.insert(study1.copy(patientId = dbPat.id))
      val dbStudy2 = dao.insert(study2.copy(patientId = dbPat.id))
      val dbEquipment1 = dao.insert(equipment1)
      val dbEquipment2 = dao.insert(equipment2)
      val dbEquipment3 = dao.insert(equipment3)
      val dbFor1 = dao.insert(for1)
      val dbFor2 = dao.insert(for2)
      val dbSeries1 = dao.insert(series1.copy(studyId = dbStudy1.id, equipmentId = dbEquipment1.id, frameOfReferenceId = dbFor1.id))
      val dbSeries2 = dao.insert(series2.copy(studyId = dbStudy2.id, equipmentId = dbEquipment2.id, frameOfReferenceId = dbFor2.id))
      val dbSeries3 = dao.insert(series3.copy(studyId = dbStudy1.id, equipmentId = dbEquipment3.id, frameOfReferenceId = dbFor1.id))
      val dbSeries4 = dao.insert(series4.copy(studyId = dbStudy2.id, equipmentId = dbEquipment1.id, frameOfReferenceId = dbFor2.id))
      val dbImage1 = dao.insert(image1.copy(seriesId = dbSeries1.id))
      val dbImage2 = dao.insert(image2.copy(seriesId = dbSeries2.id))
      val dbImage3 = dao.insert(image3.copy(seriesId = dbSeries3.id))
      val dbImage4 = dao.insert(image4.copy(seriesId = dbSeries4.id))
      val dbImage5 = dao.insert(image5.copy(seriesId = dbSeries1.id))
      val dbImage6 = dao.insert(image6.copy(seriesId = dbSeries2.id))
      val dbImage7 = dao.insert(image7.copy(seriesId = dbSeries3.id))
      val dbImage8 = dao.insert(image8.copy(seriesId = dbSeries4.id))
      dao.insert(imageFile1.copy(id = dbImage1.id))
      dao.insert(imageFile2.copy(id = dbImage2.id))
      dao.insert(imageFile3.copy(id = dbImage3.id))
      dao.insert(imageFile4.copy(id = dbImage4.id))
      dao.insert(imageFile5.copy(id = dbImage5.id))
      dao.insert(imageFile6.copy(id = dbImage6.id))
      dao.insert(imageFile7.copy(id = dbImage7.id))
      dao.insert(imageFile8.copy(id = dbImage8.id))

      dao.patients(0, 20, None, false, None).size should be(1)
      dao.studiesForPatient(0, 20, dbPat.id).size should be(2)
      dao.equipments.size should be(3)
      dao.frameOfReferences.size should be(2)
      dao.seriesForStudy(0, 20, dbStudy1.id).size should be(2)
      dao.seriesForStudy(0, 20, dbStudy2.id).size should be(2)
      dao.imagesForSeries(dbSeries1.id).size should be(2)
      dao.imagesForSeries(dbSeries2.id).size should be(2)
      dao.imagesForSeries(dbSeries3.id).size should be(2)
      dao.imagesForSeries(dbSeries4.id).size should be(2)
      dao.imageFileForImage(dbImage1.id).isDefined should be(true)
      dao.imageFileForImage(dbImage2.id).isDefined should be(true)
      dao.imageFileForImage(dbImage3.id).isDefined should be(true)
      dao.imageFileForImage(dbImage4.id).isDefined should be(true)
      dao.imageFileForImage(dbImage5.id).isDefined should be(true)
      dao.imageFileForImage(dbImage6.id).isDefined should be(true)
      dao.imageFileForImage(dbImage7.id).isDefined should be(true)
      dao.imageFileForImage(dbImage8.id).isDefined should be(true)
      dao.imageFilesForSeries(dbSeries1.id).size should be(2)
      dao.imageFilesForSeries(dbSeries2.id).size should be(2)
      dao.imageFilesForSeries(dbSeries3.id).size should be(2)
      dao.imageFilesForSeries(dbSeries4.id).size should be(2)
      dao.imageFilesForStudy(dbStudy1.id).size should be(4)
      dao.imageFilesForStudy(dbStudy2.id).size should be(4)
      dao.imageFilesForPatient(dbPat.id).size should be(8)
    }
  }
  
  it should "return the correct number of patients for patients queries" in {
    db.withSession { implicit session =>
      // Queries on Patient properties
      dao.queryPatients(0, 10, None, true, Seq(QueryProperty("PatientName", QueryOperator.EQUALS, "p1"))).size should be(1)
      dao.queryPatients(0, 10, None, true, Seq(QueryProperty("PatientName", QueryOperator.EQUALS, "p1"), QueryProperty("PatientSex", QueryOperator.EQUALS, "M"))).size should be(1)
      dao.queryPatients(0, 10, None, true, Seq(QueryProperty("PatientName", QueryOperator.EQUALS, "p1"), QueryProperty("PatientSex", QueryOperator.EQUALS, "F"))).size should be(0)
      
      // Check that query returns Patient with all data
      dao.queryPatients(0, 1, None, true, Seq(QueryProperty("PatientName", QueryOperator.EQUALS, "p1"))).foreach(dbPatient => {
        dbPatient.id should be >= (0L)
        dbPatient.patientName.value should be("p1")
        dbPatient.patientID.value should be("s1")
        dbPatient.patientBirthDate.value should be("2000-01-01")
        dbPatient.patientSex.value should be("M")
      })
      
      // Queries on Study properties
      dao.queryPatients(0, 10, None, true, Seq(QueryProperty("StudyInstanceUID", QueryOperator.EQUALS, "stuid1"))).size should be(1)
      dao.queryPatients(0, 10, None, true, Seq(QueryProperty("StudyInstanceUID", QueryOperator.EQUALS, "stuid1"), QueryProperty("StudyDate", QueryOperator.EQUALS, "19990101"))).size should be(1)
      dao.queryPatients(0, 10, None, true, Seq(QueryProperty("StudyInstanceUID", QueryOperator.EQUALS, "stuid1"), QueryProperty("StudyDate", QueryOperator.EQUALS, "20100101"))).size should be(0)
      
      // Queries on Series properties
      dao.queryPatients(0, 10, None, true, Seq(QueryProperty("SeriesInstanceUID", QueryOperator.EQUALS, "seuid1"))).size should be(1)
      
      // Queries on Equipments properties
      dao.queryPatients(0, 10, None, true, Seq(QueryProperty("Manufacturer", QueryOperator.EQUALS, "manu1"))).size should be(1)
      
      // Queries on FrameOfReferences properties
      dao.queryPatients(0, 10, None, true, Seq(QueryProperty("FrameOfReferenceUID", QueryOperator.EQUALS, "frid1"))).size should be(1)
      
      // Test like query
      dao.queryPatients(0, 10, None, true, Seq(QueryProperty("StudyDescription", QueryOperator.LIKE, "%desc%"))).size should be(1)
      
      // Test paging
      dao.queryPatients(1, 1, None, true, Seq(QueryProperty("Manufacturer", QueryOperator.EQUALS, "manu1"))).size should be(0)
    }
  }
  
  it should "return the correct number of studies for studies queries" in {
    db.withSession { implicit session =>
      // Queries on Study properties
      dao.queryStudies(0, 10, None, true, Seq(QueryProperty("StudyInstanceUID", QueryOperator.EQUALS, "stuid1"))).size should be(1)
      
      // Check that query returns Studies with all data
      dao.queryStudies(0, 1, None, true, Seq(QueryProperty("StudyInstanceUID", QueryOperator.EQUALS, "stuid1"))).foreach(dbStudy => {
          dbStudy.id should be >= (0L)
          dbStudy.patientId should be >= (0L)
          dbStudy.studyInstanceUID.value should be("stuid1")
          dbStudy.studyDescription.value should be("stdesc1")
          dbStudy.studyDate.value should be("19990101")
          dbStudy.studyID.value should be("stid1")
          dbStudy.accessionNumber.value should be("acc1")
          dbStudy.patientAge.value should be("12Y")
        })
        
      // Queries on Patient properties
      dao.queryStudies(0, 10, None, true, Seq(QueryProperty("PatientName", QueryOperator.EQUALS, "p1"))).size should be(2)
      
      // Queries on Series properties
      dao.queryStudies(0, 10, None, true, Seq(QueryProperty("SeriesInstanceUID", QueryOperator.EQUALS, "seuid1"))).size should be(1)
      
      // Queries on Equipments properties
      dao.queryStudies(0, 10, None, true, Seq(QueryProperty("Manufacturer", QueryOperator.EQUALS, "manu2"))).size should be(1)
      
      // Queries on FrameOfReferences properties
      dao.queryStudies(0, 10, None, true, Seq(QueryProperty("FrameOfReferenceUID", QueryOperator.EQUALS, "frid1"))).size should be(1)
    }
  }
  
  it should "return the correct number of series for series queries" in {
    db.withSession { implicit session =>
      // Queries on Study properties
      dao.querySeries(0, 10, None, true, Seq(QueryProperty("SeriesInstanceUID", QueryOperator.EQUALS, "seuid1"))).size should be(1)
      
      // Check that query returns Studies with all data
      dao.querySeries(0, 1, None, true, Seq(QueryProperty("SeriesInstanceUID", QueryOperator.EQUALS, "seuid1"))).foreach(dbSeries => {
          dbSeries.id should be >= (0L)
          dbSeries.studyId should be >= (0L)
          dbSeries.equipmentId should be >= (0L)
          dbSeries.frameOfReferenceId should be >= (0L)
          dbSeries.seriesInstanceUID.value should be("seuid1")
          dbSeries.seriesDescription.value should be("sedesc1")
          dbSeries.seriesDate.value should be("19990101")
          dbSeries.modality.value should be("NM")
          dbSeries.protocolName.value should be("prot1")
          dbSeries.bodyPartExamined.value should be("bodypart1")
        })
        
      // Queries on Patient properties
      dao.querySeries(0, 10, None, true, Seq(QueryProperty("PatientName", QueryOperator.EQUALS, "p1"))).size should be(4)
      
      // Queries on Studies properties
      dao.querySeries(0, 10, None, true, Seq(QueryProperty("StudyInstanceUID", QueryOperator.EQUALS, "stuid1"))).size should be(2)
      
      // Queries on Equipments properties
      dao.querySeries(0, 10, None, true, Seq(QueryProperty("Manufacturer", QueryOperator.EQUALS, "manu2"))).size should be(1)
      
      // Queries on FrameOfReferences properties
      dao.querySeries(0, 10, None, true, Seq(QueryProperty("FrameOfReferenceUID", QueryOperator.EQUALS, "frid1"))).size should be(2)
    }
  }

  it should "support listing flat series complete with series, study and patient information" in {
    db.withSession { implicit session =>
      val flatSeries = dao.flatSeries(0, 20, None, true, None)
      flatSeries.length should be(4)
      flatSeries(0).series should not be (null)
      flatSeries(0).study should not be (null)
      flatSeries(0).patient should not be (null)
    }
  }

  it should "support accessing a single flat series by id" in {
    db.withSession { implicit session =>
      dao.series.foreach(s => {
        val flatSeries = dao.flatSeriesById(s.id)
        flatSeries should not be (None)
        flatSeries.get.id should be(s.id)
      })
    }
  }
}
