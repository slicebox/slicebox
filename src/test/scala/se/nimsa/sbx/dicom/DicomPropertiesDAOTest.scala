package se.nimsa.sbx.dicom

import scala.slick.driver.H2Driver
import scala.slick.jdbc.JdbcBackend.Database
import org.scalatest.FlatSpec
import org.scalatest.Matchers
import org.scalatest.BeforeAndAfterEach
import DicomPropertyValue._
import DicomHierarchy._
import DicomProtocol._
import org.h2.jdbc.JdbcSQLException

class DicomPropertiesDAOTest extends FlatSpec with Matchers with BeforeAndAfterEach {

  private val db = Database.forURL("jdbc:h2:mem:dicompropertiesdaotest;DB_CLOSE_DELAY=-1", driver = "org.h2.Driver")

  val metaDataDao = new DicomMetaDataDAO(H2Driver)
  val propertiesDao = new DicomPropertiesDAO(H2Driver)

  override def beforeEach() =
    db.withSession { implicit session =>
      metaDataDao.create
      propertiesDao.create
    }
  
  override def afterEach() =
    db.withSession { implicit session =>
      propertiesDao.drop
      metaDataDao.drop
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
  val imageFile1 = ImageFile(-1, FileName("file1"), SourceType.API, None)
  val imageFile2 = ImageFile(-1, FileName("file2"), SourceType.API, None)
  val imageFile3 = ImageFile(-1, FileName("file3"), SourceType.API, None)
  val imageFile4 = ImageFile(-1, FileName("file4"), SourceType.API, None)
  val imageFile5 = ImageFile(-1, FileName("file5"), SourceType.API, None)
  val imageFile6 = ImageFile(-1, FileName("file6"), SourceType.API, None)
  val imageFile7 = ImageFile(-1, FileName("file7"), SourceType.API, None)
  val imageFile8 = ImageFile(-1, FileName("file8"), SourceType.API, None)

  val pat1_copy = Patient(-1, PatientName("p1"), PatientID("s1"), PatientBirthDate("1000-01-01"), PatientSex("F"))
  val study3 = Study(-1, -1, StudyInstanceUID("stuid3"), StudyDescription("stdesc3"), StudyDate("19990103"), StudyID("stid3"), AccessionNumber("acc3"), PatientAge("13Y"))
  // the following series has a copied SeriesInstanceUID but unique parent study so should no be treated as copy
  val series1_copy = Series(-1, -1, -1, -1, SeriesInstanceUID("souid1"), SeriesDescription("sedesc1"), SeriesDate("19990101"), Modality("NM"), ProtocolName("prot1"), BodyPartExamined("bodypart1"))
  val image9 = Image(-1, -1, SOPInstanceUID("souid9"), ImageType("PRIMARY/RECON/TOMO"), InstanceNumber("1"))
  val imageFile9 = ImageFile(-1, FileName("file9"), SourceType.API, None)

  "The properties db" should "be emtpy before anything has been added" in {
    db.withSession { implicit session =>
      propertiesDao.imageFiles.size should be(0)
    }
  }

  it should "cascade delete linked image files when a patient is deleted" in {
    db.withSession { implicit session =>
      val dbPat = metaDataDao.insert(pat1)
      val dbStudy = metaDataDao.insert(study1.copy(patientId = dbPat.id))
      val dbEquipment = metaDataDao.insert(equipment1)
      val dbFor = metaDataDao.insert(for1)
      val dbSeries = metaDataDao.insert(series1.copy(studyId = dbStudy.id, equipmentId = dbEquipment.id, frameOfReferenceId = dbFor.id))
      val dbImage = metaDataDao.insert(image1.copy(seriesId = dbSeries.id))
      propertiesDao.insert(imageFile1.copy(id = dbImage.id))
      
      metaDataDao.patientByNameAndID(pat1).foreach(dbPat => {
        metaDataDao.deletePatient(dbPat.id)
        propertiesDao.imageFiles.size should be(0)
      })
    }
  }

  it should "not support adding an image file which links to a non-existing image" in {
    db.withSession { implicit session =>
      intercept[JdbcSQLException] {
        propertiesDao.insert(imageFile1)
      }
    }
  }

}
