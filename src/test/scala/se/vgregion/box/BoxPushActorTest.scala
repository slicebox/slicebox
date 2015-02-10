package se.vgregion.box

import akka.testkit.TestKit
import akka.testkit.ImplicitSender
import org.scalatest._
import akka.actor.ActorSystem
import akka.actor.Props
import org.scalatest.Matchers
import org.scalatest.WordSpecLike
import scala.slick.driver.H2Driver
import scala.slick.jdbc.JdbcBackend.Database
import se.vgregion.app.DbProps
import java.nio.file.Files
import se.vgregion.util.TestUtil
import akka.testkit.TestActorRef
import spray.http.HttpRequest
import scala.concurrent.Promise
import scala.concurrent.Future
import spray.http.HttpResponse
import se.vgregion.box.BoxProtocol._
import se.vgregion.dicom.DicomPropertyValue._
import se.vgregion.dicom.DicomHierarchy._
import se.vgregion.dicom.DicomProtocol._
import se.vgregion.dicom.DicomMetaDataDAO
import spray.http.StatusCodes._
import scala.collection.mutable.ArrayBuffer
import scala.concurrent.duration.DurationInt

class BoxPushActorTest(_system: ActorSystem) extends TestKit(_system) with ImplicitSender
  with WordSpecLike with Matchers with BeforeAndAfterAll with BeforeAndAfterEach {

  def this() = this(ActorSystem("BoxPushActorTestSystem"))

  val db = Database.forURL("jdbc:h2:mem:boxpushactortest;DB_CLOSE_DELAY=-1", driver = "org.h2.Driver")
  val dbProps = DbProps(db, H2Driver)
  
  val storage = Files.createTempDirectory("slicebox-test-storage-")
  
  val boxDao = new BoxDAO(H2Driver)
  val dicomMetaDataDao = new DicomMetaDataDAO(H2Driver)

  db.withSession { implicit session =>
    boxDao.create
    dicomMetaDataDao.create
  }
  
  val testBox = Box(1, "Test Box", "abc123", "testbox.com", BoxSendMethod.PUSH)
  
  val testTransactionId = 888
  val testTransactionId2 = 999
  
  val pat1 = Patient(-1, PatientName("p1"), PatientID("s1"), PatientBirthDate("2000-01-01"), PatientSex("M"))
  val study1 = Study(-1, -1, StudyInstanceUID("stuid1"), StudyDescription("stdesc1"), StudyDate("19990101"), StudyID("stid1"), AccessionNumber("acc1"))
  val study2 = Study(-1, -1, StudyInstanceUID("stuid2"), StudyDescription("stdesc2"), StudyDate("19990102"), StudyID("stid2"), AccessionNumber("acc2"))
  val equipment1 = Equipment(-1, Manufacturer("manu1"), StationName("station1"))
  val equipment2 = Equipment(-1, Manufacturer("manu2"), StationName("station2"))
  val equipment3 = Equipment(-1, Manufacturer("manu3"), StationName("station3"))
  val for1 = FrameOfReference(-1, FrameOfReferenceUID("frid1"))
  val for2 = FrameOfReference(-1, FrameOfReferenceUID("frid2"))
  val series1 = Series(-1, -1, -1, -1, SeriesInstanceUID("seuid1"), SeriesDescription("sedesc1"), SeriesDate("19990101"), Modality("NM"), ProtocolName("prot1"), BodyPartExamined("bodypart1"))
  val series2 = Series(-1, -1, -1, -1, SeriesInstanceUID("seuid2"), SeriesDescription("sedesc2"), SeriesDate("19990102"), Modality("NM"), ProtocolName("prot2"), BodyPartExamined("bodypart2"))
  val series3 = Series(-1, -1, -1, -1, SeriesInstanceUID("seuid3"), SeriesDescription("sedesc3"), SeriesDate("19990103"), Modality("NM"), ProtocolName("prot3"), BodyPartExamined("bodypart1"))
  val series4 = Series(-1, -1, -1, -1, SeriesInstanceUID("seuid4"), SeriesDescription("sedesc4"), SeriesDate("19990104"), Modality("NM"), ProtocolName("prot4"), BodyPartExamined("bodypart2"))
  val image1 = Image(-1, -1, SOPInstanceUID("souid1"), ImageType("PRIMARY/RECON/TOMO"))
  val image2 = Image(-1, -1, SOPInstanceUID("souid2"), ImageType("PRIMARY/RECON/TOMO"))
  val image3 = Image(-1, -1, SOPInstanceUID("souid3"), ImageType("PRIMARY/RECON/TOMO"))
  val image4 = Image(-1, -1, SOPInstanceUID("souid4"), ImageType("PRIMARY/RECON/TOMO"))
  val image5 = Image(-1, -1, SOPInstanceUID("souid5"), ImageType("PRIMARY/RECON/TOMO"))
  val image6 = Image(-1, -1, SOPInstanceUID("souid6"), ImageType("PRIMARY/RECON/TOMO"))
  val image7 = Image(-1, -1, SOPInstanceUID("souid7"), ImageType("PRIMARY/RECON/TOMO"))
  val image8 = Image(-1, -1, SOPInstanceUID("souid8"), ImageType("PRIMARY/RECON/TOMO"))
  var imageFile1 = ImageFile(-1, FileName("file1"))
  var imageFile2 = ImageFile(-1, FileName("file2"))
  val imageFile3 = ImageFile(-1, FileName("file3"))
  val imageFile4 = ImageFile(-1, FileName("file4"))
  val imageFile5 = ImageFile(-1, FileName("file5"))
  val imageFile6 = ImageFile(-1, FileName("file6"))
  val imageFile7 = ImageFile(-1, FileName("file7"))
  val imageFile8 = ImageFile(-1, FileName("file8"))
  
  db.withSession { implicit session =>
    val dbPat = dicomMetaDataDao.insert(pat1)
    val dbStudy = dicomMetaDataDao.insert(study1.copy(patientId = dbPat.id))
    val dbEquipment = dicomMetaDataDao.insert(equipment1)
    val dbFor = dicomMetaDataDao.insert(for1)
    val dbSeries = dicomMetaDataDao.insert(series1.copy(studyId = dbStudy.id, equipmentId = dbEquipment.id, frameOfReferenceId = dbFor.id))
    val dbImage = dicomMetaDataDao.insert(image1.copy(seriesId = dbSeries.id))
    imageFile1 = dicomMetaDataDao.insert(imageFile1.copy(id = dbImage.id))
    val dbImage2 = dicomMetaDataDao.insert(image1.copy(seriesId = dbSeries.id))
    imageFile2 = dicomMetaDataDao.insert(imageFile2.copy(id = dbImage2.id))
  }
  
  
  val fileSendOKRespone = HttpResponse()
  val fileSendFailedRespone = HttpResponse(InternalServerError)
  var mockSendFileHttpResponse: HttpResponse = null
  val capturedFileSendRequests: ArrayBuffer[HttpRequest] = ArrayBuffer()
  
  val boxPushActorRef = _system.actorOf(Props(new BoxPushActor(testBox, dbProps, storage, 500.millis) {
    override def sendFilePipeline = {
      (req: HttpRequest) => {
        capturedFileSendRequests += req
        Future {
          mockSendFileHttpResponse
        }
      }
    }
  }))

  override def afterAll {
    TestKit.shutdownActorSystem(system)
    TestUtil.deleteFolder(storage)
  }
  
  override def beforeEach() {
    capturedFileSendRequests.clear()
    
    db.withSession { implicit session =>
      boxDao.listOutboxEntries.foreach(outboxEntry => {
        boxDao.removeOutboxEntry(outboxEntry.id)
      })
    }
  }

  "A BoxPushActor" should {

    "remove processed outbox entry" in {
      mockSendFileHttpResponse = fileSendOKRespone
      
      db.withSession { implicit session =>
        boxDao.insertOutboxEntry(OutboxEntry(1, testBox.id, testTransactionId, 1, 1, imageFile1.id, false))
      
        // Sleep for a while so that the BoxPushActor has time to poll database
        Thread.sleep(1000)
       
        boxDao.listOutboxEntries.size should be(0)
      }
    }
    
    "should post file to correct URL" in {
      mockSendFileHttpResponse = fileSendOKRespone
      
      val outboxEntry = OutboxEntry(1, testBox.id, testTransactionId, 2, 5, imageFile1.id, false)
      db.withSession { implicit session =>
        boxDao.insertOutboxEntry(outboxEntry)
      }
      
      // Sleep for a while so that the BoxPushActor has time to poll database
      Thread.sleep(1000)
      
      capturedFileSendRequests.size should be(1)
      capturedFileSendRequests(0).uri.toString() should be(s"${testBox.baseUrl}/image/${outboxEntry.transactionId}/${outboxEntry.sequenceNumber}/${outboxEntry.totalImageCount}")
    }
    
    "should post file in correct order" in {
      mockSendFileHttpResponse = fileSendOKRespone
      
      val outboxEntrySeq1 = OutboxEntry(1, testBox.id, testTransactionId, 1, 2, imageFile1.id, false)
      val outboxEntrySeq2 = OutboxEntry(1, testBox.id, testTransactionId, 2, 2, imageFile2.id, false)
      db.withSession { implicit session =>
        // Insert outbox entries out of order
        boxDao.insertOutboxEntry(outboxEntrySeq2)
        boxDao.insertOutboxEntry(outboxEntrySeq1)
      }
      
      // Sleep for a while so that the BoxPushActor has time to poll database
      Thread.sleep(1000)
      
      capturedFileSendRequests.size should be(2)
      capturedFileSendRequests(0).uri.toString() should be(s"${testBox.baseUrl}/image/${outboxEntrySeq1.transactionId}/${outboxEntrySeq1.sequenceNumber}/${outboxEntrySeq1.totalImageCount}")
      capturedFileSendRequests(1).uri.toString() should be(s"${testBox.baseUrl}/image/${outboxEntrySeq2.transactionId}/${outboxEntrySeq2.sequenceNumber}/${outboxEntrySeq2.totalImageCount}")
    }
    
    "mark outbox entry as failed when file send fails" in {
      mockSendFileHttpResponse = fileSendFailedRespone
      
      db.withSession { implicit session =>
        boxDao.insertOutboxEntry(OutboxEntry(1, testBox.id, testTransactionId, 1, 1, imageFile1.id, false))
      
        // Sleep for a while so that the BoxPushActor has time to poll database
        Thread.sleep(1000)
       
        val outboxEntries = boxDao.listOutboxEntries
        outboxEntries.size should be(1)
        outboxEntries.foreach(outboxEntry => {
          outboxEntry.failed should be(true)
        })
      }
    }
    
    "mark all outbox entries for transaction as failed when file send fails" in {
      mockSendFileHttpResponse = fileSendFailedRespone
      
      db.withSession { implicit session =>
        boxDao.insertOutboxEntry(OutboxEntry(1, testBox.id, testTransactionId, 1, 2, imageFile1.id, false))
        boxDao.insertOutboxEntry(OutboxEntry(1, testBox.id, testTransactionId, 2, 2, imageFile2.id, false))
      
        // Sleep for a while so that the BoxPushActor has time to poll database
        Thread.sleep(1000)
       
        val outboxEntries = boxDao.listOutboxEntries
        outboxEntries.size should be(2)
        outboxEntries.foreach(outboxEntry => {
          outboxEntry.failed should be(true)
        })
      }
    }
    
    "process other transactions when file send fails" in {
      mockSendFileHttpResponse = fileSendFailedRespone
      
      db.withSession { implicit session =>
        boxDao.insertOutboxEntry(OutboxEntry(1, testBox.id, testTransactionId, 1, 1, imageFile1.id, false))
      
        // Sleep for a while so that the BoxPushActor has time to poll database
        Thread.sleep(1000)
        
        mockSendFileHttpResponse = fileSendOKRespone
        boxDao.insertOutboxEntry(OutboxEntry(1, testBox.id, testTransactionId2, 1, 1, imageFile2.id, false))
        
        Thread.sleep(1000)
       
        capturedFileSendRequests.size should be(2)
        
        val outboxEntries = boxDao.listOutboxEntries
        outboxEntries.size should be(1)
        outboxEntries.foreach(outboxEntry => {
          outboxEntry.transactionId should be(testTransactionId)
          outboxEntry.failed should be(true)
        })
      }
    }
  }
}