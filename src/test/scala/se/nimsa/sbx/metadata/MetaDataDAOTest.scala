package se.nimsa.sbx.metadata

import scala.slick.driver.H2Driver
import scala.slick.jdbc.JdbcBackend.Database
import org.scalatest.FlatSpec
import org.scalatest.Matchers
import se.nimsa.sbx.dicom.DicomPropertyValue._
import se.nimsa.sbx.dicom.DicomHierarchy._
import org.h2.jdbc.JdbcSQLException
import MetaDataProtocol._

class MetaDataDAOTest extends FlatSpec with Matchers {

  private val db = Database.forURL("jdbc:h2:mem:dicommetadatadaotest;DB_CLOSE_DELAY=-1", driver = "org.h2.Driver")

  val dao = new MetaDataDAO(H2Driver)

  db.withSession { implicit session =>
    dao.create
  }

  val pat1 = Patient(-1, PatientName("p1"), PatientID("s1"), PatientBirthDate("2000-01-01"), PatientSex("M"))
  val study1 = Study(-1, -1, StudyInstanceUID("stuid1"), StudyDescription("stdesc1"), StudyDate("19990101"), StudyID("stid1"), AccessionNumber("acc1"), PatientAge("12Y"))
  val study2 = Study(-1, -1, StudyInstanceUID("stuid2"), StudyDescription("stdesc2"), StudyDate("19990102"), StudyID("stid2"), AccessionNumber("acc2"), PatientAge("14Y"))
  val series1 = Series(-1, -1, SeriesInstanceUID("seuid1"), SeriesDescription("sedesc1"), SeriesDate("19990101"), Modality("NM"), ProtocolName("prot1"), BodyPartExamined("bodypart1"), Manufacturer("manu1"), StationName("station1"), FrameOfReferenceUID("frid1"))
  val series2 = Series(-1, -1, SeriesInstanceUID("seuid2"), SeriesDescription("sedesc2"), SeriesDate("19990102"), Modality("NM"), ProtocolName("prot2"), BodyPartExamined("bodypart2"), Manufacturer("manu1"), StationName("station1"), FrameOfReferenceUID("frid2"))
  val series3 = Series(-1, -1, SeriesInstanceUID("seuid3"), SeriesDescription("sedesc3"), SeriesDate("19990103"), Modality("NM"), ProtocolName("prot3"), BodyPartExamined("bodypart1"), Manufacturer("manu2"), StationName("station2"), FrameOfReferenceUID("frid1"))
  val series4 = Series(-1, -1, SeriesInstanceUID("seuid4"), SeriesDescription("sedesc4"), SeriesDate("19990104"), Modality("NM"), ProtocolName("prot4"), BodyPartExamined("bodypart2"), Manufacturer("manu3"), StationName("station3"), FrameOfReferenceUID("frid2"))
  val image1 = Image(-1, -1, SOPInstanceUID("souid1"), ImageType("PRIMARY/RECON/TOMO"), InstanceNumber("1"))
  val image2 = Image(-1, -1, SOPInstanceUID("souid2"), ImageType("PRIMARY/RECON/TOMO"), InstanceNumber("1"))
  val image3 = Image(-1, -1, SOPInstanceUID("souid3"), ImageType("PRIMARY/RECON/TOMO"), InstanceNumber("1"))
  val image4 = Image(-1, -1, SOPInstanceUID("souid4"), ImageType("PRIMARY/RECON/TOMO"), InstanceNumber("1"))
  val image5 = Image(-1, -1, SOPInstanceUID("souid5"), ImageType("PRIMARY/RECON/TOMO"), InstanceNumber("1"))
  val image6 = Image(-1, -1, SOPInstanceUID("souid6"), ImageType("PRIMARY/RECON/TOMO"), InstanceNumber("1"))
  val image7 = Image(-1, -1, SOPInstanceUID("souid7"), ImageType("PRIMARY/RECON/TOMO"), InstanceNumber("1"))
  val image8 = Image(-1, -1, SOPInstanceUID("souid8"), ImageType("PRIMARY/RECON/TOMO"), InstanceNumber("1"))

