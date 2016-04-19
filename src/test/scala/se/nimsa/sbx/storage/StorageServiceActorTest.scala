package se.nimsa.sbx.storage

import java.nio.file.Files

import akka.actor.ActorSystem
import akka.actor.Status.Failure
import akka.testkit.{ImplicitSender, TestActorRef, TestKit}
import org.scalatest.{BeforeAndAfterAll, Matchers, WordSpecLike}
import se.nimsa.sbx.app.DbProps
import se.nimsa.sbx.dicom.DicomUtil
import se.nimsa.sbx.storage.StorageProtocol.{AddDataset, DatasetAdded}
import se.nimsa.sbx.util.TestUtil

import scala.slick.driver.H2Driver
import scala.slick.jdbc.JdbcBackend.Database

class StorageServiceActorTest(_system: ActorSystem) extends TestKit(_system) with ImplicitSender
  with WordSpecLike with Matchers with BeforeAndAfterAll {

  def this() = this(ActorSystem("StorageTestSystem"))

  val db = Database.forURL("jdbc:h2:mem:storageserviceactortest;DB_CLOSE_DELAY=-1", driver = "org.h2.Driver")
  val dbProps = DbProps(db, H2Driver)

  val dataset = TestUtil.testImageDataset()
  val image = DicomUtil.datasetToImage(dataset)

  val storage = Files.createTempDirectory("slicebox-test-storage-")

  val storageActorRef = TestActorRef(new StorageServiceActor(storage))
  val storageActor = storageActorRef.underlyingActor

  override def afterAll {
    TestKit.shutdownActorSystem(system)
    TestUtil.deleteFolder(storage)
  }

  "The storage service" must {

    "return 'overwrite = true' when adding the same dataset for the second time, indicating that the previous dataset was overwritten" in {
      storageActorRef ! AddDataset(dataset, image)
      expectMsgPF() {
        case DatasetAdded(_, overwrite) =>
          overwrite shouldBe false
      }
      storageActorRef ! AddDataset(dataset, image)
      expectMsgPF() {
        case DatasetAdded(_, overwrite) =>
          overwrite shouldBe true
      }
    }

    "return a notification that the dataset has been added when adding a dataset" in {
      storageActorRef ! AddDataset(dataset, image)
      expectMsgType[DatasetAdded]
    }

    "return a notification that the dataset has been added when adding an already added dataset" in {
      storageActorRef ! AddDataset(dataset, image)
      expectMsgType[DatasetAdded]
    }

    "return a failure message when adding a dataset with a non-supported SOP class" in {
      storageActorRef ! AddDataset(DicomUtil.loadDataset(TestUtil.testSecondaryCaptureFile.toPath, withPixelData = true), image)
      expectMsgType[Failure]
    }
  }

}