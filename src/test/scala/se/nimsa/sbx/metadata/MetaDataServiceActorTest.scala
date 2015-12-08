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
import akka.actor.Props
import akka.actor.Actor
import se.nimsa.sbx.dicom.DicomHierarchy._
import scala.collection.mutable.ListBuffer
import org.scalatest.BeforeAndAfterEach
import se.nimsa.sbx.dicom.DicomPropertyValue._

class MetaDataServiceActorTest(_system: ActorSystem) extends TestKit(_system) with ImplicitSender
    with WordSpecLike with Matchers with BeforeAndAfterAll with BeforeAndAfterEach {

  def this() = this(ActorSystem("MetaDataTestSystem"))

  val db = Database.forURL("jdbc:h2:mem:metadataserviceactortest;DB_CLOSE_DELAY=-1", driver = "org.h2.Driver")
  val dbProps = DbProps(db, H2Driver)

  val seriesTypeDao = new SeriesTypeDAO(dbProps.driver)
  val metaDataDao = new MetaDataDAO(dbProps.driver)
  val propertiesDao = new PropertiesDAO(dbProps.driver)

  db.withSession { implicit session =>
    seriesTypeDao.create
    metaDataDao.create
    propertiesDao.create
  }

  val dataset = TestUtil.testImageDataset()

  val storage = Files.createTempDirectory("slicebox-test-storage-")

  val metaDataActorRef = TestActorRef(new MetaDataServiceActor(dbProps))
  val metaDataActor = metaDataActorRef.underlyingActor

  override def afterAll {
    TestKit.shutdownActorSystem(system)
    TestUtil.deleteFolder(storage)
  }

  val patientEvents = new ListBuffer[Patient]()
  val studyEvents = new ListBuffer[Study]()
  val seriesEvents = new ListBuffer[Series]()
  val imageEvents = new ListBuffer[Image]()

  override def afterEach {
    db.withSession { implicit session => seriesTypeDao.clear; metaDataDao.clear; propertiesDao.clear }
    patientEvents.clear; studyEvents.clear; seriesEvents.clear; imageEvents.clear
  }

  val listeningService = system.actorOf(Props(new Actor {

    override def preStart = {
      context.system.eventStream.subscribe(context.self, classOf[PatientAdded])
      context.system.eventStream.subscribe(context.self, classOf[StudyAdded])
      context.system.eventStream.subscribe(context.self, classOf[SeriesAdded])
      context.system.eventStream.subscribe(context.self, classOf[ImageAdded])
      context.system.eventStream.subscribe(context.self, classOf[PatientDeleted])
      context.system.eventStream.subscribe(context.self, classOf[StudyDeleted])
      context.system.eventStream.subscribe(context.self, classOf[SeriesDeleted])
      context.system.eventStream.subscribe(context.self, classOf[ImageDeleted])
    }

    def receive = {
      case PatientAdded(patient, source) => patientEvents += patient
      case StudyAdded(study, source)     => studyEvents += study
      case SeriesAdded(series, source)   => seriesEvents += series
      case ImageAdded(image, source)     => imageEvents += image
      case PatientDeleted(patientId)     => patientEvents.find(_.id == patientId).foreach(patientEvents -= _)
      case StudyDeleted(studyId)         => studyEvents.find(_.id == studyId).foreach(studyEvents -= _)
      case SeriesDeleted(seriesId)       => seriesEvents.find(_.id == seriesId).foreach(seriesEvents -= _)
      case ImageDeleted(imageId)         => imageEvents.find(_.id == imageId).foreach(imageEvents -= _)
    }
  }))

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

    "emit the approprite xxxAdded events when adding meta data" in {
      val patient = datasetToPatient(dataset)
      val study = datasetToStudy(dataset)
      val series = datasetToSeries(dataset)
      val image = datasetToImage(dataset)
      val source = Source(SourceType.UNKNOWN, "unknown", -1)

      patientEvents shouldBe empty
      studyEvents shouldBe empty
      seriesEvents shouldBe empty
      imageEvents shouldBe empty

      metaDataActorRef ! AddMetaData(patient, study, series, image, source)
      expectMsgType[MetaDataAdded]

      Thread.sleep(500)

      patientEvents should have length 1
      studyEvents should have length 1
      seriesEvents should have length 1
      imageEvents should have length 1

      // changing series level

      metaDataActorRef ! AddMetaData(patient, study, series.copy(seriesInstanceUID = SeriesInstanceUID("seuid2")), image, source)
      expectMsgType[MetaDataAdded]

      Thread.sleep(500)

      patientEvents should have length 1
      studyEvents should have length 1
      seriesEvents should have length 2
      imageEvents should have length 2

      // changing patient level

      metaDataActorRef ! AddMetaData(patient.copy(patientName = PatientName("pat2")), study, series, image, source)
      expectMsgType[MetaDataAdded]

      Thread.sleep(500)

      patientEvents should have length 2
      studyEvents should have length 2
      seriesEvents should have length 3
      imageEvents should have length 3

      // duplicate, changing nothing

      metaDataActorRef ! AddMetaData(patient.copy(patientName = PatientName("pat2")), study, series, image, source)
      expectMsgType[MetaDataAdded]

      Thread.sleep(500)

      patientEvents should have length 2
      studyEvents should have length 2
      seriesEvents should have length 3
      imageEvents should have length 3
    }

    "emit the approprite xxxDeleted events when deleting meta data" in {
      val patient = datasetToPatient(dataset)
      val study = datasetToStudy(dataset)
      val series = datasetToSeries(dataset)
      val image = datasetToImage(dataset)
      val source = Source(SourceType.UNKNOWN, "unknown", -1)

      metaDataActorRef ! AddMetaData(patient, study, series, image, source)
      val image1 = expectMsgPF() { case MetaDataAdded(pat, st, se, im, ss) => im }
      metaDataActorRef ! AddMetaData(patient.copy(patientName = PatientName("pat2")), study, series, image, source)
      val image2 = expectMsgPF() { case MetaDataAdded(pat, st, se, im, ss) => im }
      metaDataActorRef ! AddMetaData(patient, study.copy(studyInstanceUID = StudyInstanceUID("stuid2")), series, image, source)
      val image3 = expectMsgPF() { case MetaDataAdded(pat, st, se, im, ss) => im }
      metaDataActorRef ! AddMetaData(patient, study, series.copy(seriesInstanceUID = SeriesInstanceUID("seuid2")), image, source)
      val image4 = expectMsgPF() { case MetaDataAdded(pat, st, se, im, ss) => im }
      metaDataActorRef ! AddMetaData(patient, study, series, image.copy(sopInstanceUID = SOPInstanceUID("sopuid2")), source)
      val image5 = expectMsgPF() { case MetaDataAdded(pat, st, se, im, ss) => im }
      
      Thread.sleep(500)

      patientEvents should have length 2
      studyEvents should have length 3
      seriesEvents should have length 4
      imageEvents should have length 5
      
      metaDataActorRef ! DeleteMetaData(image5.id)
      expectMsgType[MetaDataDeleted]
      
      Thread.sleep(500)

      patientEvents should have length 2
      studyEvents should have length 3
      seriesEvents should have length 4
      imageEvents should have length 4
      
      metaDataActorRef ! DeleteMetaData(image4.id)
      expectMsgType[MetaDataDeleted]
      
      Thread.sleep(500)

      patientEvents should have length 2
      studyEvents should have length 3
      seriesEvents should have length 3
      imageEvents should have length 3
      
      metaDataActorRef ! DeleteMetaData(image3.id)
      expectMsgType[MetaDataDeleted]
      
      Thread.sleep(500)

      patientEvents should have length 2
      studyEvents should have length 2
      seriesEvents should have length 2
      imageEvents should have length 2
      
      metaDataActorRef ! DeleteMetaData(image2.id)
      expectMsgType[MetaDataDeleted]
      
      Thread.sleep(500)

      patientEvents should have length 1
      studyEvents should have length 1
      seriesEvents should have length 1
      imageEvents should have length 1
      
      metaDataActorRef ! DeleteMetaData(image1.id)
      expectMsgType[MetaDataDeleted]
      
      Thread.sleep(500)

      patientEvents shouldBe empty
      studyEvents shouldBe empty
      seriesEvents shouldBe empty
      imageEvents shouldBe empty
    }

  }

}