package se.nimsa.sbx.storage

import scala.slick.driver.H2Driver
import scala.slick.jdbc.JdbcBackend.{ Database, Session }
import org.scalatest.FlatSpec
import org.scalatest.Matchers
import org.scalatest.BeforeAndAfterEach
import se.nimsa.sbx.dicom.DicomPropertyValue._
import se.nimsa.sbx.dicom.DicomHierarchy._
import org.h2.jdbc.JdbcSQLException
import StorageProtocol._
import se.nimsa.sbx.util.TestUtil._

class PropertiesDAOTest extends FlatSpec with Matchers with BeforeAndAfterEach {

  private val db = Database.forURL("jdbc:h2:mem:dicompropertiesdaotest;DB_CLOSE_DELAY=-1", driver = "org.h2.Driver")

  val metaDataDao = new MetaDataDAO(H2Driver)
  val propertiesDao = new PropertiesDAO(H2Driver)

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
      insertMetaDataAndProperties
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
      insertMetaDataAndProperties
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
      insertMetaDataAndProperties

      propertiesDao.flatSeries(0, 20, None, true, None, None, None).size should be(4)
      propertiesDao.flatSeries(0, 20, None, true, None, None, Some(1)).size should be(4)
      propertiesDao.flatSeries(0, 20, None, true, None, Some(SourceType.BOX), Some(-1)).size should be(1)
      propertiesDao.flatSeries(0, 20, None, true, None, Some(SourceType.BOX), Some(2)).size should be(0)

      // with filter
      propertiesDao.flatSeries(0, 20, None, true, Some("p1"), Some(SourceType.BOX), Some(-1)).size should be(1)
      propertiesDao.flatSeries(0, 20, None, true, Some("p1"), Some(SourceType.SCP), Some(-1)).size should be(1)
      propertiesDao.flatSeries(0, 20, None, true, Some("p2"), Some(SourceType.BOX), Some(-1)).size should be(0)

      // filter only
      propertiesDao.flatSeries(0, 20, None, true, Some("p1"), None, None).size should be(4)
    }
  }

  it should "support filtering patients by source" in {
    db.withSession { implicit session =>
      insertMetaDataAndProperties
      propertiesDao.patients(0, 20, None, true, None, None, None).size should be(1)
      propertiesDao.patients(0, 20, None, true, None, None, Some(1)).size should be(1)
      propertiesDao.patients(0, 20, None, true, None, Some(SourceType.BOX), Some(-1)).size should be(1)
      propertiesDao.patients(0, 20, None, true, None, Some(SourceType.BOX), Some(2)).size should be(0)

      // with filter
      propertiesDao.patients(0, 20, None, true, Some("p1"), Some(SourceType.BOX), Some(-1)).size should be(1)
      propertiesDao.patients(0, 20, None, true, Some("p2"), Some(SourceType.BOX), Some(-1)).size should be(0)

      // filter only
      propertiesDao.patients(0, 20, None, true, Some("p1"), None, None).size should be(1)
    }
  }

  it should "support filtering studies by source" in {
    db.withSession { implicit session =>
      insertMetaDataAndProperties
      propertiesDao.studiesForPatient(0, 20, 1, None, None).size should be(2)
      propertiesDao.studiesForPatient(0, 20, 1, None, Some(1)).size should be(2)
      propertiesDao.studiesForPatient(0, 20, 1, Some(SourceType.BOX), Some(-1)).size should be(1)
      propertiesDao.studiesForPatient(0, 20, 1, Some(SourceType.BOX), Some(2)).size should be(0)
    }
  }

  it should "support filtering series by source" in {
    db.withSession { implicit session =>
      insertMetaDataAndProperties
      propertiesDao.seriesForStudy(0, 20, 1, None, None).size should be(2)
      propertiesDao.seriesForStudy(0, 20, 1, None, Some(1)).size should be(2)
      propertiesDao.seriesForStudy(0, 20, 1, Some(SourceType.BOX), Some(-1)).size should be(1)
      propertiesDao.seriesForStudy(0, 20, 1, Some(SourceType.SCP), Some(-1)).size should be(1)
      propertiesDao.seriesForStudy(0, 20, 2, Some(SourceType.UNKNOWN), Some(-1)).size should be(1)
      propertiesDao.seriesForStudy(0, 20, 2, Some(SourceType.DIRECTORY), Some(-1)).size should be(1)
      propertiesDao.seriesForStudy(0, 20, 1, Some(SourceType.BOX), Some(2)).size should be(0)
      propertiesDao.seriesForStudy(0, 20, 1, Some(SourceType.SCP), Some(2)).size should be(0)
    }
  }

  def insertMetaDataAndProperties(implicit session: Session) = {
    val (dbPatient1, (dbStudy1, dbStudy2), (dbSeries1, dbSeries2, dbSeries3, dbSeries4), (dbEquipment1, dbEquipment2, dbEquipment3), (dbFor1, dbFor2), (dbImage1, dbImage2, dbImage3, dbImage4, dbImage5, dbImage6, dbImage7, dbImage8)) =
      insertMetaData(metaDataDao)
    insertProperties(propertiesDao, dbSeries1, dbSeries2, dbSeries3, dbSeries4, dbImage1, dbImage2, dbImage3, dbImage4, dbImage5, dbImage6, dbImage7, dbImage8)
  }
  
}
