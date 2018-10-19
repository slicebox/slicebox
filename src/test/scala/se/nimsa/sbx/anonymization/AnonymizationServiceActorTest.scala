package se.nimsa.sbx.anonymization

import akka.actor.{ActorRef, ActorSystem, Props}
import akka.stream.ActorMaterializer
import akka.testkit.{ImplicitSender, TestKit}
import akka.util.Timeout
import org.scalatest._
import se.nimsa.dicom.data.{Tag, TagPath}
import se.nimsa.sbx.anonymization.AnonymizationProtocol._
import se.nimsa.sbx.app.GeneralProtocol.ImagesDeleted
import se.nimsa.sbx.dicom.DicomHierarchy.DicomHierarchyLevel
import se.nimsa.sbx.util.FutureUtil.await
import se.nimsa.sbx.util.TestUtil

import scala.concurrent.duration.DurationInt
import scala.concurrent.{ExecutionContextExecutor, Future}

class AnonymizationServiceActorTest(_system: ActorSystem) extends TestKit(_system) with ImplicitSender
  with WordSpecLike with Matchers with BeforeAndAfterAll with BeforeAndAfterEach {

  def this() = this(ActorSystem("AnonymizationServiceActorTestSystem"))

  implicit val ec: ExecutionContextExecutor = system.dispatcher
  implicit val timeout: Timeout = Timeout(30.seconds)
  implicit val materializer: ActorMaterializer = ActorMaterializer()

  val dbConfig = TestUtil.createTestDb("anonymizationserviceactortest")

  val anonymizationDao = new AnonymizationDAO(dbConfig)

  await(anonymizationDao.create())

  override def afterEach(): Unit = {
    await(Future.sequence(Seq(
      anonymizationDao.clear()
    )))
  }

  val key1 = AnonymizationKey(-1, 123456789, 1, "pn1", "anonPn1", "pid1", "anonPid1", "stuid1", "anonStuid1", "seuid1", "anonSeuid1", "sopuid1", "anonSopuid1")
  val key2 = AnonymizationKey(-1, 123456789, 2, "pn2", "anonPn2", "pid2", "anonPid2", "stuid2", "anonStuid2", "seuid2", "anonSeuid2", "sopuid2", "anonSopuid2")
  val key3 = AnonymizationKey(-1, 123456789, 3, "pn3", "anonPn3", "pid3", "anonPid3", "stuid3", "anonStuid3", "seuid3", "anonSeuid3", "sopuid3", "anonSopuid3")

  val anonymizationService: ActorRef = system.actorOf(Props(new AnonymizationServiceActor(anonymizationDao, purgeEmptyAnonymizationKeys = false)), name = "AnonymizationService")

  override def afterAll: Unit = TestKit.shutdownActorSystem(system)

  "An AnonymizationServiceActor" should {

    "not remove anonymization keys when corresponding images are deleted and purging is off" in {
      await(anonymizationDao.insertAnonymizationKey(key1))
      await(anonymizationDao.insertAnonymizationKey(key2))
      await(anonymizationDao.insertAnonymizationKey(key3))

      await(anonymizationDao.listAnonymizationKeys) should have length 3

      anonymizationService ! ImagesDeleted(Seq(2, 3))

      expectNoMessage()

      await(anonymizationDao.listAnonymizationKeys) should have length 3
    }

    "remove anonymization keys when corresponding images are deleted and purging is on" in {
      val anonymizationService: ActorRef = system.actorOf(Props(new AnonymizationServiceActor(anonymizationDao, purgeEmptyAnonymizationKeys = true)), name = "AnonymizationServicePurge")

      await(anonymizationDao.insertAnonymizationKey(key1))
      await(anonymizationDao.insertAnonymizationKey(key2))
      await(anonymizationDao.insertAnonymizationKey(key3))

      await(anonymizationDao.listAnonymizationKeys) should have length 3

      anonymizationService ! ImagesDeleted(Seq(2, 3))

      expectNoMessage()

      await(anonymizationDao.listAnonymizationKeys) should have length 1
    }

    "yield patient, study and series information depending on completeness of match when querying for anonymization keys" in {
      val key = await(anonymizationDao.insertAnonymizationKey(key1))
      await(anonymizationDao.insertAnonymizationKeyValues(Seq(
        AnonymizationKeyValue(-1, key.id, TagPath.fromTag(Tag.PatientName), key.patientName, key.anonPatientName),
          AnonymizationKeyValue(-1, key.id, TagPath.fromTag(Tag.PatientID), key.patientID, key.anonPatientID),
          AnonymizationKeyValue(-1, key.id, TagPath.fromTag(Tag.StudyInstanceUID), key.studyInstanceUID, key.anonStudyInstanceUID),
          AnonymizationKeyValue(-1, key.id, TagPath.fromTag(Tag.PatientName), key.seriesInstanceUID, key.anonSeriesInstanceUID),
          AnonymizationKeyValue(-1, key.id, TagPath.fromTag(Tag.PatientName), key.sopInstanceUID, key.anonSOPInstanceUID))))

      // image match
      anonymizationService ! QueryReverseAnonymizationKeyValues(key.anonPatientName, key.anonPatientID, key.anonStudyInstanceUID, key.anonSeriesInstanceUID, key.anonSOPInstanceUID)
      expectMsgPF() {
        case r: AnonymizationKeyOpResult =>
          r.matchLevel shouldBe DicomHierarchyLevel.IMAGE
          r.values should have length 5
      }

      // series match
      anonymizationService ! QueryReverseAnonymizationKeyValues(key.anonPatientName, key.anonPatientID, key.anonStudyInstanceUID, key.anonSeriesInstanceUID, "")
      expectMsgPF() {
        case r: AnonymizationKeyOpResult =>
          r.matchLevel shouldBe DicomHierarchyLevel.SERIES
          r.values should have length 5
      }

      // study match
      anonymizationService ! QueryReverseAnonymizationKeyValues(key.anonPatientName, key.anonPatientID, key.anonStudyInstanceUID, "", "")
      expectMsgPF() {
        case r: AnonymizationKeyOpResult =>
          r.matchLevel shouldBe DicomHierarchyLevel.STUDY
          r.values should have length 5
      }

      // patient match
      anonymizationService ! QueryReverseAnonymizationKeyValues(key.anonPatientName, key.anonPatientID, "", "", "")
      expectMsgPF() {
        case r: AnonymizationKeyOpResult =>
          r.matchLevel shouldBe DicomHierarchyLevel.PATIENT
          r.values should have length 5
      }

      // no match
      anonymizationService ! QueryReverseAnonymizationKeyValues("", "", "", "", "")
      expectMsgPF() {
        case r: AnonymizationKeyOpResult =>
          r.isEmpty shouldBe true
          r.matchLevel shouldBe DicomHierarchyLevel.PATIENT
          r.values shouldBe empty
      }
    }

    "insert anonymization key into database" in {
      val imageId = 42

      anonymizationService ! InsertAnonymizationKeyValues(imageId, Set(AnonymizationKeyValueData(DicomHierarchyLevel.PATIENT, TagPath.fromTag(Tag.PatientName), "name", "anon name")))
      expectMsgPF() {
        case r: AnonymizationKeyOpResult =>
          r.anonymizationKeyMaybe shouldBe defined
          r.anonymizationKeyMaybe.get.patientName shouldBe "name"
          r.anonymizationKeyMaybe.get.anonPatientName shouldBe "anon name"
          r.values should have size 1
          r.values.head.tagPath shouldBe TagPath.fromTag(Tag.PatientName)
      }
    }

    "harmonize anonymous information based on existing keys when inserting key into database" in {
      val imageId = 42

      anonymizationService ! InsertAnonymizationKeyValues(imageId, Set(
        AnonymizationKeyValueData(DicomHierarchyLevel.PATIENT, TagPath.fromTag(Tag.PatientName), "name", "anon name"),
        AnonymizationKeyValueData(DicomHierarchyLevel.PATIENT, TagPath.fromTag(Tag.PatientID), "id", "anon id")
      ))
      expectMsgType[AnonymizationKeyOpResult]

      anonymizationService ! InsertAnonymizationKeyValues(imageId + 1, Set(
        AnonymizationKeyValueData(DicomHierarchyLevel.PATIENT, TagPath.fromTag(Tag.PatientName), "name", "anon name"),
        AnonymizationKeyValueData(DicomHierarchyLevel.PATIENT, TagPath.fromTag(Tag.PatientID), "id", "another anon id")
      ))
      expectMsgPF() {
        case r: AnonymizationKeyOpResult => r.anonymizationKeyMaybe.get.anonPatientID shouldBe "anon id"
      }
    }

  }
}