package se.nimsa.sbx.storage

import java.nio.file.Files

import akka.actor.ActorSystem
import akka.actor.Status.Failure
import akka.testkit.{ImplicitSender, TestActorRef, TestKit}
import org.scalatest.{BeforeAndAfterAll, Matchers, WordSpecLike}
import se.nimsa.sbx.app.DbProps
import se.nimsa.sbx.app.GeneralProtocol.{Source, SourceType}
import se.nimsa.sbx.dicom.{DicomData, DicomUtil}
import se.nimsa.sbx.storage.StorageProtocol.{AddDicomData, CheckDicomData, DicomDataAdded, GetDicomData}
import se.nimsa.sbx.util.TestUtil

import scala.concurrent.duration.DurationInt
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

  val storageActorRef = TestActorRef(new StorageServiceActor(storage, cleanupMinimumFileAge = 0.seconds))
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
      storageActorRef ! CheckDicomData(DicomUtil.loadDicomData(TestUtil.testSecondaryCaptureFile.toPath, withPixelData = true), useExtendedContexts = false)
      expectMsgType[Failure]
    }

    "return a success message when checking a secondary capture dataset with the extended list of accepted contexts" in {
      storageActorRef ! CheckDicomData(DicomUtil.loadDicomData(TestUtil.testSecondaryCaptureFile.toPath, withPixelData = true), useExtendedContexts = true)
      expectMsg(true)
    }

    "cleanup temporary DICOM bulk data files regularly" in {
      val tempDir = Files.createTempDirectory("sbx-test-temp-dir-")
      DicomUtil.bulkDataTempFileDirectory = tempDir.toString
      tempDir.toFile.listFiles shouldBe empty

      storageActorRef ! AddDicomData(dicomData, source, image)
      expectMsgType[DicomDataAdded]

      storageActorRef ! GetDicomData(image, withPixelData = true)
      expectMsgType[DicomData]

      tempDir.toFile.listFiles should have length 1

      storageActor.cleanupTemporaryFiles()
      expectNoMsg

      tempDir.toFile.listFiles shouldBe empty

      DicomUtil.bulkDataTempFileDirectory = System.getProperty("java.io.tmpdir")
      TestUtil.deleteFolder(tempDir)
    }
  }

}