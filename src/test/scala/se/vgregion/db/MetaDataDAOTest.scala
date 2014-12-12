package se.vgregion.db

import org.scalatest._
import scala.slick.jdbc.JdbcBackend.Database
import scala.slick.driver.H2Driver
import se.vgregion.dicom.DicomPropertyValue._
import se.vgregion.dicom.MetaDataProtocol._

class MetaDataDAOTest extends FlatSpec with Matchers {

  private val db = Database.forURL("jdbc:h2:mem:dbtest2;DB_CLOSE_DELAY=-1", driver = "org.h2.Driver")

  val dao = new MetaDataDAO(H2Driver, null)

  db.withSession { implicit session =>
    dao.create
  }

  val pat1 = Patient(PatientName("p1"), PatientID("s1"), PatientBirthDate("2000-01-01"), PatientSex("M"))
  val study1 = Study(pat1, StudyInstanceUID("stuid1"), StudyDescription("stdesc1"), StudyDate("19990101"), StudyID("stid1"), AccessionNumber("acc1"))
  val study2 = Study(pat1, StudyInstanceUID("stuid2"), StudyDescription("stdesc2"), StudyDate("19990102"), StudyID("stid2"), AccessionNumber("acc2"))
  val series1 = Series(study1, Equipment(Manufacturer("manu1"), StationName("station1")), FrameOfReference(FrameOfReferenceUID("frid1")), SeriesInstanceUID("souid1"), SeriesDescription("sedesc1"), SeriesDate("19990101"), Modality("NM"), ProtocolName("prot1"), BodyPartExamined("bodypart1"))
  val series2 = Series(study2, Equipment(Manufacturer("manu2"), StationName("station2")), FrameOfReference(FrameOfReferenceUID("frid2")), SeriesInstanceUID("souid2"), SeriesDescription("sedesc2"), SeriesDate("19990102"), Modality("NM"), ProtocolName("prot2"), BodyPartExamined("bodypart2"))
  val series3 = Series(study1, Equipment(Manufacturer("manu1"), StationName("station1")), FrameOfReference(FrameOfReferenceUID("frid1")), SeriesInstanceUID("souid3"), SeriesDescription("sedesc3"), SeriesDate("19990103"), Modality("NM"), ProtocolName("prot3"), BodyPartExamined("bodypart1"))
  val series4 = Series(study2, Equipment(Manufacturer("manu3"), StationName("station3")), FrameOfReference(FrameOfReferenceUID("frid2")), SeriesInstanceUID("souid4"), SeriesDescription("sedesc4"), SeriesDate("19990104"), Modality("NM"), ProtocolName("prot4"), BodyPartExamined("bodypart2"))
  val image1 = Image(series1, SOPInstanceUID("souid1"), ImageType("PRIMARY/RECON/TOMO"))
  val image2 = Image(series2, SOPInstanceUID("souid2"), ImageType("PRIMARY/RECON/TOMO"))
  val image3 = Image(series3, SOPInstanceUID("souid3"), ImageType("PRIMARY/RECON/TOMO"))
  val image4 = Image(series4, SOPInstanceUID("souid4"), ImageType("PRIMARY/RECON/TOMO"))
  val image5 = Image(series1, SOPInstanceUID("souid5"), ImageType("PRIMARY/RECON/TOMO"))
  val image6 = Image(series2, SOPInstanceUID("souid6"), ImageType("PRIMARY/RECON/TOMO"))
  val image7 = Image(series3, SOPInstanceUID("souid7"), ImageType("PRIMARY/RECON/TOMO"))
  val image8 = Image(series4, SOPInstanceUID("souid8"), ImageType("PRIMARY/RECON/TOMO"))
  val imageFile1 = ImageFile(image1, FileName("file1"))
  val imageFile2 = ImageFile(image2, FileName("file2"))
  val imageFile3 = ImageFile(image3, FileName("file3"))
  val imageFile4 = ImageFile(image4, FileName("file4"))
  val imageFile5 = ImageFile(image5, FileName("file5"))
  val imageFile6 = ImageFile(image6, FileName("file6"))
  val imageFile7 = ImageFile(image7, FileName("file7"))
  val imageFile8 = ImageFile(image8, FileName("file8"))

  val pat1_copy = Patient(PatientName("p1"), PatientID("s1"), PatientBirthDate("1000-01-01"), PatientSex("F"))
  val study3 = Study(pat1_copy, StudyInstanceUID("stuid3"), StudyDescription("stdesc3"), StudyDate("19990103"), StudyID("stid3"), AccessionNumber("acc3"))
  // the following series has a copied SeriesInstanceUID but unique parent study so should no be treated as copy
  val series1_copy = Series(study3, Equipment(Manufacturer("manu1"), StationName("station1")), FrameOfReference(FrameOfReferenceUID("frid1")), SeriesInstanceUID("souid1"), SeriesDescription("sedesc1"), SeriesDate("19990101"), Modality("NM"), ProtocolName("prot1"), BodyPartExamined("bodypart1"))
  val image9 = Image(series1_copy, SOPInstanceUID("souid9"), ImageType("PRIMARY/RECON/TOMO"))
  val imageFile9 = ImageFile(image9, FileName("file9"))
  
