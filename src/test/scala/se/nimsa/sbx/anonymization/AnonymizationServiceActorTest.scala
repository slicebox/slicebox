package se.nimsa.sbx.anonymization

import akka.actor.{ActorSystem, Props}
import akka.testkit.{ImplicitSender, TestKit}
import akka.util.Timeout
import org.dcm4che3.data.{Attributes, Tag, VR}
import org.scalatest.{BeforeAndAfterAll, BeforeAndAfterEach, Matchers, WordSpecLike}
import se.nimsa.sbx.anonymization.AnonymizationProtocol.{Anonymize, ReverseAnonymization, TagValue}
import se.nimsa.sbx.anonymization.AnonymizationUtil.anonymizeAttributes
import se.nimsa.sbx.dicom.DicomUtil._
import se.nimsa.sbx.metadata.MetaDataDAO
import se.nimsa.sbx.util.FutureUtil.await
import se.nimsa.sbx.util.TestUtil
import se.nimsa.sbx.util.TestUtil.{createAnonymizationKey, createDicomData}

import scala.concurrent.duration.DurationInt

class AnonymizationServiceActorTest(_system: ActorSystem) extends TestKit(_system) with ImplicitSender
  with WordSpecLike with Matchers with BeforeAndAfterAll with BeforeAndAfterEach {

  def this() = this(ActorSystem("AnonymizationServiceActorTestSystem"))

  implicit val ec = system.dispatcher
  implicit val timeout = Timeout(30.seconds)

  val dbConfig = TestUtil.createTestDb("anonymizationserviceactortest")
  val dao = new MetaDataDAO(dbConfig)

  val anonymizationDao = new AnonymizationDAO(dbConfig)

  val anonymizationService = system.actorOf(Props(new AnonymizationServiceActor(anonymizationDao, purgeEmptyAnonymizationKeys = false)), name = "AnonymizationService")

  override def beforeAll() = await(anonymizationDao.create())

  override def afterEach() = await(anonymizationDao.clear())

  override def afterAll {
    TestKit.shutdownActorSystem(system)
  }

  "An AnonymizationServiceActor" should {

    "anonymize a dataset with protected health information" in {
      val dicomData = createDicomData()
      anonymizationService ! Anonymize(1, dicomData.attributes, Seq.empty)
      expectMsgPF() {
        case attributes: Attributes =>
          isAnonymous(attributes) shouldBe true
          attributes eq dicomData.attributes shouldBe false
      }
    }

    "anonymize a dataset and apply tag values" in {
      val dicomData = createDicomData()
      val t1 = TagValue(Tag.PatientName.intValue, "Mapped Patient Name")
      val t2 = TagValue(Tag.PatientID.intValue, "Mapped Patient ID")
      val t3 = TagValue(Tag.SeriesDescription.intValue, "Mapped Series Description")
      anonymizationService ! Anonymize(1, dicomData.attributes, Seq(t1, t2, t3))
      expectMsgPF() {
        case attributes: Attributes =>
          isAnonymous(attributes) shouldBe true
          attributes.getString(Tag.PatientName) should be("Mapped Patient Name")
          attributes.getString(Tag.PatientID) should be("Mapped Patient ID")
          attributes.getString(Tag.SeriesDescription) should be("Mapped Series Description")
      }
    }

    "not anonymize an anonymous dataset" in {
      val dicomData = createDicomData()
      val anonymizedAttributes = anonymizeAttributes(dicomData.attributes)
      anonymizationService ! Anonymize(1, anonymizedAttributes, Seq.empty)
      expectMsgPF() {
        case attributes: Attributes =>
          isAnonymous(attributes) shouldBe true
          attributes eq anonymizedAttributes shouldBe true
      }
    }

    "anonymize an anonymous dataset when tag values are present" in {
      val dicomData = createDicomData()
      val t1 = TagValue(Tag.PatientName.intValue, "Mapped Patient Name")
      anonymizationService ! Anonymize(1, dicomData.attributes, Seq(t1))
      expectMsgPF() {
        case attributes: Attributes =>
          isAnonymous(attributes) shouldBe true
          attributes eq dicomData.attributes shouldBe false
      }
    }

    "harmonize anonymization with respect to relevant anonymization keys when sending a file" in {
      val dicomData = createDicomData()
      val key = await(insertAnonymizationKey(dicomData.attributes))
      anonymizationService ! Anonymize(1, dicomData.attributes, Seq.empty)
      expectMsgPF() {
        case harmonized: Attributes =>
          harmonized.getString(Tag.PatientID) should be(key.anonPatientID)
          harmonized.getString(Tag.StudyInstanceUID) should be(key.anonStudyInstanceUID)
          harmonized.getString(Tag.SeriesInstanceUID) should be(key.anonSeriesInstanceUID)
          harmonized.getString(Tag.FrameOfReferenceUID) should be(key.anonFrameOfReferenceUID)
      }
    }

    "reverse anonymization in an anonymous dataset" in {
      val dicomData = createDicomData()
      val anonymizedAttributes = anonymizeAttributes(dicomData.attributes)
      anonymizationService ! ReverseAnonymization(anonymizedAttributes)
      expectMsgPF() {
        case attributes: Attributes => attributes eq anonymizedAttributes shouldBe false
      }
    }

    "not reverse anonymization in a dataset with protected health information (not anonymous)" in {
      val dicomData = createDicomData()
      anonymizationService ! ReverseAnonymization(dicomData.attributes)
      expectMsgPF() {
        case attributes: Attributes => attributes eq dicomData.attributes shouldBe true
      }
    }

    "reverse anonymization in an anonymous dataset based on anonymization keys" in {
      val dicomData = createDicomData()
      val key = await(insertAnonymizationKey(dicomData.attributes))
      val anonymizedAttributes = anonymizeAttributes(dicomData.attributes)
      anonymizedAttributes.setString(Tag.PatientName, VR.PN, key.anonPatientName)
      anonymizedAttributes.setString(Tag.PatientID, VR.SH, key.anonPatientID)
      anonymizedAttributes.setString(Tag.StudyInstanceUID, VR.UI, key.anonStudyInstanceUID)
      anonymizedAttributes.setString(Tag.SeriesInstanceUID, VR.UI, key.anonSeriesInstanceUID)
      anonymizedAttributes.setString(Tag.FrameOfReferenceUID, VR.UI, key.anonFrameOfReferenceUID)
      anonymizationService ! ReverseAnonymization(anonymizedAttributes)

      expectMsgPF() {
        case reversed: Attributes =>
          reversed.getString(Tag.PatientName) should be(key.patientName)
          reversed.getString(Tag.PatientID) should be(key.patientID)
          reversed.getString(Tag.StudyInstanceUID) should be(key.studyInstanceUID)
          reversed.getString(Tag.StudyDescription) should be(key.studyDescription)
          reversed.getString(Tag.StudyID) should be(key.studyID)
          reversed.getString(Tag.AccessionNumber) should be(key.accessionNumber)
          reversed.getString(Tag.SeriesInstanceUID) should be(key.seriesInstanceUID)
          reversed.getString(Tag.SeriesDescription) should be(key.seriesDescription)
          reversed.getString(Tag.ProtocolName) should be(key.protocolName)
          reversed.getString(Tag.FrameOfReferenceUID) should be(key.frameOfReferenceUID)
      }
    }

    "add anonymization image records to an anonymization key when several datasets from the same series are anonymized" in {
      val dicomData1 = createDicomData()
      val dicomData2 = dicomData1.copy(attributes = new Attributes(dicomData1.attributes))
      val dicomData3 = dicomData1.copy(attributes = new Attributes(dicomData1.attributes))
      dicomData1.attributes.setString(Tag.SOPInstanceUID, VR.UI, "sopuid1")
      dicomData2.attributes.setString(Tag.SOPInstanceUID, VR.UI, "sopuid2")
      dicomData3.attributes.setString(Tag.SOPInstanceUID, VR.UI, "sopuid3")
      val image1 = attributesToImage(dicomData1.attributes).copy(id = 1)
      val image2 = attributesToImage(dicomData2.attributes).copy(id = 2)
      val image3 = attributesToImage(dicomData3.attributes).copy(id = 3)
      anonymizationService ! Anonymize(image1.id, dicomData1.attributes, Seq.empty)
      expectMsgType[Attributes]
      anonymizationService ! Anonymize(image2.id, dicomData2.attributes, Seq.empty)
      expectMsgType[Attributes]
      anonymizationService ! Anonymize(image3.id, dicomData3.attributes, Seq.empty)
      expectMsgType[Attributes]
      val anonImages = await(anonymizationDao.listAnonymizationKeyImages)
      anonImages should have length 3
      anonImages.map(_.imageId) shouldBe List(image1.id, image2.id, image3.id)
    }
  }

  def insertAnonymizationKey(attributes: Attributes) = {
    val key = createAnonymizationKey(attributes)
    anonymizationDao.insertAnonymizationKey(key).map(_ => key)
  }

}