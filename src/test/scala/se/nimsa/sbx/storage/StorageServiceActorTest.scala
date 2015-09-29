package se.nimsa.sbx.storage

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
import se.nimsa.sbx.dicom.DicomUtil.loadDataset
import StorageProtocol._
import se.nimsa.sbx.seriestype.SeriesTypeDAO
import se.nimsa.sbx.app.GeneralProtocol._

class StorageServiceActorTest(_system: ActorSystem) extends TestKit(_system) with ImplicitSender
    with WordSpecLike with Matchers with BeforeAndAfterAll {

  def this() = this(ActorSystem("StorageTestSystem"))

  val db = Database.forURL("jdbc:h2:mem:dicomstorageactortest;DB_CLOSE_DELAY=-1", driver = "org.h2.Driver")
  val dbProps = DbProps(db, H2Driver)

  db.withSession { implicit session =>
    new SeriesTypeDAO(dbProps.driver).create
    new MetaDataDAO(dbProps.driver).create
    new PropertiesDAO(dbProps.driver).create
  }

  val dataset = TestUtil.testImageDataset()

  val storage = Files.createTempDirectory("slicebox-test-storage-")

  val storageActorRef = TestActorRef(new StorageServiceActor(dbProps, storage))
  val storageActor = storageActorRef.underlyingActor

  override def afterAll {
    TestKit.shutdownActorSystem(system)
    TestUtil.deleteFolder(storage)
  }

  "The storage service" must {

    "return an empty list of patients when no metadata exists" in {
      storageActorRef ! GetPatients(0, 10000, None, true, None, Array.empty, Array.empty, Array.empty)
      expectMsg(Patients(Seq()))
    }

    "return a notification that the dataset has been added when adding a dataset" in {
      storageActorRef ! AddDataset(dataset, SourceTypeId(SourceType.UNKNOWN, -1))
      expectMsgPF() {
        case ImageAdded(image) => true
      }
    }

    "return a list of one object when asking for all patients" in {
      storageActorRef ! GetPatients(0, 10000, None, true, None, Array.empty, Array.empty, Array.empty)
      expectMsgPF() {
        case Patients(list) if (list.size == 1) => true
      }
    }

    "return a notification that the dataset has been added when adding an already added dataset" in {
      storageActorRef ! AddDataset(dataset, SourceTypeId(SourceType.UNKNOWN, -1))
      expectMsgPF() {
        case ImageAdded(image) => true
      }
    }

    "return a list of one object when asking for all patients even though a dataset has been added twice" in {
      storageActorRef ! GetPatients(0, 10000, None, true, None, Array.empty, Array.empty, Array.empty)
      expectMsgPF() {
        case Patients(list) if (list.size == 1) => true
      }
    }

  }

}