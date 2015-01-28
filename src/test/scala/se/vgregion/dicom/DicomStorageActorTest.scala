package se.vgregion.dicom

import java.nio.file.Files
import java.nio.file.Paths

import scala.slick.driver.H2Driver
import scala.slick.jdbc.JdbcBackend.Database

import akka.actor.ActorSystem
import akka.testkit.ImplicitSender
import akka.testkit.TestActorRef
import akka.testkit.TestKit

import org.scalatest.BeforeAndAfterAll
import org.scalatest.Matchers
import org.scalatest.WordSpecLike

import se.vgregion.app.DbProps
import se.vgregion.util.TestUtil

import DicomProtocol.AddDataset
import DicomProtocol.GetAllImageFiles
import DicomProtocol.ImageAdded
import DicomProtocol.ImageFiles
import DicomUtil.loadDataset

class DicomStorageActorTest(_system: ActorSystem) extends TestKit(_system) with ImplicitSender
  with WordSpecLike with Matchers with BeforeAndAfterAll {

  def this() = this(ActorSystem("StorageTestSystem"))

  val db = Database.forURL("jdbc:h2:mem:dicomstorageactortest;DB_CLOSE_DELAY=-1", driver = "org.h2.Driver")
  val dbProps = DbProps(db, H2Driver)

  val fileName = "anon270.dcm"
  val dcmPath = Paths.get(getClass().getResource("../app/" + fileName).toURI())
  val dataset = loadDataset(dcmPath, true)

  val storage = Files.createTempDirectory("slicebox-test-storage-")

  val storageActorRef = TestActorRef(new DicomStorageActor(dbProps, storage))
  val storageActor = storageActorRef.underlyingActor

  override def afterAll {
    TestKit.shutdownActorSystem(system)
    TestUtil.deleteFolder(storage)
  }
    
  "A DicomStorageActor" must {

    "return an empty list when no metadata exists" in {
      storageActorRef ! GetAllImageFiles
      expectMsg(ImageFiles(Seq()))
    }

    "return a notification that the dataset has been added when adding a dataset" in {
      storageActorRef ! AddDataset(dataset)
      expectMsgPF() {
        case ImageAdded(image) => true
      }
    }

    "return a list of one object when asking for all image files" in {
      storageActorRef ! GetAllImageFiles
      expectMsgPF() {
        case ImageFiles(list) if (list.size == 1) => true
      }
    }

    "return a notification that the dataset has been added when adding an already added dataset" in {
      storageActorRef ! AddDataset(dataset)
      expectMsgPF() {
        case ImageAdded(image) => true
      }
    }

    "return a list of one object when asking for all image files even though a dataset has been added twice" in {
      storageActorRef ! GetAllImageFiles
      expectMsgPF() {
        case ImageFiles(list) if (list.size == 1) => true
      }
    }

  }

}