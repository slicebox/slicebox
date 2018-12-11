package se.nimsa.sbx.dicom.streams

import akka.NotUsed
import akka.actor.{ActorRef, ActorSystem, Cancellable}
import akka.pattern.ask
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.{Source => StreamSource}
import akka.testkit.TestKit
import akka.util.{ByteString, Timeout}
import org.scalatest.{AsyncFlatSpecLike, BeforeAndAfterAll, BeforeAndAfterEach, Matchers}
import se.nimsa.dicom.data._
import se.nimsa.dicom.streams.ModifyFlow.{TagInsertion, TagModification, modifyFlow}
import se.nimsa.dicom.streams.{DicomFlows, DicomStreamException, ElementFlows, ElementSink}
import se.nimsa.sbx.anonymization.AnonymizationProtocol._
import se.nimsa.sbx.anonymization.{AnonymizationDAO, AnonymizationProfile, AnonymizationServiceActor, ConfidentialityOption}
import se.nimsa.sbx.app.GeneralProtocol.{Source, SourceType}
import se.nimsa.sbx.dicom.Contexts
import se.nimsa.sbx.dicom.DicomHierarchy.DicomHierarchyLevel
import se.nimsa.sbx.filtering.{FilteringDAO, FilteringServiceActor}
import se.nimsa.sbx.metadata.MetaDataProtocol._
import se.nimsa.sbx.metadata.{MetaDataDAO, MetaDataServiceActor, PropertiesDAO}
import se.nimsa.sbx.storage.RuntimeStorage
import se.nimsa.sbx.util.FutureUtil.await
import se.nimsa.sbx.util.TestUtil

import scala.collection.immutable.Seq
import scala.concurrent.duration.{DurationInt, FiniteDuration}
import scala.concurrent.{ExecutionContextExecutor, Future}
import scala.reflect.ClassTag

class DicomStreamOpsTest extends TestKit(ActorSystem("DicomStreamOpsSpec")) with AsyncFlatSpecLike with Matchers with BeforeAndAfterAll with BeforeAndAfterEach {

  import DicomTestData._

  implicit val materializer: ActorMaterializer = ActorMaterializer()
  implicit val ec: ExecutionContextExecutor = system.dispatcher
  implicit val timeout: Timeout = Timeout(30.seconds)

  val dbConfig = TestUtil.createTestDb("dicomstreamopstest")

  val profile = AnonymizationProfile(Seq(ConfidentialityOption.BASIC_PROFILE))

  val metaDataDao = new MetaDataDAO(dbConfig)(ec)
  val propertiesDao = new PropertiesDAO(dbConfig)(ec)
  val anonymizationDao = new AnonymizationDAO(dbConfig)(ec)
  val filteringDao = new FilteringDAO(dbConfig)(ec)

  val metaDataService: ActorRef = system.actorOf(MetaDataServiceActor.props(metaDataDao, propertiesDao), name = "MetaDataService")
  val anonymizationService: ActorRef = system.actorOf(AnonymizationServiceActor.props(anonymizationDao, purgeEmptyAnonymizationKeys = true), name = "AnonymizationService")
  val filteringService: ActorRef = system.actorOf(FilteringServiceActor.props(filteringDao), name = "FilteringService")

  override def beforeAll(): Unit = {
    await(metaDataDao.create())
    await(propertiesDao.create())
    await(anonymizationDao.create())
    await(filteringDao.create())
  }

  override def afterAll: Unit = TestKit.shutdownActorSystem(system)

  override def afterEach(): Unit = {
    await(propertiesDao.clear())
    await(metaDataDao.clear())
    await(anonymizationDao.clear())
    await(filteringDao.clear())
  }

  val storage = new RuntimeStorage

  class DicomStreamOpsImpl extends DicomStreamOps {

