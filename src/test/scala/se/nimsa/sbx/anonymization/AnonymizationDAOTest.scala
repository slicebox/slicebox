package se.nimsa.sbx.anonymization

import akka.util.Timeout
import org.scalatest.{AsyncFlatSpec, BeforeAndAfterEach, Matchers}
import se.nimsa.sbx.anonymization.AnonymizationProtocol._
import se.nimsa.sbx.metadata.MetaDataProtocol._
import se.nimsa.sbx.util.FutureUtil.await
import se.nimsa.sbx.util.TestUtil

import scala.concurrent.ExecutionContext
import scala.concurrent.duration.DurationInt

class AnonymizationDAOTest extends AsyncFlatSpec with Matchers with BeforeAndAfterEach {

  /*
  The ExecutionContext provided by ScalaTest only works inside tests, but here we have async stuff in beforeEach and
  afterEach so we must roll our own EC.
  */
  lazy val ec = ExecutionContext.global

  val dbConfig = TestUtil.createTestDb("anonymizationdaotest")
  val dao = new AnonymizationDAO(dbConfig)(ec)

  implicit val timeout = Timeout(30.seconds)

  override def beforeEach() = await(dao.create())

  override def afterEach() = await(dao.drop())

  val key1 = AnonymizationKey(-1, 123456789, "pn1", "anonPn1", "pid1", "anonPid1", "pBD1", "stuid1", "anonStuid1", "", "", "", "", "", "", "", "", "")
  val key2 = AnonymizationKey(-1, 123456789, "pn2", "anonPn2", "pid2", "anonPid2", "pBD2", "stuid2", "anonStuid2", "", "", "", "", "", "", "", "", "")
  val key3 = AnonymizationKey(-1, 123456789, "pn3", "anonPn3", "pid3", "anonPid3", "pBD3", "stuid3", "anonStuid3", "", "", "", "", "", "", "", "", "")

  "The anonymization db" should "be emtpy before anything has been added" in {
    for {
      keys <- dao.listAnonymizationKeys
      images <- dao.listAnonymizationKeyImages
    } yield {
      keys shouldBe empty
      images shouldBe empty
    }
  }

  it should "cascade delete linked images when a key is deleted" in {
    for {
      key <- dao.insertAnonymizationKey(key1)
      _ <- dao.insertAnonymizationKeyImage(AnonymizationKeyImage(-1, key.id, 55))
      k1 <- dao.listAnonymizationKeys
      i1 <- dao.listAnonymizationKeyImages
      _ <- dao.removeAnonymizationKey(key.id)
      k2 <- dao.listAnonymizationKeys
      i2 <- dao.listAnonymizationKeyImages
    } yield {
      k1 should have length 1
      i1 should have length 1
      k2 shouldBe empty
      i2 shouldBe empty
    }
  }

  it should "create valid SQL queries (no SQL exceptions) with all combinations of input arguments when querying anonymization keys" in {
    val qp = Seq(QueryProperty("anonStudyInstanceUID", QueryOperator.EQUALS, "123"))
    for {
      _ <- dao.queryAnonymizationKeys(0, 10, None, orderAscending = false, qp)
      _ <- dao.queryAnonymizationKeys(0, 10, Some("patientName"), orderAscending = false, qp)
    } yield succeed
  }

  it should "throw IllegalArgumentException when querying anonymization keys for properties (columns) that does not exist" in {
    val qp = Seq(QueryProperty("misspelled property", QueryOperator.EQUALS, "value"))
    recoverToSucceededIf[IllegalArgumentException] {
      dao.queryAnonymizationKeys(0, 10, None, orderAscending = false, qp)
    }
    recoverToSucceededIf[IllegalArgumentException] {
      dao.queryAnonymizationKeys(0, 10, Some("patientName"), orderAscending = false, qp)
    }
  }

