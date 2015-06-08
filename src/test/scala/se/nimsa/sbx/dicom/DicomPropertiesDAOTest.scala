package se.nimsa.sbx.dicom

import scala.slick.driver.H2Driver
import scala.slick.jdbc.JdbcBackend.{ Database, Session }
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

  "The properties db" should "be emtpy before anything has been added" in {
    db.withSession { implicit session =>
      propertiesDao.imageFiles.size should be(0)
    }
  }

  it should "cascade delete linked image files and series sources when a patient is deleted" in {
    db.withSession { implicit session =>
      insertMetaData
      propertiesDao.imageFiles.size should be(8)
      propertiesDao.seriesSources.size should be(4)
      metaDataDao.patientById(1).foreach(dbPat => {
        metaDataDao.deletePatient(dbPat.id)
        propertiesDao.imageFiles.size should be(0)
        propertiesDao.seriesSources.size should be(0)
      })
    }
  }

  it should "not support adding an image file which links to a non-existing image" in {
    db.withSession { implicit session =>
      intercept[JdbcSQLException] {
        propertiesDao.insertImageFile(ImageFile(-1, FileName("file1"), SourceType.USER, 1))
      }
    }
  }

  it should "support filtering image files by source" in {
    db.withSession { implicit session =>
      insertMetaData
      propertiesDao.imageFilesForSource(SourceType.SCP, 1).length should be(2)
      propertiesDao.imageFilesForSource(SourceType.BOX, 1).length should be(2)
      propertiesDao.imageFilesForSource(SourceType.USER, 1).length should be(2)
      propertiesDao.imageFilesForSource(SourceType.DIRECTORY, 1).length should be(1)
      propertiesDao.imageFilesForSource(SourceType.UNKNOWN, -1).length should be(1)
      propertiesDao.imageFilesForSource(SourceType.SCP, 2).length should be(0)
      propertiesDao.imageFilesForSource(SourceType.BOX, 2).length should be(0)
      propertiesDao.imageFilesForSource(SourceType.USER, 2).length should be(0)
      propertiesDao.imageFilesForSource(SourceType.DIRECTORY, 2).length should be(0)
      propertiesDao.imageFilesForSource(SourceType.UNKNOWN, 1).length should be(0)
    }
  }

  it should "support filtering flat series by source" in {
    db.withSession { implicit session =>
      insertMetaData
      propertiesDao.flatSeries(0, 20, None, true, None, None, None).size should be (4)
      propertiesDao.flatSeries(0, 20, None, true, None, None, Some(1)).size should be (4)
      propertiesDao.flatSeries(0, 20, None, true, None, Some(SourceType.BOX), Some(-1)).size should be (2)
      propertiesDao.flatSeries(0, 20, None, true, None, Some(SourceType.BOX), Some(2)).size should be (0)
      
      // with filter
      propertiesDao.flatSeries(0, 20, None, true, Some("p1"), Some(SourceType.BOX), Some(-1)).size should be (2)
      propertiesDao.flatSeries(0, 20, None, true, Some("p2"), Some(SourceType.BOX), Some(-1)).size should be (0)
      
      // filter only
      propertiesDao.flatSeries(0, 20, None, true, Some("p1"), None, None).size should be (4)
    }
  }

  it should "support filtering patients by source" in {
    db.withSession { implicit session =>
      insertMetaData
      propertiesDao.patients(0, 20, None, true, None, None, None).size should be (1)
      propertiesDao.patients(0, 20, None, true, None, None, Some(1)).size should be (1)
      propertiesDao.patients(0, 20, None, true, None, Some(SourceType.BOX), Some(-1)).size should be (1)
      propertiesDao.patients(0, 20, None, true, None, Some(SourceType.BOX), Some(2)).size should be (0)
      
      // with filter
      propertiesDao.patients(0, 20, None, true, Some("p1"), Some(SourceType.BOX), Some(-1)).size should be (1)
      propertiesDao.patients(0, 20, None, true, Some("p2"), Some(SourceType.BOX), Some(-1)).size should be (0)
      
      // filter only
      propertiesDao.patients(0, 20, None, true, Some("p1"), None, None).size should be (1)
    }
  }

  def insertMetaData(implicit session: Session) = {
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
    val imageFile1 = ImageFile(-1, FileName("file1"), SourceType.USER, 1)
    val imageFile2 = ImageFile(-1, FileName("file2"), SourceType.USER, 1)
    val imageFile3 = ImageFile(-1, FileName("file3"), SourceType.BOX, 1)
    val imageFile4 = ImageFile(-1, FileName("file4"), SourceType.BOX, 1)
    val imageFile5 = ImageFile(-1, FileName("file5"), SourceType.DIRECTORY, 1)
    val imageFile6 = ImageFile(-1, FileName("file6"), SourceType.UNKNOWN, -1)
    val imageFile7 = ImageFile(-1, FileName("file7"), SourceType.SCP, 1)
    val imageFile8 = ImageFile(-1, FileName("file8"), SourceType.SCP, 1)
    val seriesSource1 = SeriesSource(-1, SourceType.BOX, -1)
    val seriesSource2 = SeriesSource(-1, SourceType.BOX, -1)
    val seriesSource3 = SeriesSource(-1, SourceType.UNKNOWN, -1)
    val seriesSource4 = SeriesSource(-1, SourceType.DIRECTORY, -1)

    val dbPatient1 = metaDataDao.insert(pat1)
    val dbStudy1 = metaDataDao.insert(study1.copy(patientId = dbPatient1.id))
    val dbStudy2 = metaDataDao.insert(study2.copy(patientId = dbPatient1.id))
    val dbEquipment1 = metaDataDao.insert(equipment1)
    val dbEquipment2 = metaDataDao.insert(equipment2)
    val dbEquipment3 = metaDataDao.insert(equipment3)
    val dbFor1 = metaDataDao.insert(for1)
    val dbFor2 = metaDataDao.insert(for2)
    val dbSeries1 = metaDataDao.insert(series1.copy(studyId = dbStudy1.id, equipmentId = dbEquipment1.id, frameOfReferenceId = dbFor1.id))
    val dbSeries2 = metaDataDao.insert(series2.copy(studyId = dbStudy1.id, equipmentId = dbEquipment1.id, frameOfReferenceId = dbFor2.id))
    val dbSeries3 = metaDataDao.insert(series3.copy(studyId = dbStudy2.id, equipmentId = dbEquipment2.id, frameOfReferenceId = dbFor1.id))
    val dbSeries4 = metaDataDao.insert(series4.copy(studyId = dbStudy2.id, equipmentId = dbEquipment3.id, frameOfReferenceId = dbFor2.id))
    val dbImage1 = metaDataDao.insert(image1.copy(seriesId = dbSeries1.id))
    val dbImage2 = metaDataDao.insert(image2.copy(seriesId = dbSeries1.id))
    val dbImage3 = metaDataDao.insert(image3.copy(seriesId = dbSeries2.id))
    val dbImage4 = metaDataDao.insert(image4.copy(seriesId = dbSeries2.id))
    val dbImage5 = metaDataDao.insert(image5.copy(seriesId = dbSeries3.id))
    val dbImage6 = metaDataDao.insert(image6.copy(seriesId = dbSeries3.id))
    val dbImage7 = metaDataDao.insert(image7.copy(seriesId = dbSeries4.id))
    val dbImage8 = metaDataDao.insert(image8.copy(seriesId = dbSeries4.id))
    val dbImageFile1 = propertiesDao.insertImageFile(imageFile1.copy(id = dbImage1.id))
    val dbImageFile2 = propertiesDao.insertImageFile(imageFile2.copy(id = dbImage2.id))
    val dbImageFile3 = propertiesDao.insertImageFile(imageFile3.copy(id = dbImage3.id))
    val dbImageFile4 = propertiesDao.insertImageFile(imageFile4.copy(id = dbImage4.id))
    val dbImageFile5 = propertiesDao.insertImageFile(imageFile5.copy(id = dbImage5.id))
    val dbImageFile6 = propertiesDao.insertImageFile(imageFile6.copy(id = dbImage6.id))
    val dbImageFile7 = propertiesDao.insertImageFile(imageFile7.copy(id = dbImage7.id))
    val dbImageFile8 = propertiesDao.insertImageFile(imageFile8.copy(id = dbImage8.id))
    val dbSeriesSource1 = propertiesDao.insertSeriesSource(seriesSource1.copy(id = dbSeries1.id))
    val dbSeriesSource2 = propertiesDao.insertSeriesSource(seriesSource2.copy(id = dbSeries2.id))
    val dbSeriesSource3 = propertiesDao.insertSeriesSource(seriesSource3.copy(id = dbSeries3.id))
    val dbSeriesSource4 = propertiesDao.insertSeriesSource(seriesSource4.copy(id = dbSeries4.id))
  }
}
