package se.nimsa.sbx.anonymization

import scala.slick.driver.H2Driver
import scala.slick.jdbc.JdbcBackend.{ Database, Session }
import org.scalatest.FlatSpec
import org.scalatest.Matchers
import org.scalatest.BeforeAndAfterEach
import se.nimsa.sbx.dicom.DicomPropertyValue._
import se.nimsa.sbx.dicom.DicomHierarchy._
import org.h2.jdbc.JdbcSQLException
import se.nimsa.sbx.util.TestUtil._
import se.nimsa.sbx.seriestype.SeriesTypeDAO
import se.nimsa.sbx.seriestype.SeriesTypeProtocol.SeriesType
import se.nimsa.sbx.seriestype.SeriesTypeProtocol.SeriesSeriesType
import se.nimsa.sbx.app.GeneralProtocol._
import AnonymizationProtocol._
import se.nimsa.sbx.metadata.MetaDataProtocol._

class AnonymizationDAOTest extends FlatSpec with Matchers with BeforeAndAfterEach {

  private val db = Database.forURL("jdbc:h2:mem:anonymizationdaotest;DB_CLOSE_DELAY=-1", driver = "org.h2.Driver")

  val dao = new AnonymizationDAO(H2Driver)

  override def beforeEach() =
    db.withSession { implicit session =>
      dao.create
    }

  override def afterEach() =
    db.withSession { implicit session =>
      dao.drop
    }

  val key1 = AnonymizationKey(-1, 123456789, "pn1", "anonPn1", "pid1", "anonPid1", "pBD1", "stuid1", "anonStuid1", "", "", "", "", "", "", "", "", "")
  val key2 = AnonymizationKey(-1, 123456789, "pn2", "anonPn2", "pid2", "anonPid2", "pBD2", "stuid2", "anonStuid2", "", "", "", "", "", "", "", "", "")
  val key3 = AnonymizationKey(-1, 123456789, "pn3", "anonPn3", "pid3", "anonPid3", "pBD3", "stuid3", "anonStuid3", "", "", "", "", "", "", "", "", "")

  "The anonymization db" should "be emtpy before anything has been added" in {
    db.withSession { implicit session =>
      dao.listAnonymizationKeys shouldBe empty
      dao.listAnonymizationKeyImages shouldBe empty
    }
  }

  it should "cascade delete linked images when a key is deleted" in {
    db.withSession { implicit session =>
      val key = dao.insertAnonymizationKey(key1)
      dao.insertAnonymizationKeyImage(AnonymizationKeyImage(-1, key.id, 55))

      dao.listAnonymizationKeys should have length 1
      dao.listAnonymizationKeyImages should have length 1

      dao.removeAnonymizationKey(key.id)

      dao.listAnonymizationKeys shouldBe empty
      dao.listAnonymizationKeyImages shouldBe empty
    }
  }

  it should "create valid SQL queries (no SQL exceptions) with all combinations of input arguments when querying anonymization keys" in {
    db.withSession { implicit session =>
      val qo = Some(QueryOrder("patientName", true))
      val qp = Seq(QueryProperty("anonStudyInstanceUID", QueryOperator.EQUALS, "123"))
      dao.queryAnonymizationKeys(0, 10, None, false, qp)
      dao.queryAnonymizationKeys(0, 10, Some("patientName"), false, qp)
    }
  }

  it should "throw IllegalArgumentException when querying anonymization keys for properties (columns) that does not exist" in {
    db.withSession { implicit session =>
      val qp = Seq(QueryProperty("misspelled property", QueryOperator.EQUALS, "value"))
      intercept[IllegalArgumentException] {
        dao.queryAnonymizationKeys(0, 10, None, false, qp)
      }
      intercept[IllegalArgumentException] {
        dao.queryAnonymizationKeys(0, 10, Some("patientName"), false, qp)
      }
    }
  }

