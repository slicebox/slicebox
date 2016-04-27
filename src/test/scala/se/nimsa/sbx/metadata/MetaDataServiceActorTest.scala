package se.nimsa.sbx.metadata

import java.nio.file.Files

import akka.actor.{Actor, ActorSystem, Props}
import akka.testkit.{ImplicitSender, TestActorRef, TestKit}
import org.dcm4che3.data.{Attributes, Tag, VR}
import org.scalatest.{BeforeAndAfterAll, BeforeAndAfterEach, Matchers, WordSpecLike}
import se.nimsa.sbx.app.DbProps
import se.nimsa.sbx.app.GeneralProtocol.{Source, SourceType}
import se.nimsa.sbx.dicom.DicomHierarchy._
import se.nimsa.sbx.metadata.MetaDataProtocol._
import se.nimsa.sbx.seriestype.SeriesTypeDAO
import se.nimsa.sbx.util.TestUtil

import scala.collection.mutable.ListBuffer
import scala.slick.driver.H2Driver
import scala.slick.jdbc.JdbcBackend.Database

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
    patientEvents.clear
    studyEvents.clear
    seriesEvents.clear
    imageEvents.clear
  }

  val listeningService = system.actorOf(Props(new Actor {

    override def preStart = {
      context.system.eventStream.subscribe(context.self, classOf[MetaDataAdded])
      context.system.eventStream.subscribe(context.self, classOf[MetaDataDeleted])
    }

    def receive = {
      case MetaDataAdded(patient, study, series, image, patientAdded, studyAdded, seriesAdded, imageAdded, source) =>
        if (patientAdded) patientEvents += patient
        if (studyAdded) studyEvents += study
        if (seriesAdded) seriesEvents += series
        if (imageAdded) imageEvents += image
      case MetaDataDeleted(patientMaybe, studyMaybe, seriesMaybe, imageMaybe) =>
        patientMaybe.foreach(patient => patientEvents.find(_.id == patient.id).foreach(patientEvents -= _))
        studyMaybe.foreach(study => studyEvents.find(_.id == study.id).foreach(studyEvents -= _))
        seriesMaybe.foreach(series => seriesEvents.find(_.id == series.id).foreach(seriesEvents -= _))
        imageMaybe.foreach(image => imageEvents.find(_.id == image.id).foreach(imageEvents -= _))
    }

  }))

  "The meta data service" should {

    "return an empty list of patients when no metadata exists" in {
      metaDataActorRef ! GetPatients(0, 10000, None, orderAscending = true, None, Array.empty, Array.empty, Array.empty)
      expectMsg(Patients(Seq()))
    }

    "return a list of one object when asking for all patients" in {
      val source = Source(SourceType.UNKNOWN, "unknown", -1)
      metaDataActorRef ! AddMetaData(dataset, source)
      expectMsgType[MetaDataAdded]

      metaDataActorRef ! GetPatients(0, 10000, None, orderAscending = true, None, Array.empty, Array.empty, Array.empty)
      expectMsgPF() {
        case Patients(list) if list.size == 1 => true
      }
    }

    "emit the approprite xxxAdded events when adding meta data" in {
      val source = Source(SourceType.UNKNOWN, "unknown", -1)

      patientEvents shouldBe empty
      studyEvents shouldBe empty
      seriesEvents shouldBe empty
      imageEvents shouldBe empty

      metaDataActorRef ! AddMetaData(dataset, source)
      expectMsgType[MetaDataAdded]
      //      val (patient, study, series, image) =
      //      expectMsgPF() {
      //        case MetaDataAdded(patient, study, series, image, seriesSource) => (patient, study, series, image)
      //      }

      Thread.sleep(500)

      patientEvents should have length 1
      studyEvents should have length 1
      seriesEvents should have length 1
      imageEvents should have length 1

      // changing series level

      val dataset2 = new Attributes(dataset)
      dataset2.setString(Tag.SeriesInstanceUID, VR.UI, "seuid2")
      metaDataActorRef ! AddMetaData(dataset2, source)
      expectMsgType[MetaDataAdded]

      Thread.sleep(500)

      patientEvents should have length 1
      studyEvents should have length 1
      seriesEvents should have length 2
      imageEvents should have length 2

      // changing patient level

      val dataset3 = new Attributes(dataset)
      dataset3.setString(Tag.PatientName, VR.PN, "pat2")
      metaDataActorRef ! AddMetaData(dataset3, source)
      expectMsgType[MetaDataAdded]

      Thread.sleep(500)

      patientEvents should have length 2
      studyEvents should have length 2
      seriesEvents should have length 3
      imageEvents should have length 3

      // duplicate, changing nothing

      metaDataActorRef ! AddMetaData(dataset3, source)
      expectMsgType[MetaDataAdded]

      Thread.sleep(500)

      patientEvents should have length 2
      studyEvents should have length 2
      seriesEvents should have length 3
      imageEvents should have length 3
    }

    "emit the approprite xxxDeleted events when deleting meta data" in {
      val source = Source(SourceType.UNKNOWN, "unknown", -1)

      metaDataActorRef ! AddMetaData(dataset, source)
      val image1 = expectMsgPF() { case MetaDataAdded(_, _, _, im, _, _, _, _, _) => im }

      val dataset2 = new Attributes(dataset)
      dataset2.setString(Tag.PatientName, VR.PN, "pat2")
      metaDataActorRef ! AddMetaData(dataset2, source)
      val image2 = expectMsgPF() { case MetaDataAdded(_, _, _, im, _, _, _, _, _) => im }

      val dataset3 = new Attributes(dataset)
      dataset3.setString(Tag.StudyInstanceUID, VR.UI, "stuid2")
      metaDataActorRef ! AddMetaData(dataset3, source)
      val image3 = expectMsgPF() { case MetaDataAdded(_, _, _, im, _, _, _, _, _) => im }

      val dataset4 = new Attributes(dataset)
      dataset4.setString(Tag.SeriesInstanceUID, VR.UI, "seuid2")
      metaDataActorRef ! AddMetaData(dataset4, source)
      val image4 = expectMsgPF() { case MetaDataAdded(_, _, _, im, _, _, _, _, _) => im }

      val dataset5 = new Attributes(dataset)
      dataset5.setString(Tag.SOPInstanceUID, VR.UI, "sopuid2")
      metaDataActorRef ! AddMetaData(dataset5, source)
      val image5 = expectMsgPF() { case MetaDataAdded(_, _, _, im, _, _, _, _, _) => im }

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