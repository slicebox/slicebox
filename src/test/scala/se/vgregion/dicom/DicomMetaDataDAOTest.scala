package se.vgregion.dicom

import scala.slick.driver.H2Driver
import scala.slick.jdbc.JdbcBackend.Database
import org.scalatest.FlatSpec
import org.scalatest.Matchers
import DicomDispatchProtocol._
import DicomHierarchy._
import DicomMetaDataProtocol._
import DicomPropertyValue._

class DicomMetaDataDAOTest extends FlatSpec with Matchers {

  private val db = Database.forURL("jdbc:h2:mem:dicommetadatadaotest;DB_CLOSE_DELAY=-1", driver = "org.h2.Driver")

  val dao = new DicomMetaDataDAO(H2Driver)

  db.withSession { implicit session =>
    dao.create
  }

  val owner1 = Owner("Owner1")
  val owner2 = Owner("Owner2")
  val owner3 = Owner("Owner3")

  val pat1 = Patient(PatientName("p1"), PatientID("s1"), PatientBirthDate("2000-01-01"), PatientSex("M"))
  val study1 = Study(pat1, StudyInstanceUID("stuid1"), StudyDescription("stdesc1"), StudyDate("19990101"), StudyID("stid1"), AccessionNumber("acc1"))
  val study2 = Study(pat1, StudyInstanceUID("stuid2"), StudyDescription("stdesc2"), StudyDate("19990102"), StudyID("stid2"), AccessionNumber("acc2"))
  val series1 = Series(study1, Equipment(Manufacturer("manu1"), StationName("station1")), FrameOfReference(FrameOfReferenceUID("frid1")), SeriesInstanceUID("seuid1"), SeriesDescription("sedesc1"), SeriesDate("19990101"), Modality("NM"), ProtocolName("prot1"), BodyPartExamined("bodypart1"))
  val series2 = Series(study2, Equipment(Manufacturer("manu2"), StationName("station2")), FrameOfReference(FrameOfReferenceUID("frid2")), SeriesInstanceUID("seuid2"), SeriesDescription("sedesc2"), SeriesDate("19990102"), Modality("NM"), ProtocolName("prot2"), BodyPartExamined("bodypart2"))
  val series3 = Series(study1, Equipment(Manufacturer("manu1"), StationName("station1")), FrameOfReference(FrameOfReferenceUID("frid1")), SeriesInstanceUID("seuid3"), SeriesDescription("sedesc3"), SeriesDate("19990103"), Modality("NM"), ProtocolName("prot3"), BodyPartExamined("bodypart1"))
  val series4 = Series(study2, Equipment(Manufacturer("manu3"), StationName("station3")), FrameOfReference(FrameOfReferenceUID("frid2")), SeriesInstanceUID("seuid4"), SeriesDescription("sedesc4"), SeriesDate("19990104"), Modality("NM"), ProtocolName("prot4"), BodyPartExamined("bodypart2"))
  val image1 = Image(series1, SOPInstanceUID("souid1"), ImageType("PRIMARY/RECON/TOMO"))
  val image2 = Image(series2, SOPInstanceUID("souid2"), ImageType("PRIMARY/RECON/TOMO"))
  val image3 = Image(series3, SOPInstanceUID("souid3"), ImageType("PRIMARY/RECON/TOMO"))
  val image4 = Image(series4, SOPInstanceUID("souid4"), ImageType("PRIMARY/RECON/TOMO"))
  val image5 = Image(series1, SOPInstanceUID("souid5"), ImageType("PRIMARY/RECON/TOMO"))
  val image6 = Image(series2, SOPInstanceUID("souid6"), ImageType("PRIMARY/RECON/TOMO"))
  val image7 = Image(series3, SOPInstanceUID("souid7"), ImageType("PRIMARY/RECON/TOMO"))
  val image8 = Image(series4, SOPInstanceUID("souid8"), ImageType("PRIMARY/RECON/TOMO"))
  val imageFile1 = ImageFile(image1, FileName("file1"), owner1)
  val imageFile2 = ImageFile(image2, FileName("file2"), owner2)
  val imageFile3 = ImageFile(image3, FileName("file3"), owner1)
  val imageFile4 = ImageFile(image4, FileName("file4"), owner2)
  val imageFile5 = ImageFile(image5, FileName("file5"), owner1)
  val imageFile6 = ImageFile(image6, FileName("file6"), owner2)
  val imageFile7 = ImageFile(image7, FileName("file7"), owner1)
  val imageFile8 = ImageFile(image8, FileName("file8"), owner2)

  val pat1_copy = Patient(PatientName("p1"), PatientID("s1"), PatientBirthDate("1000-01-01"), PatientSex("F"))
  val study3 = Study(pat1_copy, StudyInstanceUID("stuid3"), StudyDescription("stdesc3"), StudyDate("19990103"), StudyID("stid3"), AccessionNumber("acc3"))
  // the following series has a copied SeriesInstanceUID but unique parent study so should no be treated as copy
  val series1_copy = Series(study3, Equipment(Manufacturer("manu1"), StationName("station1")), FrameOfReference(FrameOfReferenceUID("frid1")), SeriesInstanceUID("souid1"), SeriesDescription("sedesc1"), SeriesDate("19990101"), Modality("NM"), ProtocolName("prot1"), BodyPartExamined("bodypart1"))
  val image9 = Image(series1_copy, SOPInstanceUID("souid9"), ImageType("PRIMARY/RECON/TOMO"))
  val imageFile9 = ImageFile(image9, FileName("file9"), owner1)

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

