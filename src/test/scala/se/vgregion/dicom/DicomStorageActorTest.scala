package se.vgregion.dicom

import scala.slick.driver.H2Driver
import scala.slick.jdbc.JdbcBackend.Database
import org.scalatest.BeforeAndAfterAll
import org.scalatest.Matchers
import org.scalatest.WordSpecLike
import se.vgregion.app.DbProps
import DicomProtocol._
import DicomHierarchy._
import DicomPropertyValue._
import akka.actor.ActorSystem
import akka.testkit.ImplicitSender
import akka.testkit.TestKit
import java.nio.file.Paths
import java.nio.file.Path
import org.dcm4che3.data.Attributes
import org.dcm4che3.io.DicomInputStream
import java.io.BufferedInputStream
import java.nio.file.Files
import org.dcm4che3.io.DicomInputStream.IncludeBulkData
import org.dcm4che3.data.Tag

class DicomStorageActorTest(_system: ActorSystem) extends TestKit(_system) with ImplicitSender
  with WordSpecLike with Matchers with BeforeAndAfterAll {

  def this() = this(ActorSystem("StorageTestSystem"))

  override def afterAll {
    TestKit.shutdownActorSystem(system)
  }

  val db = Database.forURL("jdbc:h2:mem:dicomstorageactortest;DB_CLOSE_DELAY=-1", driver = "org.h2.Driver")
  val dbProps = DbProps(db, H2Driver)

  val fileName = "anon270.dcm"
  val dcmPath = Paths.get(getClass().getResource("../app/" + fileName).toURI())
  val dataset = loadDicom(dcmPath, true)

  val storage = Files.createTempDirectory("slicebox-test-storage-")

  val storageActor = system.actorOf(DicomStorageActor.props(dbProps, storage))

  "A DicomStorageActor" must {

    "return an empty list when no metadata exists" in {
      storageActor ! GetAllImageFiles
      expectMsg(ImageFiles(Seq()))
    }

    "return a notification that the dataset has been added when adding a dataset" in {
      storageActor ! AddDataset(dataset)
      expectMsgPF() {
        case ImageAdded(image) => true
      }
    }

    "return a list of one object when asking for all image files" in {
      storageActor ! GetAllImageFiles
      expectMsgPF() {
        case ImageFiles(list) if (list.size == 1) => true
      }
    }

    "return a notification that the dataset has been added when adding an elready added dataset" in {
      storageActor ! AddDataset(dataset)
      expectMsgPF() {
        case ImageAdded(image) => true
      }
    }

    "return a list of one object when asking for all image files even though a dataset has been added twice" in {
      storageActor ! GetAllImageFiles
      expectMsgPF() {
        case ImageFiles(list) if (list.size == 1) => true
      }
    }

  }

  def loadDicom(path: Path, withPixelData: Boolean): Attributes = {
    val dis = new DicomInputStream(new BufferedInputStream(Files.newInputStream(path)))
    val dataset =
      if (withPixelData)
        dis.readDataset(-1, -1)
      else {
        dis.setIncludeBulkData(IncludeBulkData.NO)
        dis.readDataset(-1, Tag.PixelData);
      }
    dataset
  }

}