    override def callAnonymizationService[R: ClassTag](message: Any): Future[R] = anonymizationService.ask(message).mapTo[R]
    override def callMetaDataService[R: ClassTag](message: Any): Future[R] = metaDataService.ask(message).mapTo[R]
    override def callFilteringService[R: ClassTag](message: Any): Future[R] = filteringService.ask(message).mapTo[R]
    override def scheduleTask(delay: FiniteDuration)(task: => Unit): Cancellable = system.scheduler.scheduleOnce(delay)(task)

    def storeDicomData(bytesSource: StreamSource[ByteString, _], source: Source): Future[MetaDataAdded] =
      storeDicomData(bytesSource, source, storage, Contexts.imageDataContexts, reverseAnonymization = true)

    def anonymizedDicomData(imageId: Long, customTagValues: Seq[TagValue]): StreamSource[ByteString, NotUsed] =
      anonymizedDicomData(imageId, profile, customTagValues, storage)

    def modifyData(imageId: Long, tagModifications: Seq[TagInsertion]): Future[(MetaDataDeleted, MetaDataAdded)] =
      modifyData(imageId, tagModifications, storage)
  }

  val dicomStreamOpsImpl = new DicomStreamOpsImpl()

  def toSource(elements: Elements): StreamSource[ByteString, NotUsed] = StreamSource.single(elements.toBytes())

  "Validating a DICOM file" should "throw an exception for a non-supported context" in {
    val bytes = preamble ++ fmiGroupLength(unsupportedMediaStorageSOPClassUID ++ tsuidExplicitLE) ++ unsupportedMediaStorageSOPClassUID ++ tsuidExplicitLE ++ patientNameJohnDoe
    val source = StreamSource.single(bytes)
    recoverToSucceededIf[DicomStreamException] {
      source.runWith(dicomStreamOpsImpl.dicomDataSink(storage.fileSink("name"), storage.parseFlow(None), _ => Future.successful(AnonymizationKeyOpResult.empty), Contexts.imageDataContexts))
    }
  }

  it should "pass a supported context" in {
    val bytes = preamble ++ fmiGroupLength(supportedMediaStorageSOPClassUID ++ tsuidExplicitLE) ++ supportedMediaStorageSOPClassUID ++ tsuidExplicitLE ++ patientNameJohnDoe
    val source = StreamSource.single(bytes)
    source.runWith(dicomStreamOpsImpl.dicomDataSink(storage.fileSink("name"), storage.parseFlow(None), _ => Future.successful(AnonymizationKeyOpResult.empty), Contexts.imageDataContexts))
      .map(_ => succeed)
  }

  it should "throw an exception for a context with an unknown SOP Class UID" in {
    val bytes = preamble ++ fmiGroupLength(unknownMediaStorageSOPClassUID ++ tsuidExplicitLE) ++ unknownMediaStorageSOPClassUID ++ tsuidExplicitLE ++ patientNameJohnDoe
    val source = StreamSource.single(bytes)
    recoverToSucceededIf[DicomStreamException] {
      source.runWith(dicomStreamOpsImpl.dicomDataSink(storage.fileSink("name"), storage.parseFlow(None), _ => Future.successful(AnonymizationKeyOpResult.empty), Contexts.imageDataContexts))
    }
  }

  it should "accept DICOM data with missing file meta information" in {
    val bytes = supportedSOPClassUID
    val source = StreamSource.single(bytes)
    source.runWith(dicomStreamOpsImpl.dicomDataSink(storage.fileSink("name"), storage.parseFlow(None), _ => Future.successful(AnonymizationKeyOpResult.empty), Contexts.imageDataContexts))
      .map(_ => succeed)
  }

