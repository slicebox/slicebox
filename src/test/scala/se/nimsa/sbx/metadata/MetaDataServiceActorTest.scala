package se.nimsa.sbx.metadata

import java.nio.file.Files

import scala.slick.driver.H2Driver
import scala.slick.jdbc.JdbcBackend.Database

import org.scalatest.BeforeAndAfterAll
import org.scalatest.Matchers
import org.scalatest.WordSpecLike

import MetaDataProtocol._
import akka.actor.ActorSystem
import akka.testkit.ImplicitSender
import akka.testkit.TestActorRef
import akka.testkit.TestKit
import se.nimsa.sbx.app.DbProps
import se.nimsa.sbx.app.GeneralProtocol.Source
import se.nimsa.sbx.app.GeneralProtocol.SourceType
import se.nimsa.sbx.dicom.DicomUtil._
import se.nimsa.sbx.seriestype.SeriesTypeDAO
import se.nimsa.sbx.util.TestUtil

class MetaDataServiceActorTest(_system: ActorSystem) extends TestKit(_system) with ImplicitSender
    with WordSpecLike with Matchers with BeforeAndAfterAll {

  def this() = this(ActorSystem("MetaDataTestSystem"))

  val db = Database.forURL("jdbc:h2:mem:metadataserviceactortest;DB_CLOSE_DELAY=-1", driver = "org.h2.Driver")
  val dbProps = DbProps(db, H2Driver)

  db.withSession { implicit session =>
    new SeriesTypeDAO(dbProps.driver).create
    new MetaDataDAO(dbProps.driver).create
    new PropertiesDAO(dbProps.driver).create
  }

  val dataset = TestUtil.testImageDataset()

  val storage = Files.createTempDirectory("slicebox-test-storage-")

  val metaDataActorRef = TestActorRef(new MetaDataServiceActor(dbProps))
  val metaDataActor = metaDataActorRef.underlyingActor

  override def afterAll {
    TestKit.shutdownActorSystem(system)
    TestUtil.deleteFolder(storage)
  }

  "The meta data service" must {

    "return an empty list of patients when no metadata exists" in {
      metaDataActorRef ! GetPatients(0, 10000, None, true, None, Array.empty, Array.empty, Array.empty)
      expectMsg(Patients(Seq()))
    }

    "return a list of one object when asking for all patients" in {
      val source = Source(SourceType.UNKNOWN, "unknown", -1)
      metaDataActorRef ! AddMetaData(
        datasetToPatient(dataset),
        datasetToStudy(dataset),
        datasetToSeries(dataset),
        datasetToImage(dataset), source)
      expectMsgType[MetaDataAdded]

      metaDataActorRef ! GetPatients(0, 10000, None, true, None, Array.empty, Array.empty, Array.empty)
      expectMsgPF() {
        case Patients(list) if (list.size == 1) => true
      }
    }

  }

}