  val pat1_copy = Patient(-1, PatientName("p1"), PatientID("s1"), PatientBirthDate("1000-01-01"), PatientSex("F"))
  val study3 = Study(-1, -1, StudyInstanceUID("stuid3"), StudyDescription("stdesc3"), StudyDate("19990103"), StudyID("stid3"), AccessionNumber("acc3"), PatientAge("13Y"))
  // the following series has a copied SeriesInstanceUID but unique parent study so should no be treated as copy
  val series1_copy = Series(-1, -1, SeriesInstanceUID("souid1"), SeriesDescription("sedesc1"), SeriesDate("19990101"), Modality("NM"), ProtocolName("prot1"), BodyPartExamined("bodypart1"), Manufacturer("manu1"), StationName("station1"), FrameOfReferenceUID("frid1"))
  val image9 = Image(-1, -1, SOPInstanceUID("souid9"), ImageType("PRIMARY/RECON/TOMO"), InstanceNumber("1"))

  "The meta data db" should "be emtpy before anything has been added" in {
    db.withSession { implicit session =>
      dao.images.size should be(0)
    }
  }

  it should "contain one entry in each table after inserting one patient, study, series, equipent, frame of referenc and image" in {
    db.withSession { implicit session =>
      val dbPat = dao.insert(pat1)
      val dbStudy = dao.insert(study1.copy(patientId = dbPat.id))
      val dbSeries = dao.insert(series1.copy(studyId = dbStudy.id))
      val dbImage = dao.insert(image1.copy(seriesId = dbSeries.id))

      dao.images.size should be(1)
      dao.series.size should be(1)
      dao.studies.size should be(1)
      dao.patients(0, 20, None, false, None).size should be(1)

      dbPat.id should be >= (0L)
      dbStudy.id should be >= (0L)
      dbSeries.id should be >= (0L)
      dbImage.id should be >= (0L)
    }
  }