  "Applying tag modifications when storing DICOM data" should "replace DICOM attributes" in {
    val t1 = TagValue(TagPath.fromTag(Tag.PatientName), "Mapped Patient Name")
    val t2 = TagValue(TagPath.fromTag(Tag.PatientID), "Mapped Patient ID")
    val t3 = TagValue(TagPath.fromTag(Tag.SeriesDescription), "Mapped Series Description")
    val bytes = preamble ++ fmiGroupLength(supportedMediaStorageSOPClassUID ++ tsuidExplicitLE) ++ supportedMediaStorageSOPClassUID ++ tsuidExplicitLE ++ patientNameJohnDoe
    val source = StreamSource.single(bytes).via(storage.parseFlow(None))
    val anonSource = dicomStreamOpsImpl.anonymizedDicomDataSource(
      source,
      _ => Future.successful(AnonymizationKeyOpResult(DicomHierarchyLevel.SERIES, Some(TestUtil.createAnonymizationKey(Elements.empty())), Seq.empty)),
      profile, Seq(t1, t2, t3))

    anonSource.via(storage.parseFlow(None)).via(ElementFlows.elementFlow).runWith(ElementSink.elementSink).map { elements =>
      elements.getString(Tag.PatientName).get should be("Mapped Patient Name")
      elements.getString(Tag.PatientID).get should be("Mapped Patient ID")
      elements.getString(Tag.SeriesDescription).get should be("Mapped Series Description")
    }
  }

  "Harmonizing DICOM attributes when storing DICOM data" should "replace attributes according to existing anonymization keys" in {
    val elements = testElements
    val key = TestUtil.createAnonymizationKey(elements, anonPatientName = "apn", anonPatientID = "apid", anonSeriesInstanceUID = "aseuid")

    val source = toSource(elements).via(storage.parseFlow(None))
    val anonSource = dicomStreamOpsImpl.anonymizedDicomDataSource(source, _ => Future.successful(
      AnonymizationKeyOpResult(DicomHierarchyLevel.SERIES, Some(key), Seq(
        AnonymizationKeyValue(-1, key.id, TagPath.fromTag(Tag.PatientName), key.patientName, key.anonPatientName),
        AnonymizationKeyValue(-1, key.id, TagPath.fromTag(Tag.PatientID), key.patientID, key.anonPatientID),
        AnonymizationKeyValue(-1, key.id, TagPath.fromTag(Tag.SeriesInstanceUID), key.seriesInstanceUID, key.anonSeriesInstanceUID)
      ))), profile, Seq.empty)

    anonSource.via(storage.parseFlow(None)).via(ElementFlows.elementFlow).runWith(ElementSink.elementSink).map { elements =>
      elements.getString(Tag.PatientName).get should be(key.anonPatientName)
      elements.getString(Tag.PatientID).get should be(key.anonPatientID)
      elements.getString(Tag.SeriesInstanceUID).get should be(key.anonSeriesInstanceUID)
      elements.getString(Tag.Allergies) shouldBe empty
      elements.getString(Tag.PatientIdentityRemoved).get shouldBe "YES"
    }
  }

  "The DICOM data source with anonymization" should "anonymize the patient ID and harmonize it according to an anonymization key" in {
    val elements = TestUtil.createElements()
    val key = TestUtil.createAnonymizationKey(elements)
    val source = toSource(elements).via(storage.parseFlow(None))

    val anonSource = dicomStreamOpsImpl.anonymizedDicomDataSource(source, _ => Future.successful(AnonymizationKeyOpResult(DicomHierarchyLevel.SERIES, Some(key), Seq(
      AnonymizationKeyValue(-1, key.id, TagPath.fromTag(Tag.PatientName), key.patientName, key.anonPatientName),
      AnonymizationKeyValue(-1, key.id, TagPath.fromTag(Tag.PatientID), key.patientID, key.anonPatientID),
      AnonymizationKeyValue(-1, key.id, TagPath.fromTag(Tag.SeriesInstanceUID), key.seriesInstanceUID, key.anonSeriesInstanceUID)
    ))), profile, Seq.empty)

    anonSource.via(storage.parseFlow(None)).via(ElementFlows.elementFlow).runWith(ElementSink.elementSink).map { elements =>
      elements.getString(Tag.PatientID).get shouldBe key.anonPatientID
    }
  }