  it should "return the correct number of patients for anonymization key queries" in {
    db.withSession { implicit session =>
      val dbKey1 = dao.insertAnonymizationKey(key1)
      val dbKey2 = dao.insertAnonymizationKey(key2)
      val dbKey3 = dao.insertAnonymizationKey(key3)
      
      // Test equal query
      dao.queryAnonymizationKeys(0, 10, None, true, Seq(QueryProperty("patientName", QueryOperator.EQUALS, "pn1"))) should have length 1
      dao.queryAnonymizationKeys(0, 10, None, true, Seq(QueryProperty("patientName", QueryOperator.EQUALS, "pn2"))) should have length 1
      dao.queryAnonymizationKeys(0, 10, None, true, Seq(QueryProperty("patientName", QueryOperator.EQUALS, "pn3"))) should have length 1
      dao.queryAnonymizationKeys(0, 10, None, true, Seq(QueryProperty("patientName", QueryOperator.EQUALS, "unknown"))) shouldBe empty
      
      // Test like query
      dao.queryAnonymizationKeys(0, 10, None, true, Seq(QueryProperty("patientName", QueryOperator.LIKE, "pn"))) should have length 3
      dao.queryAnonymizationKeys(0, 10, None, true, Seq(QueryProperty("patientName", QueryOperator.LIKE, "unknown"))) shouldBe empty
      
      // Test paging
      dao.queryAnonymizationKeys(1, 1, None, true, Seq(QueryProperty("patientName", QueryOperator.LIKE, "pn"))) should have length 1
      
      // Test sorting
      dao.queryAnonymizationKeys(0, 10, Some("patientName"), true, Seq(QueryProperty("patientName", QueryOperator.LIKE, "pn")))
          .map(_.patientName) shouldBe List("pn1", "pn2", "pn3")
      dao.queryAnonymizationKeys(0, 10, Some("patientName"), false, Seq(QueryProperty("patientName", QueryOperator.LIKE, "pn")))
          .map(_.patientName) shouldBe List("pn3", "pn2", "pn1")
    }
  }

  it should "not remove empty anonymization keys when there are no associated images left and purging of empty keys is off" in {
    db.withSession { implicit session =>
      val imageId = 55
      val key = dao.insertAnonymizationKey(key1)
      dao.insertAnonymizationKeyImage(AnonymizationKeyImage(-1, key.id, imageId))

      dao.listAnonymizationKeys should have length 1
      dao.listAnonymizationKeyImages should have length 1

      dao.removeAnonymizationKeyImagesForImageId(imageId, purgeEmptyAnonymizationKeys = false)

      dao.listAnonymizationKeys should have length 1
      dao.listAnonymizationKeyImages shouldBe empty
    }
  }

  it should "remove empty anonymization keys when there are no associated images left and purging of empty keys is on" in {
    db.withSession { implicit session =>
      val imageId = 55
      val key = dao.insertAnonymizationKey(key1)
      dao.insertAnonymizationKeyImage(AnonymizationKeyImage(-1, key.id, imageId))

      dao.listAnonymizationKeys should have length 1
      dao.listAnonymizationKeyImages should have length 1

      dao.removeAnonymizationKeyImagesForImageId(imageId, purgeEmptyAnonymizationKeys = true)

      dao.listAnonymizationKeys shouldBe empty
      dao.listAnonymizationKeyImages shouldBe empty
    }
  }

  it should "remove all empty anonymization keys containing a certain image id but not other empty or non-empty keys" in {
    db.withSession { implicit session =>
      val imageId = 55
      val dbKey1 = dao.insertAnonymizationKey(key1)
      val dbKey2 = dao.insertAnonymizationKey(key2)
      val dbKey3 = dao.insertAnonymizationKey(key3)
      dao.insertAnonymizationKeyImage(AnonymizationKeyImage(-1, dbKey1.id, imageId))

      dao.insertAnonymizationKeyImage(AnonymizationKeyImage(-1, dbKey2.id, imageId))
      dao.insertAnonymizationKeyImage(AnonymizationKeyImage(-1, dbKey2.id, imageId + 1))

      dao.listAnonymizationKeys should have length 3
      dao.listAnonymizationKeyImages should have length 3

      dao.removeAnonymizationKeyImagesForImageId(imageId, purgeEmptyAnonymizationKeys = true)

      dao.anonymizationKeyForId(dbKey1.id) shouldBe None // purged
      dao.anonymizationKeyForId(dbKey2.id) shouldBe Some(dbKey2) // not yet empty
      dao.anonymizationKeyForId(dbKey3.id) shouldBe Some(dbKey3) // empty but does not contain image id
    }
  }
}
