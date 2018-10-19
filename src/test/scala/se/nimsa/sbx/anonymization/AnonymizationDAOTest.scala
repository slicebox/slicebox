package se.nimsa.sbx.anonymization

import akka.util.Timeout
import org.scalatest.{AsyncFlatSpec, BeforeAndAfterEach, Matchers}
import se.nimsa.dicom.data.{Tag, TagPath}
import se.nimsa.sbx.anonymization.AnonymizationProtocol._
import se.nimsa.sbx.metadata.MetaDataProtocol._
import se.nimsa.sbx.util.FutureUtil.await
import se.nimsa.sbx.util.TestUtil

import scala.concurrent.{ExecutionContext, ExecutionContextExecutor}
import scala.concurrent.duration.DurationInt

class AnonymizationDAOTest extends AsyncFlatSpec with Matchers with BeforeAndAfterEach {

  /*
  The ExecutionContext provided by ScalaTest only works inside tests, but here we have async stuff in beforeEach and
  afterEach so we must roll our own EC.
  */
  lazy val ec: ExecutionContextExecutor = ExecutionContext.global

  val dbConfig = TestUtil.createTestDb("anonymizationdaotest")
  val dao = new AnonymizationDAO(dbConfig)(ec)

  implicit val timeout: Timeout = Timeout(30.seconds)

  override def beforeEach(): Unit = await(dao.create())

  override def afterEach(): Unit = await(dao.drop())

  val key1 = AnonymizationKey(-1, 123456789, 1, "pn1", "anonPn1", "pid1", "anonPid1", "stuid1", "anonStuid1", "seuid1", "anonSeuid1", "sopuid1", "anonSopuid1")
  val key2 = AnonymizationKey(-1, 123456789, 2, "pn2", "anonPn2", "pid2", "anonPid2", "stuid2", "anonStuid2", "seuid2", "anonSeuid2", "sopuid2", "anonSopuid2")
  val key3 = AnonymizationKey(-1, 123456789, 3, "pn3", "anonPn3", "pid3", "anonPid3", "stuid3", "anonStuid3", "seuid3", "anonSeuid3", "sopuid3", "anonSopuid3")

  "The anonymization db" should "be emtpy before anything has been added" in {
    for {
      keys <- dao.listAnonymizationKeys
      images <- dao.listAnonymizationKeyValues
    } yield {
      keys shouldBe empty
      images shouldBe empty
    }
  }

  it should "cascade delete linked tag values when a key is deleted" in {
    for {
      key <- dao.insertAnonymizationKey(key1)
      _ <- dao.insertAnonymizationKeyValues(Seq(
        AnonymizationKeyValue(-1, key.id, TagPath.fromTag(Tag.PatientName), "pn1", "anonPn1"),
        AnonymizationKeyValue(-1, key.id, TagPath.fromTag(Tag.PatientID), "pid1", "anonPid1")))
      k1 <- dao.listAnonymizationKeys
      v1 <- dao.listAnonymizationKeyValues
      _ <- dao.deleteAnonymizationKey(key.id)
      k2 <- dao.listAnonymizationKeys
      v2 <- dao.listAnonymizationKeyValues
    } yield {
      k1 should have length 1
      v1 should have length 2
      k2 shouldBe empty
      v2 shouldBe empty
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

  it should "remove all empty anonymization keys containing a certain image id but not other empty or non-empty keys" in {
    for {
      dbKey1 <- dao.insertAnonymizationKey(key1)
      dbKey2 <- dao.insertAnonymizationKey(key2)
      dbKey3 <- dao.insertAnonymizationKey(key3)

      _ <- dao.insertAnonymizationKeyValues(Seq(
        AnonymizationKeyValue(-1, dbKey1.id, TagPath.fromTag(Tag.PatientName), "pn1" ,"anonPn1"),
        AnonymizationKeyValue(-1, dbKey2.id, TagPath.fromTag(Tag.PatientName), "pn2" ,"anonPn2"),
        AnonymizationKeyValue(-1, dbKey3.id, TagPath.fromTag(Tag.PatientName), "pn3" ,"anonPn3")))

      k1 <- dao.listAnonymizationKeys
      v1 <- dao.listAnonymizationKeyValues

      _ <- dao.deleteAnonymizationKeysForImageIds(Seq(2,3))

      pk <- dao.anonymizationKeyForId(dbKey1.id)
      k2 <- dao.anonymizationKeyForId(dbKey2.id)
      k3 <- dao.anonymizationKeyForId(dbKey3.id)
    } yield {
      k1 should have length 3
      v1 should have length 3
      pk shouldBe Some(dbKey1)
      k2 shouldBe None
      k3 shouldBe None
    }
  }
}
