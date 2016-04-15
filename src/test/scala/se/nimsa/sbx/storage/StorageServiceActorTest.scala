package se.nimsa.sbx.storage

import java.nio.file.Files

import scala.concurrent.duration.DurationInt
import scala.slick.driver.H2Driver
import scala.slick.jdbc.JdbcBackend.Database

import org.scalatest.BeforeAndAfterAll
import org.scalatest.Matchers
import org.scalatest.WordSpecLike

import akka.actor.ActorSystem
import akka.testkit.ImplicitSender
import akka.testkit.TestActorRef
import akka.testkit.TestKit
import akka.util.Timeout.durationToTimeout
import se.nimsa.sbx.app.DbProps
import se.nimsa.sbx.app.GeneralProtocol.Source
import se.nimsa.sbx.app.GeneralProtocol.SourceType
import se.nimsa.sbx.metadata.MetaDataDAO
import se.nimsa.sbx.metadata.MetaDataProtocol.GetPatients
import se.nimsa.sbx.metadata.MetaDataProtocol.Patients
import se.nimsa.sbx.metadata.MetaDataServiceActor
import se.nimsa.sbx.metadata.PropertiesDAO
import se.nimsa.sbx.seriestype.SeriesTypeDAO
import se.nimsa.sbx.storage.StorageProtocol.AddDataset
import se.nimsa.sbx.storage.StorageProtocol.DatasetAdded
import se.nimsa.sbx.util.TestUtil

class StorageServiceActorTest(_system: ActorSystem) extends TestKit(_system) with ImplicitSender
    with WordSpecLike with Matchers with BeforeAndAfterAll {

  def this() = this(ActorSystem("StorageTestSystem"))

  val db = Database.forURL("jdbc:h2:mem:storageserviceactortest;DB_CLOSE_DELAY=-1", driver = "org.h2.Driver")
  val dbProps = DbProps(db, H2Driver)

  db.withSession { implicit session =>
    new SeriesTypeDAO(dbProps.driver).create
    new MetaDataDAO(dbProps.driver).create
    new PropertiesDAO(dbProps.driver).create
  }

  val dataset = TestUtil.testImageDataset()

  val storage = Files.createTempDirectory("slicebox-test-storage-")

  val metaDataService = system.actorOf(MetaDataServiceActor.props(dbProps), name = "MetaDataService")
  val storageActorRef = TestActorRef(new StorageServiceActor(storage, 5.minutes))
  val storageActor = storageActorRef.underlyingActor

  override def afterAll {
    TestKit.shutdownActorSystem(system)
    TestUtil.deleteFolder(storage)
  }

  "The storage service" must {

    "return a notification that the dataset has been added when adding a dataset" in {
      val source = Source(SourceType.UNKNOWN, "unknown", -1)
      storageActorRef ! AddDataset(dataset, source)
      expectMsgPF() {
        case DatasetAdded(image, src, overwrite) => true
      }
    }

    "return a notification that the dataset has been added when adding an already added dataset" in {
      val source = Source(SourceType.UNKNOWN, "unknown", -1)
      storageActorRef ! AddDataset(dataset, source)
      expectMsgPF() {
        case DatasetAdded(image, src, overwrite) => true
      }
    }

    "return a list of one object when asking for all patients even though a dataset has been added twice" in {
      metaDataService ! GetPatients(0, 10000, orderBy = None, orderAscending = true, filter = None, Array.empty, Array.empty, Array.empty)
      expectMsgPF() {
        case Patients(list) if list.size == 1 => true
      }
    }

  }

}