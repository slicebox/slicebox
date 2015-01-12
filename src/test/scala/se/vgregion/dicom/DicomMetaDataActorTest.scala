package se.vgregion.dicom

import scala.slick.driver.H2Driver
import scala.slick.jdbc.JdbcBackend.Database
import org.scalatest.BeforeAndAfterAll
import org.scalatest.Matchers
import org.scalatest.WordSpecLike
import se.vgregion.app.DbProps
import DicomDispatchProtocol._
import DicomHierarchy._
import DicomMetaDataProtocol._
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

class DicomMetaDataActorTest(_system: ActorSystem) extends TestKit(_system) with ImplicitSender
  with WordSpecLike with Matchers with BeforeAndAfterAll {

  def this() = this(ActorSystem("MetaDataTestSystem"))

  override def afterAll {
    TestKit.shutdownActorSystem(system)
  }

  val db = Database.forURL("jdbc:h2:mem:dicommetadataactortest;DB_CLOSE_DELAY=-1", driver = "org.h2.Driver")
  val dbProps = DbProps(db, H2Driver)

  val fileName = "anon270.dcm"
  val dcmPath = Paths.get(getClass().getResource("../app/" + fileName).toURI())
  val dcm = loadDicom(dcmPath, true).get

  "A MetaDataActor" must {

    "return an empty list when no metadata exists" in {
      val metaDataActor = system.actorOf(DicomMetaDataActor.props(dbProps))
      metaDataActor ! GetAllImageFiles(None)
      expectMsg(ImageFiles(Seq()))
    }

    "return a notification that the dataset has been added when adding a dataset" in {
      val metaDataActor = system.actorOf(DicomMetaDataActor.props(dbProps))
    	metaDataActor ! AddDataset(dcm._1, dcm._2, fileName, "testOwner")
    	expectMsgPF() {
        case DatasetAdded(imageFile) => true      
      }
    }
    
    "return a list of one object when asking for all image files" in {
      val metaDataActor = system.actorOf(DicomMetaDataActor.props(dbProps))
      metaDataActor ! GetAllImageFiles(None)
      expectMsgPF() {
        case ImageFiles(list) if (list.size == 1) => true
      }
    }
  }

  def loadDicom(path: Path, withPixelData: Boolean): Option[(Attributes, Attributes)] = {
    try {
      val dis = new DicomInputStream(new BufferedInputStream(Files.newInputStream(path)))
      val metaInformation = dis.readFileMetaInformation();
      val dataset = if (withPixelData)
        dis.readDataset(-1, -1)
      else {
        dis.setIncludeBulkData(IncludeBulkData.NO)
        dis.readDataset(-1, Tag.PixelData);
      }
      return Some((metaInformation, dataset))
    } catch {
      case _: Throwable => None
    }
  }

}