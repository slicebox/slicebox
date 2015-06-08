package se.nimsa.sbx.dicom

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
import se.nimsa.sbx.app.DbProps
import se.nimsa.sbx.util.TestUtil
import DicomProtocol._
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

    "return an empty list of patients when no metadata exists" in {
      storageActorRef ! GetPatients(0, 10000, None, true, None, None, None)
      expectMsg(Patients(Seq()))
    }

    "return a notification that the dataset has been added when adding a dataset" in {
      storageActorRef ! AddDataset(dataset, SourceType.UNKNOWN, -1)
      expectMsgPF() {
        case ImageAdded(image) => true
      }
    }

    "return a list of one object when asking for all patients" in {
      storageActorRef ! GetPatients(0, 10000, None, true, None, None, None)
      expectMsgPF() {
        case Patients(list) if (list.size == 1) => true
      }
    }

    "return a notification that the dataset has been added when adding an already added dataset" in {
      storageActorRef ! AddDataset(dataset, SourceType.UNKNOWN, -1)
      expectMsgPF() {
        case ImageAdded(image) => true
      }
    }

    "return a list of one object when asking for all patients even though a dataset has been added twice" in {
      storageActorRef ! GetPatients(0, 10000, None, true, None, None, None)
      expectMsgPF() {
        case Patients(list) if (list.size == 1) => true
      }
    }

  }

}