  "The meta data db" should "be emtpy before anything has been added" in {
    db.withSession { implicit session =>
      dao.allImageFiles.size should be(0)
    }
  }

  it should "contain one entry on each data level after inserting such an element" in {
    db.withSession { implicit session =>
      dao.insert(imageFile1)
      dao.allImageFiles.size should be(1)
      dao.allImages.size should be(1)
      dao.allSeries.size should be(1)
      dao.allStudies.size should be(1)
      dao.allPatients.size should be(1)
      dao.patientCount should be(1)
      dao.studyCount should be(1)
      dao.seriesCount should be(1)
      dao.imageCount should be(1)
      dao.imageFileCount should be(1)
      dao.equipmentCount should be(1)
      dao.frameOfReferenceCount should be(1)
    }
  }

  it should "be empty again once that element has been removed" in {
    db.withSession { implicit session =>
      dao.deleteImageFile(imageFile1)
      dao.allImageFiles.size should be(0)
      dao.patientCount should be(0)
      dao.studyCount should be(0)
      dao.seriesCount should be(0)
      dao.imageCount should be(0)
      dao.imageFileCount should be(0)
      dao.equipmentCount should be(0)
      dao.frameOfReferenceCount should be(0)
    }
  }

  it should "contain a single element after trying to insert the same element twice" in {
    db.withSession { implicit session =>
      dao.insert(imageFile1)
      dao.insert(imageFile1)
      dao.allImageFiles.size should be(1)
      dao.imageFileCount should be(1)
    }
  }

  it should "remove all related data when deleting by patient" in {
    db.withSession { implicit session =>
      dao.deletePatient(pat1)
      dao.patientCount should be(0)
      dao.studyCount should be(0)
      dao.seriesCount should be(0)
      dao.imageCount should be(0)
      dao.imageFileCount should be(0)
      dao.equipmentCount should be(0)
      dao.frameOfReferenceCount should be(0)
    }
  }

  it should "contain the correct number of entries on each level when inserting a binary tree-like file structure" in {
    db.withSession { implicit session =>
      dao.insert(imageFile1)
      dao.insert(imageFile2)
      dao.insert(imageFile3)
      dao.insert(imageFile4)
      dao.insert(imageFile5)
      dao.insert(imageFile6)
      dao.insert(imageFile7)
      dao.insert(imageFile8)

      dao.patientCount should be(1)
      dao.studyCount should be(2)
      dao.seriesCount should be(4)
      dao.imageCount should be(8)
      dao.imageFileCount should be(8)
      dao.equipmentCount should be(4)
      dao.frameOfReferenceCount should be(4)

      dao.allPatients.size should be(1)
      dao.allStudies.size should be(2)
      dao.allSeries.size should be(4)
      dao.allImages.size should be(8)
      dao.allImageFiles.size should be(8)
    }
  }
  
  it should "propagate a delete both downwards via cascades and upwards via explicit deletes" in {
    db.withSession { implicit session =>
      dao.deleteSeries(series1)
      dao.deleteSeries(series3)
      
      dao.patientCount should be(1)
      dao.studyCount should be(1)
      dao.seriesCount should be(2)
      dao.imageCount should be(4)
      dao.imageFileCount should be(4)
      dao.equipmentCount should be(2)
      dao.frameOfReferenceCount should be(2)

      dao.allPatients.size should be(1)
      dao.allStudies.size should be(1)
      dao.allSeries.size should be(2)
      dao.allImages.size should be(4)
      dao.allImageFiles.size should be(4)
    }
  }
  
  it should "be empty again after the single existing patient has been deleted" in {
    db.withSession { implicit session =>
      dao.deletePatient(pat1)
      dao.patientCount should be(0)
      dao.studyCount should be(0)
      dao.seriesCount should be(0)
      dao.imageCount should be(0)
      dao.imageFileCount should be(0)
      dao.equipmentCount should be(0)
      dao.frameOfReferenceCount should be(0)
    }
  }
  
  it should "limit inserts based on uniqueness" in {
    db.withSession { implicit session =>
      dao.insert(imageFile1)
      dao.insert(imageFile9)
      dao.patientCount should be(1)
      dao.studyCount should be(2)
      dao.seriesCount should be(2) // despite same UID, different parent study means not same
      dao.imageCount should be(2)
      dao.imageFileCount should be(2)
      dao.equipmentCount should be(2)
      dao.frameOfReferenceCount should be(2)
    }
  }
}