  it should "be empty once again after the single existing patient has been deleted" in {
    db.withSession { implicit session =>
      dao.deletePatient(pat1)
      dao.patientCount should be(0)
      dao.imageFileCount should be(0)
    }
  }

  it should "return the correct number of files, images, series, studies and patients grouped by owner" in {
    db.withSession { implicit session =>
      dao.insert(imageFile1)
      dao.insert(imageFile2)
      dao.insert(imageFile3)

      dao.studyCount should be (2)

      val owner1Files = dao.imageFilesForOwner(owner1)
      val owner2Files = dao.imageFilesForOwner(owner2)
      val owner3Files = dao.imageFilesForOwner(owner3)
      dao.imageFileCount should be (3)
      owner1Files.map(_.fileName.value) should be (List("file1", "file3"))
      owner2Files.map(_.fileName.value) should be (List("file2"))
      owner3Files.map(_.fileName.value) should be (List())

      val owner1Images = dao.imagesForOwner(owner1)
      val owner2Images = dao.imagesForOwner(owner2)
      val owner3Images = dao.imagesForOwner(owner3)
      owner1Images.map(_.sopInstanceUID.value) should be (List("souid1", "souid3"))
      owner2Images.map(_.sopInstanceUID.value) should be (List("souid2"))
      owner3Images.map(_.sopInstanceUID.value) should be (List())

      val owner1Series = dao.seriesForOwner(owner1)
      val owner2Series = dao.seriesForOwner(owner2)
      val owner3Series = dao.seriesForOwner(owner3)
      owner1Series.map(_.seriesInstanceUID.value) should be (List("seuid1", "seuid3"))
      owner2Series.map(_.seriesInstanceUID.value) should be (List("seuid2"))
      owner3Series.map(_.seriesInstanceUID.value) should be (List())

      val owner1Studies = dao.studiesForOwner(owner1)
      val owner2Studies = dao.studiesForOwner(owner2)
      val owner3Studies = dao.studiesForOwner(owner3)
      owner1Studies.map(_.studyInstanceUID.value) should be (List("stuid1"))
      owner2Studies.map(_.studyInstanceUID.value) should be (List("stuid2"))
      owner3Studies.map(_.studyInstanceUID.value) should be (List())

      val owner1Pats = dao.patientsForOwner(owner1)
      val owner2Pats = dao.patientsForOwner(owner2)
      val owner3Pats = dao.patientsForOwner(owner3)
      owner1Pats.map(_.patientName.value) should be (List("p1"))
      owner2Pats.map(_.patientName.value) should be (List("p1"))
      owner3Pats.map(_.patientName.value) should be (List())
    }
  }

  it should "be empty once again (2) after the single existing patient has been deleted" in {
    db.withSession { implicit session =>
      dao.deletePatient(pat1)
      dao.patientCount should be(0)
      dao.imageFileCount should be(0)
    }
  }

  it should "contain the correct number of entries on each level when inserting a binary tree-like file structure, grouped by owner" in {
    db.withSession { implicit session =>
      dao.insert(imageFile1)
      dao.insert(imageFile2)
      dao.insert(imageFile3)
      dao.insert(imageFile4)
      dao.insert(imageFile5)
      dao.insert(imageFile6)
      dao.insert(imageFile7)
      dao.insert(imageFile8)

      dao.patientsForOwner(owner1).map(p => 
        p.patientName.value) should be (List("p1"))
      dao.patientsForOwner(owner2).map(p => 
        p.patientName.value) should be (List("p1"))

      dao.studiesForPatient(pat1, owner1).map(s => 
        s.studyInstanceUID.value) should be (List("stuid1"))
      dao.studiesForPatient(pat1, owner2).map(s => 
        s.studyInstanceUID.value) should be (List("stuid2"))

      dao.seriesForStudy(study1, owner1).map(s => 
        s.seriesInstanceUID.value) should be (List("seuid1", "seuid3"))
      dao.seriesForStudy(study1, owner2).map(s => 
        s.seriesInstanceUID.value) should be (List())
      dao.seriesForStudy(study2, owner1).map(s => 
        s.seriesInstanceUID.value) should be (List())
      dao.seriesForStudy(study2, owner2).map(s => 
        s.seriesInstanceUID.value) should be (List("seuid2", "seuid4"))

      dao.imagesForSeries(series1, owner1).map(s => 
        s.sopInstanceUID.value) should be (List("souid1", "souid5"))
      dao.imagesForSeries(series1, owner2).map(s => 
        s.sopInstanceUID.value) should be (List())
      dao.imagesForSeries(series2, owner1).map(s => 
        s.sopInstanceUID.value) should be (List())
      dao.imagesForSeries(series2, owner2).map(s => 
        s.sopInstanceUID.value) should be (List("souid2", "souid6"))
      dao.imagesForSeries(series3, owner1).map(s => 
        s.sopInstanceUID.value) should be (List("souid3", "souid7"))
      dao.imagesForSeries(series3, owner2).map(s => 
        s.sopInstanceUID.value) should be (List())
      dao.imagesForSeries(series4, owner1).map(s => 
        s.sopInstanceUID.value) should be (List())
      dao.imagesForSeries(series4, owner2).map(s => 
        s.sopInstanceUID.value) should be (List("souid4", "souid8"))
    }
  }

  it should "be possible to change the owner of an image file" in {
    db.withSession { implicit session =>
      val updated = dao.changeOwner(imageFile8, owner3)
      val owner3Files = dao.imageFilesForOwner(owner3)
      val imageFile8_updated = ImageFile(image8, FileName("file8"), owner3)
      owner3Files should be (List(imageFile8_updated))
    }
  }
}