  "An anonymized data source" should "create anonymization record for first image and harmonize subsequent images" in {
    val source = Source(SourceType.USER, "Jane", -1)
    val testData = TestUtil.testImageBytes
    val bytesSource = StreamSource.single(testData)

    // store 100 files in the same series
    val sopInstanceUIDs = 1 to 100
    val storedImageIds = Future.sequence {
      sopInstanceUIDs.map { sopInstanceUID =>
        val modifiedBytesSource = bytesSource
          .via(storage.parseFlow(None))
          .via(DicomFlows.blacklistFilter(Set(TagTree.fromTag(Tag.PixelData))))
          .via(modifyFlow(modifications = Seq(
            TagModification.equals(
              TagPath.fromTag(Tag.MediaStorageSOPInstanceUID),
              uid => uid.dropRight(3) ++ ByteString(f"$sopInstanceUID%03d")),
            TagModification.equals(
              TagPath.fromTag(Tag.SOPInstanceUID),
              uid => uid.dropRight(3) ++ ByteString(f"$sopInstanceUID%03d")))))
          .map(_.bytes)
        dicomStreamOpsImpl.storeDicomData(modifiedBytesSource, source)
          .map(_.image.id)
      }
    }

    // anonymize them in parallel
    val anonymousPatientIds = storedImageIds.flatMap { imageIds =>
      Future.sequence {
        imageIds.map { imageId =>
          dicomStreamOpsImpl.anonymizedDicomData(imageId, Seq.empty)
            .via(storage.parseFlow(None))
            .via(ElementFlows.elementFlow)
            .runWith(ElementSink.elementSink)
        }
      }
    }.map(_.map(elements => elements.getString(Tag.PatientID).get))

    anonymousPatientIds.map(_.toSet).map { uniqueUids =>
      uniqueUids should have size 1
    }
  }

  "Streaming storage of DICOM data" should "store the correct meta data and binary data under the expected name" in {
    val source = Source(SourceType.USER, "Jane", -1)
    val elements = TestUtil.testImageDicomData()
    val bytesSource = StreamSource.single(TestUtil.toBytes(elements))
    dicomStreamOpsImpl.storeDicomData(bytesSource, source).map { metaDataAdded =>
      storage.storage.get(storage.imageName(metaDataAdded.image.id)) shouldBe defined
      metaDataAdded.patient.patientName.value shouldBe elements.getString(Tag.PatientName).get
      metaDataAdded.patient.patientID.value shouldBe elements.getString(Tag.PatientID).get
      metaDataAdded.study.studyInstanceUID.value shouldBe elements.getString(Tag.StudyInstanceUID).get
      metaDataAdded.series.seriesInstanceUID.value shouldBe elements.getString(Tag.SeriesInstanceUID).get
      metaDataAdded.image.sopInstanceUID.value shouldBe elements.getString(Tag.SOPInstanceUID).get
    }
  }

  it should "reverse anonymization if data is anonymous and anonymization key exists" in {
    val source = Source(SourceType.USER, "Jane", -1)
    val elements = TestUtil.testImageDicomData()
      .setString(Tag.PatientIdentityRemoved, "YES")
    val realName = "Real Name"
    val realID = "Real ID"
    val anonKey = TestUtil.createAnonymizationKey(
      elements,
      anonPatientName = elements.getString(Tag.PatientName).get,
      anonPatientID = elements.getString(Tag.PatientID).get
    ).copy(patientName = realName, patientID = realID)
    dicomStreamOpsImpl.callAnonymizationService[AnonymizationKeyOpResult](InsertAnonymizationKeyValues(42, Set(
      AnonymizationKeyValueData(DicomHierarchyLevel.PATIENT, TagPath.fromTag(Tag.PatientName), anonKey.patientName, anonKey.anonPatientName),
      AnonymizationKeyValueData(DicomHierarchyLevel.PATIENT, TagPath.fromTag(Tag.PatientID), anonKey.patientID, anonKey.anonPatientID)
    ))).flatMap { _ =>
      val bytesSource = StreamSource.single(TestUtil.toBytes(elements))
      dicomStreamOpsImpl.storeDicomData(bytesSource, source).map { metaDataAdded =>
        metaDataAdded.patient.patientName.value shouldBe realName
        metaDataAdded.patient.patientID.value shouldBe realID
      }
    }
  }

