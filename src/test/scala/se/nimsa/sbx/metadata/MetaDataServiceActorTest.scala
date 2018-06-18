package se.nimsa.sbx.metadata

import akka.actor.{Actor, ActorRef, ActorSystem, Props}
import akka.stream.ActorMaterializer
import akka.testkit.{ImplicitSender, TestActorRef, TestKit}
import akka.util.Timeout
import org.dcm4che3.data.Tag
import org.scalatest.{BeforeAndAfterAll, BeforeAndAfterEach, Matchers, WordSpecLike}
import se.nimsa.dicom.data.Elements
import se.nimsa.sbx.app.GeneralProtocol.{Source, SourceType}
import se.nimsa.sbx.dicom.DicomHierarchy._
import se.nimsa.sbx.metadata.MetaDataProtocol._
import se.nimsa.sbx.seriestype.SeriesTypeDAO
import se.nimsa.sbx.util.FutureUtil.await
import se.nimsa.sbx.util.TestUtil

import scala.collection.mutable.ListBuffer
import scala.concurrent.duration.DurationInt
import scala.concurrent.{ExecutionContextExecutor, Future}

class MetaDataServiceActorTest extends TestKit(ActorSystem("MetaDataTestSystem")) with ImplicitSender
  with WordSpecLike with Matchers with BeforeAndAfterAll with BeforeAndAfterEach {

  implicit val materializer: ActorMaterializer = ActorMaterializer()
  implicit val ec: ExecutionContextExecutor = system.dispatcher
  implicit val timeout: Timeout = Timeout(30.seconds)

  val dbConfig = TestUtil.createTestDb("metadataserviceactortest")
  val db = dbConfig.db

  val seriesTypeDao = new SeriesTypeDAO(dbConfig)
  val metaDataDao = new MetaDataDAO(dbConfig)
  val propertiesDao = new PropertiesDAO(dbConfig)

  val elements: Elements = TestUtil.testImageDicomData()

  val metaDataActorRef = TestActorRef(new MetaDataServiceActor(metaDataDao, propertiesDao))
  val metaDataActor: MetaDataServiceActor = metaDataActorRef.underlyingActor

  val patientEvents = new ListBuffer[Patient]()
  val studyEvents = new ListBuffer[Study]()
  val seriesEvents = new ListBuffer[Series]()
  val imageEvents = new ListBuffer[Image]()

  override def beforeAll(): Unit = {
    await(metaDataDao.create())
    await(propertiesDao.create())
    await(seriesTypeDao.create())
  }

  override def afterAll: Unit = TestKit.shutdownActorSystem(system)

  override def afterEach: Unit = {
    patientEvents.clear()
    studyEvents.clear()
    seriesEvents.clear()
    imageEvents.clear()
    await(Future.sequence(Seq(
      seriesTypeDao.clear(),
      metaDataDao.clear(),
      propertiesDao.clear()
    )))
  }

  val listeningService: ActorRef = system.actorOf(Props(new Actor {

    override def preStart: Unit = {
      context.system.eventStream.subscribe(context.self, classOf[MetaDataAdded])
      context.system.eventStream.subscribe(context.self, classOf[MetaDataDeleted])
    }

    def receive: Receive = {
      case MetaDataAdded(patient, study, series, image, patientAdded, studyAdded, seriesAdded, imageAdded, _) =>
        if (patientAdded) patientEvents += patient
        if (studyAdded) studyEvents += study
        if (seriesAdded) seriesEvents += series
        if (imageAdded) imageEvents += image
      case MetaDataDeleted(patientIds, studyIds, seriesIds, imageIds) =>
        patientEvents.filter(e => patientIds.contains(e.id)).foreach(patientEvents -= _)
        studyEvents.filter(e => studyIds.contains(e.id)).foreach(studyEvents -= _)
        seriesEvents.filter(e => seriesIds.contains(e.id)).foreach(seriesEvents -= _)
        imageEvents.filter(e => imageIds.contains(e.id)).foreach(imageEvents -= _)
    }

  }))

  "The meta data service" should {

    "return an empty list of patients when no metadata exists" in {
      metaDataActorRef ! GetPatients(0, 10000, None, orderAscending = true, None, Array.empty, Array.empty, Array.empty)
      expectMsg(Patients(Seq()))
    }

    "return a list of one object when asking for all patients" in {
      val source = Source(SourceType.UNKNOWN, "unknown", -1)
      metaDataActorRef ! AddMetaData(elements, source)
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

      metaDataActorRef ! AddMetaData(elements, source)
      expectMsgType[MetaDataAdded]

      Thread.sleep(500)

      patientEvents should have length 1
      studyEvents should have length 1
      seriesEvents should have length 1
      imageEvents should have length 1

      // changing series level

      val elements2 = elements.setString(Tag.SeriesInstanceUID, "seuid2")
      metaDataActorRef ! AddMetaData(elements2, source)
      expectMsgType[MetaDataAdded]

      Thread.sleep(500)

      patientEvents should have length 1
      studyEvents should have length 1
      seriesEvents should have length 2
      imageEvents should have length 2

      // changing patient level

      val elements3 = elements.setString(Tag.PatientName, "pat2")
      metaDataActorRef ! AddMetaData(elements3, source)
      expectMsgType[MetaDataAdded]

      Thread.sleep(500)

      patientEvents should have length 2
      studyEvents should have length 2
      seriesEvents should have length 3
      imageEvents should have length 3

      // duplicate, changing nothing

      metaDataActorRef ! AddMetaData(elements3, source)
      expectMsgType[MetaDataAdded]

      Thread.sleep(500)

      patientEvents should have length 2
      studyEvents should have length 2
      seriesEvents should have length 3
      imageEvents should have length 3
    }

    "emit the approprite xxxDeleted events when deleting meta data" in {
      val source = Source(SourceType.UNKNOWN, "unknown", -1)

      metaDataActorRef ! AddMetaData(elements, source)
      val image1 = expectMsgPF() { case MetaDataAdded(_, _, _, im, _, _, _, _, _) => im }

      val elements2 = elements.setString(Tag.PatientName, "pat2")
      metaDataActorRef ! AddMetaData(elements2, source)
      val image2 = expectMsgPF() { case MetaDataAdded(_, _, _, im, _, _, _, _, _) => im }

      val elements3 = elements.setString(Tag.StudyInstanceUID, "stuid2")
      metaDataActorRef ! AddMetaData(elements3, source)
      val image3 = expectMsgPF() { case MetaDataAdded(_, _, _, im, _, _, _, _, _) => im }

      val elements4 = elements.setString(Tag.SeriesInstanceUID, "seuid2")
      metaDataActorRef ! AddMetaData(elements4, source)
      val image4 = expectMsgPF() { case MetaDataAdded(_, _, _, im, _, _, _, _, _) => im }

      val elements5 = elements.setString(Tag.SOPInstanceUID, "sopuid2")
      metaDataActorRef ! AddMetaData(elements5, source)
      val image5 = expectMsgPF() { case MetaDataAdded(_, _, _, im, _, _, _, _, _) => im }

      Thread.sleep(500)

      patientEvents should have length 2
      studyEvents should have length 3
      seriesEvents should have length 4
      imageEvents should have length 5

      metaDataActorRef ! DeleteMetaData(Seq(image5.id))
      expectMsgType[MetaDataDeleted]

      Thread.sleep(500)

      patientEvents should have length 2
      studyEvents should have length 3
      seriesEvents should have length 4
      imageEvents should have length 4

      metaDataActorRef ! DeleteMetaData(Seq(image4.id))
      expectMsgType[MetaDataDeleted]

      Thread.sleep(500)

      patientEvents should have length 2
      studyEvents should have length 3
      seriesEvents should have length 3
      imageEvents should have length 3

      metaDataActorRef ! DeleteMetaData(Seq(image3.id))
      expectMsgType[MetaDataDeleted]

      Thread.sleep(500)

      patientEvents should have length 2
      studyEvents should have length 2
      seriesEvents should have length 2
      imageEvents should have length 2

      metaDataActorRef ! DeleteMetaData(Seq(image2.id))
      expectMsgType[MetaDataDeleted]

      Thread.sleep(500)

      patientEvents should have length 1
      studyEvents should have length 1
      seriesEvents should have length 1
      imageEvents should have length 1

      metaDataActorRef ! DeleteMetaData(Seq(image1.id))
      expectMsgType[MetaDataDeleted]

      Thread.sleep(500)

      patientEvents shouldBe empty
      studyEvents shouldBe empty
      seriesEvents shouldBe empty
      imageEvents shouldBe empty
    }

    "support updating metadata without creating new metadata instances if key elements are unchanged" in {
      val source = Source(SourceType.UNKNOWN, "unknown", -1)

      metaDataActorRef ! AddMetaData(elements, source)
      expectMsgType[MetaDataAdded]

      val elements2 = elements
        .setString(Tag.PatientBirthDate, "new date")
        .setString(Tag.StudyID, "new id")
        .setString(Tag.Modality, "new modality")
        .setString(Tag.InstanceNumber, "666")

      metaDataActorRef ! AddMetaData(elements2, source)
      expectMsgType[MetaDataAdded]

      await(metaDataDao.patients) should have length 1
      await(metaDataDao.studies) should have length 1
      await(metaDataDao.series) should have length 1
      await(metaDataDao.images) should have length 1

      await(metaDataDao.patients).head.patientBirthDate.value shouldBe "new date"
      await(metaDataDao.studies).head.studyID.value shouldBe "new id"
      await(metaDataDao.series).head.modality.value shouldBe "new modality"
      await(metaDataDao.images).head.instanceNumber.value shouldBe "666"
    }

    "support updating metadata and creating new metadata instances if key elements are changed" in {
      val source1 = Source(SourceType.UNKNOWN, "unknown", -1)
      val source2 = Source(SourceType.SCP, "scp", -1)

      metaDataActorRef ! AddMetaData(elements, source1)
      expectMsgType[MetaDataAdded]

      val elements2 = elements
        .setString(Tag.SeriesInstanceUID, "new ui")

      metaDataActorRef ! AddMetaData(elements2, source2)
      expectMsgType[MetaDataAdded]

      await(metaDataDao.patients) should have length 1
      await(metaDataDao.studies) should have length 1
      await(metaDataDao.series) should have length 2
      await(metaDataDao.images) should have length 2

      val seriesSources = await(propertiesDao.seriesSources)
      seriesSources should have length 2
      seriesSources.head.source shouldBe source1
      seriesSources(1).source shouldBe source2
    }

  }

}