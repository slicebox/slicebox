package se.nimsa.sbx.anonymization

import java.util.Date
import scala.slick.driver.H2Driver
import scala.slick.jdbc.JdbcBackend.Database
import scala.concurrent.duration.DurationInt
import org.dcm4che3.data.Attributes
import org.dcm4che3.data.Tag
import org.dcm4che3.data.VR
import org.scalatest.BeforeAndAfterAll
import org.scalatest.BeforeAndAfterEach
import org.scalatest.Matchers
import org.scalatest.WordSpecLike
import akka.actor.ActorSystem
import akka.actor.Props
import akka.actor.actorRef2Scala
import akka.testkit.ImplicitSender
import akka.testkit.TestKit
import se.nimsa.sbx.app.DbProps
import se.nimsa.sbx.util.TestUtil._
import se.nimsa.sbx.dicom.DicomUtil.datasetToImage
import AnonymizationProtocol._
import AnonymizationUtil.anonymizeDataset
import se.nimsa.sbx.dicom.DicomHierarchy.Image
import akka.actor.Actor
import se.nimsa.sbx.metadata.MetaDataProtocol.GetImage
import se.nimsa.sbx.app.GeneralProtocol.ImageAdded

class AnonymizationServiceActorTest(_system: ActorSystem) extends TestKit(_system) with ImplicitSender
    with WordSpecLike with Matchers with BeforeAndAfterAll with BeforeAndAfterEach {

  def this() = this(ActorSystem("AnonymizationServiceActorTestSystem"))

  val db = Database.forURL("jdbc:h2:mem:anonymizationserviceactortest;DB_CLOSE_DELAY=-1", driver = "org.h2.Driver")
  val dbProps = DbProps(db, H2Driver)

  val anonymizationDao = new AnonymizationDAO(H2Driver)

  db.withSession { implicit session =>
    anonymizationDao.create
  }

  val anonymizationService = system.actorOf(Props(new AnonymizationServiceActor(dbProps, 5.minutes)), name = "AnonymizationService")

  case class AddImage(image: Image)
  val metaDataService = system.actorOf(Props(new Actor {
    var addedImages = Seq.empty[Image]
    def receive = {
      case AddImage(image) =>
        addedImages = addedImages :+ image
        sender ! ImageAdded(image, null)
      case GetImage(imageId) =>
        sender ! addedImages.find(_.id == imageId)
    }
  }), name = "MetaDataService")
  
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
        val dataset = createDataset()
        val key = insertAnonymizationKey(dataset)
        anonymizationService ! Anonymize(1, dataset, Seq.empty)
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
        val dataset = createDataset()
        val key = insertAnonymizationKey(dataset)
        val anonymizedDataset = anonymizeDataset(dataset)
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
        val dataset1 = createDataset()
        val dataset2 = new Attributes(dataset1)
        val dataset3 = new Attributes(dataset1)
        dataset1.setString(Tag.SOPInstanceUID, VR.UI, "sopuid1")
        dataset2.setString(Tag.SOPInstanceUID, VR.UI, "sopuid2")
        dataset3.setString(Tag.SOPInstanceUID, VR.UI, "sopuid3")
        val image1 = datasetToImage(dataset1)
        val image2 = datasetToImage(dataset2)
        val image3 = datasetToImage(dataset3)
        metaDataService ! AddImage(image1)
        expectMsgType[ImageAdded]
        metaDataService ! AddImage(image2)
        expectMsgType[ImageAdded]
        metaDataService ! AddImage(image3)
        expectMsgType[ImageAdded]
        anonymizationService ! Anonymize(image1.id, dataset1, Seq.empty)
        expectMsgType[Attributes]
        anonymizationService ! Anonymize(image2.id, dataset2, Seq.empty)
        expectMsgType[Attributes]
        anonymizationService ! Anonymize(image3.id, dataset3, Seq.empty)
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