  it should "return the correct number of patients for anonymization key queries" in {
    for {
      _ <- dao.insertAnonymizationKey(key1)
      _ <- dao.insertAnonymizationKey(key2)
      _ <- dao.insertAnonymizationKey(key3)

      // Test equal query
      k1 <- dao.queryAnonymizationKeys(0, 10, None, orderAscending = true, Seq(QueryProperty("patientName", QueryOperator.EQUALS, "pn1")))
      k2 <- dao.queryAnonymizationKeys(0, 10, None, orderAscending = true, Seq(QueryProperty("patientName", QueryOperator.EQUALS, "pn2")))
      k3 <- dao.queryAnonymizationKeys(0, 10, None, orderAscending = true, Seq(QueryProperty("patientName", QueryOperator.EQUALS, "pn3")))
      k4 <- dao.queryAnonymizationKeys(0, 10, None, orderAscending = true, Seq(QueryProperty("patientName", QueryOperator.EQUALS, "unknown")))

      // Test like query
      k5 <- dao.queryAnonymizationKeys(0, 10, None, orderAscending = true, Seq(QueryProperty("patientName", QueryOperator.LIKE, "pn")))
      k6 <- dao.queryAnonymizationKeys(0, 10, None, orderAscending = true, Seq(QueryProperty("patientName", QueryOperator.LIKE, "unknown")))

      // Test paging
      k7 <- dao.queryAnonymizationKeys(1, 1, None, orderAscending = true, Seq(QueryProperty("patientName", QueryOperator.LIKE, "pn")))

      // Test sorting
      k8 <- dao.queryAnonymizationKeys(0, 10, Some("patientName"), orderAscending = true, Seq(QueryProperty("patientName", QueryOperator.LIKE, "pn")))
        .map(_.map(_.patientName))
      k9 <- dao.queryAnonymizationKeys(0, 10, Some("patientName"), orderAscending = false, Seq(QueryProperty("patientName", QueryOperator.LIKE, "pn")))
        .map(_.map(_.patientName))
    } yield {
      k1 should have length 1
      k2 should have length 1
      k3 should have length 1
      k4 shouldBe empty
      k5 should have length 3
      k6 shouldBe empty
      k7 should have length 1
      k8 shouldBe List("pn1", "pn2", "pn3")
      k9 shouldBe List("pn3", "pn2", "pn1")
    }
  }

  it should "not remove empty anonymization keys when there are no associated images left and purging of empty keys is off" in {
    val imageId = 55
    for {
      key <- dao.insertAnonymizationKey(key1)
      _ <- dao.insertAnonymizationKeyImage(AnonymizationKeyImage(-1, key.id, imageId))

      k1 <- dao.listAnonymizationKeys
      k2 <- dao.listAnonymizationKeyImages

      _ <- dao.removeAnonymizationKeyImagesForImageId(Seq(imageId), purgeEmptyAnonymizationKeys = false)

      k3 <- dao.listAnonymizationKeys
      k4 <- dao.listAnonymizationKeyImages
    } yield {
      k1 should have length 1
      k2 should have length 1
      k3 should have length 1
      k4 shouldBe empty
    }
  }

  it should "remove empty anonymization keys when there are no associated images left and purging of empty keys is on" in {
    val imageId = 55
    for {
      key <- dao.insertAnonymizationKey(key1)
      _ <- dao.insertAnonymizationKeyImage(AnonymizationKeyImage(-1, key.id, imageId))

      k1 <- dao.listAnonymizationKeys
      k2 <- dao.listAnonymizationKeyImages

      _ <- dao.removeAnonymizationKeyImagesForImageId(Seq(imageId), purgeEmptyAnonymizationKeys = true)

      k3 <- dao.listAnonymizationKeys
      k4 <- dao.listAnonymizationKeyImages
    } yield {
      k1 should have length 1
      k2 should have length 1
      k3 shouldBe empty
      k4 shouldBe empty
    }
  }

  it should "remove all empty anonymization keys containing a certain image id but not other empty or non-empty keys" in {
    val imageId = 55
    for {
      dbKey1 <- dao.insertAnonymizationKey(key1)
      dbKey2 <- dao.insertAnonymizationKey(key2)
      dbKey3 <- dao.insertAnonymizationKey(key3)

      _ <- dao.insertAnonymizationKeyImage(AnonymizationKeyImage(-1, dbKey1.id, imageId))

      _ <- dao.insertAnonymizationKeyImage(AnonymizationKeyImage(-1, dbKey2.id, imageId))
      _ <- dao.insertAnonymizationKeyImage(AnonymizationKeyImage(-1, dbKey2.id, imageId + 1))

      k1 <- dao.listAnonymizationKeys
      k2 <- dao.listAnonymizationKeyImages

      _ <- dao.removeAnonymizationKeyImagesForImageId(Seq(imageId), purgeEmptyAnonymizationKeys = true)

      pk <- dao.anonymizationKeyForId(dbKey1.id)
      k3 <- dao.anonymizationKeyForId(dbKey2.id)
      k4 <- dao.anonymizationKeyForId(dbKey3.id)
    } yield {
      k1 should have length 3
      k2 should have length 3
      pk shouldBe None // purged
      k3 shouldBe Some(dbKey2) // not yet empty
      k4 shouldBe Some(dbKey3) // empty but does not contain image id
    }
  }
}