  it should "cascade delete linked studies, series and images when a patient is deleted" in {
    db.withSession { implicit session =>
      dao.patientByNameAndID(pat1).foreach(dbPat => {
        dao.deletePatient(dbPat.id)
        dao.images.size should be(0)
        dao.series.size should be(0)
        dao.studies.size should be(0)
        dao.patients(0, 20, None, false, None).size should be(0)
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
      val dbSeries1 = dao.insert(series1.copy(studyId = dbStudy1.id))
      val dbSeries2 = dao.insert(series2.copy(studyId = dbStudy2.id))
      val dbSeries3 = dao.insert(series3.copy(studyId = dbStudy1.id))
      val dbSeries4 = dao.insert(series4.copy(studyId = dbStudy2.id))
      val dbImage1 = dao.insert(image1.copy(seriesId = dbSeries1.id))
      val dbImage2 = dao.insert(image2.copy(seriesId = dbSeries2.id))
      val dbImage3 = dao.insert(image3.copy(seriesId = dbSeries3.id))
      val dbImage4 = dao.insert(image4.copy(seriesId = dbSeries4.id))
      val dbImage5 = dao.insert(image5.copy(seriesId = dbSeries1.id))
      val dbImage6 = dao.insert(image6.copy(seriesId = dbSeries2.id))
      val dbImage7 = dao.insert(image7.copy(seriesId = dbSeries3.id))
      val dbImage8 = dao.insert(image8.copy(seriesId = dbSeries4.id))

      dao.patients(0, 20, None, false, None).size should be(1)
      dao.studiesForPatient(0, 20, dbPat.id).size should be(2)
      dao.seriesForStudy(0, 20, dbStudy1.id).size should be(2)
      dao.seriesForStudy(0, 20, dbStudy2.id).size should be(2)
      dao.imagesForSeries(0, 20, dbSeries1.id).size should be(2)
      dao.imagesForSeries(0, 20, dbSeries2.id).size should be(2)
      dao.imagesForSeries(0, 20, dbSeries3.id).size should be(2)
      dao.imagesForSeries(0, 20, dbSeries4.id).size should be(2)
    }
  }
  
  it should "return the correct number of patients for patients queries" in {
    db.withSession { implicit session =>
      // Queries on Patient properties
      dao.queryPatients(0, 10, None, true, Seq(QueryProperty("patientName", QueryOperator.EQUALS, "p1"))).size should be(1)
      dao.queryPatients(0, 10, None, true, Seq(QueryProperty("patientName", QueryOperator.EQUALS, "p1"), QueryProperty("patientSex", QueryOperator.EQUALS, "M"))).size should be(1)
      dao.queryPatients(0, 10, None, true, Seq(QueryProperty("patientName", QueryOperator.EQUALS, "p1"), QueryProperty("patientSex", QueryOperator.EQUALS, "F"))).size should be(0)
      
      // Check that query returns Patient with all data
      dao.queryPatients(0, 1, None, true, Seq(QueryProperty("patientName", QueryOperator.EQUALS, "p1"))).foreach(dbPatient => {
        dbPatient.id should be >= (0L)
        dbPatient.patientName.value should be("p1")
        dbPatient.patientID.value should be("s1")
        dbPatient.patientBirthDate.value should be("2000-01-01")
        dbPatient.patientSex.value should be("M")
      })
      
      // Queries on Study properties
      dao.queryPatients(0, 10, None, true, Seq(QueryProperty("studyInstanceUID", QueryOperator.EQUALS, "stuid1"))).size should be(1)
      dao.queryPatients(0, 10, None, true, Seq(QueryProperty("studyInstanceUID", QueryOperator.EQUALS, "stuid1"), QueryProperty("studyDate", QueryOperator.EQUALS, "19990101"))).size should be(1)
      dao.queryPatients(0, 10, None, true, Seq(QueryProperty("studyInstanceUID", QueryOperator.EQUALS, "stuid1"), QueryProperty("studyDate", QueryOperator.EQUALS, "20100101"))).size should be(0)
      
      // Queries on Series properties
      dao.queryPatients(0, 10, None, true, Seq(QueryProperty("seriesInstanceUID", QueryOperator.EQUALS, "seuid1"))).size should be(1)
      
      // Queries on Equipments properties
      dao.queryPatients(0, 10, None, true, Seq(QueryProperty("manufacturer", QueryOperator.EQUALS, "manu1"))).size should be(1)
      
      // Queries on FrameOfReferences properties
      dao.queryPatients(0, 10, None, true, Seq(QueryProperty("frameOfReferenceUID", QueryOperator.EQUALS, "frid1"))).size should be(1)
      
      // Test like query
      dao.queryPatients(0, 10, None, true, Seq(QueryProperty("studyDescription", QueryOperator.LIKE, "desc"))).size should be(1)
      
      // Test paging
      dao.queryPatients(1, 1, None, true, Seq(QueryProperty("manufacturer", QueryOperator.EQUALS, "manu1"))).size should be(0)
    }
  }
  
  it should "return the correct number of studies for studies queries" in {
    db.withSession { implicit session =>
      // Queries on Study properties
      dao.queryStudies(0, 10, None, true, Seq(QueryProperty("studyInstanceUID", QueryOperator.EQUALS, "stuid1"))).size should be(1)
      
      // Check that query returns Studies with all data
      dao.queryStudies(0, 1, None, true, Seq(QueryProperty("studyInstanceUID", QueryOperator.EQUALS, "stuid1"))).foreach(dbStudy => {
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
      dao.queryStudies(0, 10, None, true, Seq(QueryProperty("patientName", QueryOperator.EQUALS, "p1"))).size should be(2)
      
      // Queries on Series properties
      dao.queryStudies(0, 10, None, true, Seq(QueryProperty("seriesInstanceUID", QueryOperator.EQUALS, "seuid1"))).size should be(1)
      
      // Queries on Equipments properties
      dao.queryStudies(0, 10, None, true, Seq(QueryProperty("manufacturer", QueryOperator.EQUALS, "manu2"))).size should be(1)
      
      // Queries on FrameOfReferences properties
      dao.queryStudies(0, 10, None, true, Seq(QueryProperty("frameOfReferenceUID", QueryOperator.EQUALS, "frid1"))).size should be(1)
    }
  }
  
  it should "return the correct number of series for series queries" in {
    db.withSession { implicit session =>
      // Queries on Series properties
      dao.querySeries(0, 10, None, true, Seq(QueryProperty("seriesInstanceUID", QueryOperator.EQUALS, "seuid1"))).size should be(1)
      
      // Check that query returns Studies with all data
      dao.querySeries(0, 1, None, true, Seq(QueryProperty("seriesInstanceUID", QueryOperator.EQUALS, "seuid1"))).foreach(dbSeries => {
          dbSeries.id should be >= (0L)
          dbSeries.studyId should be >= (0L)
          dbSeries.seriesDescription.value should be("sedesc1")
          dbSeries.seriesDate.value should be("19990101")
          dbSeries.modality.value should be("NM")
          dbSeries.protocolName.value should be("prot1")
          dbSeries.bodyPartExamined.value should be("bodypart1")
        })
        
      // Queries on Patient properties
      dao.querySeries(0, 10, None, true, Seq(QueryProperty("patientName", QueryOperator.EQUALS, "p1"))).size should be(4)
      
      // Queries on Studies properties
      dao.querySeries(0, 10, None, true, Seq(QueryProperty("studyInstanceUID", QueryOperator.EQUALS, "stuid1"))).size should be(2)
      
      // Queries on Equipments properties
      dao.querySeries(0, 10, None, true, Seq(QueryProperty("manufacturer", QueryOperator.EQUALS, "manu2"))).size should be(1)
      
      // Queries on FrameOfReferences properties
      dao.querySeries(0, 10, None, true, Seq(QueryProperty("frameOfReferenceUID", QueryOperator.EQUALS, "frid1"))).size should be(2)
    }
  }

  it should "return the correct number of images for image queries" in {
    db.withSession { implicit session =>
      // Queries on Image properties
      dao.queryImages(0, 10, None, true, Seq(QueryProperty("sopInstanceUID", QueryOperator.EQUALS, "souid1"))).size should be(1)
      
      // Check that query returns Studies with all data
      dao.queryImages(0, 1, None, true, Seq(QueryProperty("sopInstanceUID", QueryOperator.EQUALS, "souid1"))).foreach(dbImage => {
          dbImage.id should be >= (0L)
          dbImage.seriesId should be >= (0L)
          dbImage.sopInstanceUID.value should be("souid1")
          dbImage.imageType.value should be("PRIMARY/RECON/TOMO")
          dbImage.instanceNumber.value should be("1")
        })
        
      // Queries on Patient properties
      dao.queryImages(0, 10, None, true, Seq(QueryProperty("patientName", QueryOperator.EQUALS, "p1"))).size should be(8)
      
      // Queries on Studies properties
      dao.queryImages(0, 10, None, true, Seq(QueryProperty("studyInstanceUID", QueryOperator.EQUALS, "stuid1"))).size should be(4)
      
      // Queries on Equipments properties
      dao.queryImages(0, 10, None, true, Seq(QueryProperty("manufacturer", QueryOperator.EQUALS, "manu2"))).size should be(2)
      
      // Queries on FrameOfReferences properties
      dao.queryImages(0, 10, None, true, Seq(QueryProperty("frameOfReferenceUID", QueryOperator.EQUALS, "frid1"))).size should be(4)
      
      // Queries on Series properties
      dao.queryImages(0, 10, None, true, Seq(QueryProperty("seriesInstanceUID", QueryOperator.EQUALS, "seuid1"))).size should be(2)
    }
  }

  it should "return the correct number of flat series for flat series queries" in {
    db.withSession { implicit session =>
      // Queries on Series properties
      dao.queryFlatSeries(0, 10, None, true, Seq(QueryProperty("seriesInstanceUID", QueryOperator.EQUALS, "seuid1"))).size should be(1)
      
      // Check that query returns Studies with all data
      dao.queryFlatSeries(0, 1, None, true, Seq(QueryProperty("seriesInstanceUID", QueryOperator.EQUALS, "seuid1"))).foreach(dbFlatSeries => {
          dbFlatSeries.id should be >= (0L)
          dbFlatSeries.series.studyId should be >= (0L)
          dbFlatSeries.series.seriesDescription.value should be("sedesc1")
          dbFlatSeries.series.seriesDate.value should be("19990101")
          dbFlatSeries.series.modality.value should be("NM")
          dbFlatSeries.series.protocolName.value should be("prot1")
          dbFlatSeries.series.bodyPartExamined.value should be("bodypart1")
        })
        
      // Queries on Patient properties
      dao.queryFlatSeries(0, 10, None, true, Seq(QueryProperty("patientName", QueryOperator.EQUALS, "p1"))).size should be(4)
      
      // Queries on Studies properties
      dao.queryFlatSeries(0, 10, None, true, Seq(QueryProperty("studyInstanceUID", QueryOperator.EQUALS, "stuid1"))).size should be(2)
      
      // Queries on Equipments properties
      dao.queryFlatSeries(0, 10, None, true, Seq(QueryProperty("manufacturer", QueryOperator.EQUALS, "manu2"))).size should be(1)
      
      // Queries on FrameOfReferences properties
      dao.queryFlatSeries(0, 10, None, true, Seq(QueryProperty("frameOfReferenceUID", QueryOperator.EQUALS, "frid1"))).size should be(2)
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
