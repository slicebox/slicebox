package se.nimsa.sbx.storage

import akka.actor.ActorSystem
import akka.actor.Status.Failure
import akka.testkit.{ImplicitSender, TestActorRef, TestKit}
import org.scalatest.{BeforeAndAfterAll, Matchers, WordSpecLike}
import se.nimsa.sbx.app.DbProps
import se.nimsa.sbx.app.GeneralProtocol.{Source, SourceType}
import se.nimsa.sbx.dicom.DicomUtil
import se.nimsa.sbx.storage.StorageProtocol.{AddDicomData, CheckDicomData, DicomDataAdded}
import se.nimsa.sbx.util.TestUtil

import scala.slick.driver.H2Driver
import scala.slick.jdbc.JdbcBackend.Database

class StorageServiceActorTest(_system: ActorSystem) extends TestKit(_system) with ImplicitSender
  with WordSpecLike with Matchers with BeforeAndAfterAll {

  def this() = this(ActorSystem("StorageTestSystem"))

  val db = Database.forURL("jdbc:h2:mem:storageserviceactortest;DB_CLOSE_DELAY=-1", driver = "org.h2.Driver")
  val dbProps = DbProps(db, H2Driver)

  val dicomData = TestUtil.testImageDicomData()
  val image = DicomUtil.attributesToImage(dicomData.attributes)

  val storage = new RuntimeStorage

  val storageActorRef = TestActorRef(new StorageServiceActor(storage))
  val storageActor = storageActorRef.underlyingActor

  val source = Source(SourceType.BOX, "remote box", 1)

  override def afterAll {
    TestKit.shutdownActorSystem(system)
  }

  "The storage service" must {

    "return 'overwrite = true' when adding the same dataset for the second time, indicating that the previous dataset was overwritten" in {
      storageActorRef ! AddDicomData(dicomData, source, image)
      expectMsgPF() {
        case DicomDataAdded(_, overwrite) =>
          overwrite shouldBe false
      }
      storageActorRef ! AddDicomData(dicomData, source, image)
      expectMsgPF() {
        case DicomDataAdded(_, overwrite) =>
          overwrite shouldBe true
      }
    }

    "return a notification that the dataset has been added when adding a dataset" in {
      storageActorRef ! AddDicomData(dicomData, source, image)
      expectMsgType[DicomDataAdded]
    }

    "return a notification that the dataset has been added when adding an already added dataset" in {
      storageActorRef ! AddDicomData(dicomData, source, image)
      expectMsgType[DicomDataAdded]
    }

    "return a failure message when checking a secondary capture dataset with the standard list of accepted contexts" in {
      storageActorRef ! CheckDicomData(DicomUtil.loadDicomData(TestUtil.testSecondaryCaptureFile.toPath, withPixelData = true, useBulkDataURI = true), useExtendedContexts = false)
      expectMsgType[Failure]
    }

    "return a success message when checking a secondary capture dataset with the extended list of accepted contexts" in {
      storageActorRef ! CheckDicomData(DicomUtil.loadDicomData(TestUtil.testSecondaryCaptureFile.toPath, withPixelData = true, useBulkDataURI = true), useExtendedContexts = true)
      expectMsg(true)
    }
  }

}