  it should "not reverse anonymization if data is not anonymous" in {
    val source = Source(SourceType.USER, "Jane", -1)
    val testData = TestUtil.testImageDicomData()
    val bytesSource = StreamSource.single(TestUtil.toBytes(testData))
    dicomStreamOpsImpl.storeDicomData(bytesSource, source).map { metaDataAdded =>
      metaDataAdded.patient.patientName.value shouldBe testData.getString(Tag.PatientName).get
    }
  }

  "Modifying DICOM data" should "remove the old data and replace it with new" in {
    val source = Source(SourceType.USER, "Jane", -1)
    val testData = TestUtil.testImageDicomData()
    val bytesSource = StreamSource.single(TestUtil.toBytes(testData))
    for {
      metaDataAdded1 <- dicomStreamOpsImpl.storeDicomData(bytesSource, source)
      (_, metaDataAdded2) <- dicomStreamOpsImpl.modifyData(metaDataAdded1.image.id, Seq.empty)
    } yield {
      storage.storage.get(storage.imageName(metaDataAdded1.image.id)) should not be defined
      storage.storage.get(storage.imageName(metaDataAdded2.image.id)) shouldBe defined
    }
  }

  it should "modify data according to input tag modifications" in {
    val newName = "New Name"
    val source = Source(SourceType.USER, "Jane", -1)
    val testData = TestUtil.testImageDicomData()
    val bytesSource = StreamSource.single(TestUtil.toBytes(testData))
    for {
      metaDataAdded1 <- dicomStreamOpsImpl.storeDicomData(bytesSource, source)
      (_, metaDataAdded2) <- dicomStreamOpsImpl.modifyData(metaDataAdded1.image.id, Seq(TagInsertion(TagPath.fromTag(Tag.PatientName), _ => ByteString(newName))))
    } yield {
      metaDataAdded2.patient.patientName.value shouldBe newName
    }
  }

  it should "transfer series source and tags to new, modified data" in {
    val source = Source(SourceType.USER, "Jane", -1)
    val testData = TestUtil.testImageDicomData()
    val bytesSource = StreamSource.single(TestUtil.toBytes(testData))
    for {
      metaDataAdded1 <- dicomStreamOpsImpl.storeDicomData(bytesSource, source)
      _ <- dicomStreamOpsImpl.callMetaDataService[SeriesTagAddedToSeries](AddSeriesTagToSeries(SeriesTag(42, "tag1"), metaDataAdded1.series.id))
      (_, metaDataAdded2) <- dicomStreamOpsImpl.modifyData(metaDataAdded1.image.id, Seq.empty)
      oldSource <- dicomStreamOpsImpl.callMetaDataService[Option[SeriesSource]](GetSourceForSeries(metaDataAdded1.series.id))
      newSource <- dicomStreamOpsImpl.callMetaDataService[Option[SeriesSource]](GetSourceForSeries(metaDataAdded2.series.id))
      oldSeriesTags <- dicomStreamOpsImpl.callMetaDataService[SeriesTags](GetSeriesTagsForSeries(metaDataAdded1.series.id))
      newSeriesTags <- dicomStreamOpsImpl.callMetaDataService[SeriesTags](GetSeriesTagsForSeries(metaDataAdded2.series.id))
    } yield {
      oldSource shouldBe empty
      oldSeriesTags.seriesTags shouldBe empty
      newSource shouldBe defined
      newSeriesTags.seriesTags should have length 1
    }
  }

}
