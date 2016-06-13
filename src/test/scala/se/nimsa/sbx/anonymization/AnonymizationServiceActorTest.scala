package se.nimsa.sbx.anonymization

import scala.slick.driver.H2Driver
import scala.slick.jdbc.JdbcBackend.Database

import org.dcm4che3.data.Attributes
import org.dcm4che3.data.Tag
import org.dcm4che3.data.VR
import org.scalatest.BeforeAndAfterAll
import org.scalatest.BeforeAndAfterEach
import org.scalatest.Matchers
import org.scalatest.WordSpecLike

import AnonymizationProtocol.Anonymize
import AnonymizationProtocol.ReverseAnonymization
import AnonymizationUtil.anonymizeDataset
import akka.actor.ActorSystem
import akka.actor.Props
import akka.testkit.ImplicitSender
import akka.testkit.TestKit
import se.nimsa.sbx.app.DbProps
import se.nimsa.sbx.dicom.DicomUtil.attributesToImage
import se.nimsa.sbx.util.TestUtil.createAnonymizationKey
import se.nimsa.sbx.util.TestUtil.createDicomData

class AnonymizationServiceActorTest(_system: ActorSystem) extends TestKit(_system) with ImplicitSender
    with WordSpecLike with Matchers with BeforeAndAfterAll with BeforeAndAfterEach {

  def this() = this(ActorSystem("AnonymizationServiceActorTestSystem"))

  val db = Database.forURL("jdbc:h2:mem:anonymizationserviceactortest;DB_CLOSE_DELAY=-1", driver = "org.h2.Driver")
  val dbProps = DbProps(db, H2Driver)

  val anonymizationDao = new AnonymizationDAO(H2Driver)

  db.withSession { implicit session =>
    anonymizationDao.create
  }

  val anonymizationService = system.actorOf(Props(new AnonymizationServiceActor(dbProps)), name = "AnonymizationService")

  override def afterEach() =
    db.withSession { implicit session =>
      anonymizationDao.clear
    }

  override def afterAll {
    TestKit.shutdownActorSystem(system)
  }

  "An AnonymizationServiceActor" should {

    "harmonize anonymization with respect to relevant anonymization keys when sending a file" in {
      db.withSession { implicit session =>
        val dicomData = createDicomData()
        val key = insertAnonymizationKey(dicomData.attributes)
        anonymizationService ! Anonymize(1, dicomData.attributes, Seq.empty)
        expectMsgPF() {
          case harmonized: Attributes =>
            harmonized.getString(Tag.PatientID) should be(key.anonPatientID)
            harmonized.getString(Tag.StudyInstanceUID) should be(key.anonStudyInstanceUID)
            harmonized.getString(Tag.SeriesInstanceUID) should be(key.anonSeriesInstanceUID)
            harmonized.getString(Tag.FrameOfReferenceUID) should be(key.anonFrameOfReferenceUID)
        }
      }
    }

    "reverse anonymization in an anonymous dataset based on anonymization keys" in {
      db.withSession { implicit session =>
        val dicomData = createDicomData()
        val key = insertAnonymizationKey(dicomData.attributes)
        val anonymizedDataset = anonymizeDataset(dicomData.attributes)
        anonymizedDataset.setString(Tag.PatientName, VR.PN, key.anonPatientName)
        anonymizedDataset.setString(Tag.PatientID, VR.SH, key.anonPatientID)
        anonymizedDataset.setString(Tag.StudyInstanceUID, VR.UI, key.anonStudyInstanceUID)
        anonymizedDataset.setString(Tag.SeriesInstanceUID, VR.UI, key.anonSeriesInstanceUID)
        anonymizedDataset.setString(Tag.FrameOfReferenceUID, VR.UI, key.anonFrameOfReferenceUID)
        anonymizationService ! ReverseAnonymization(anonymizedDataset)

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
    }

    "add anonymization image records to an anonymization key when several datasets from the same series are anonymized" in {
      db.withSession { implicit session =>
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
        val anonImages = anonymizationDao.listAnonymizationKeyImages
        anonImages should have length 3
        anonImages.map(_.imageId) shouldBe List(image1.id, image2.id, image3.id)
      }      
    }
  }

  def insertAnonymizationKey(dataset: Attributes)(implicit session: H2Driver.simple.Session) = {
    val key = createAnonymizationKey(dataset)
    anonymizationDao.insertAnonymizationKey(key)
    key
  }

}