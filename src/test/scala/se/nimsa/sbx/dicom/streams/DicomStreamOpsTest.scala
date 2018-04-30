package se.nimsa.sbx.dicom.streams

import akka.NotUsed
import akka.actor.{ActorRef, ActorSystem, Cancellable}
import akka.pattern.ask
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.{Source => StreamSource}
import akka.testkit.TestKit
import akka.util.{ByteString, Timeout}
import org.scalatest.{AsyncFlatSpecLike, BeforeAndAfterAll, BeforeAndAfterEach, Matchers}
import se.nimsa.dicom._
import se.nimsa.dicom.streams.ModifyFlow.{TagModification, modifyFlow}
import se.nimsa.dicom.streams.{DicomFlows, DicomStreamException, ElementFolds}
import se.nimsa.sbx.anonymization.AnonymizationProtocol._
import se.nimsa.sbx.anonymization.{AnonymizationDAO, AnonymizationServiceActor}
import se.nimsa.sbx.app.GeneralProtocol.{Source, SourceType}
import se.nimsa.sbx.dicom.Contexts
import se.nimsa.sbx.dicom.streams.DicomStreamUtil.{DicomInfoPart, PartialAnonymizationKeyPart}
import se.nimsa.sbx.metadata.MetaDataProtocol._
import se.nimsa.sbx.metadata.{MetaDataDAO, MetaDataServiceActor, PropertiesDAO}
import se.nimsa.sbx.storage.RuntimeStorage
import se.nimsa.sbx.util.FutureUtil.await
import se.nimsa.sbx.util.TestUtil

import scala.concurrent.duration.{DurationInt, FiniteDuration}
import scala.concurrent.{ExecutionContextExecutor, Future}
import scala.reflect.ClassTag

class DicomStreamOpsTest extends TestKit(ActorSystem("DicomStreamOpsSpec")) with AsyncFlatSpecLike with Matchers with BeforeAndAfterAll with BeforeAndAfterEach {

  import DicomTestData._

  implicit val materializer: ActorMaterializer = ActorMaterializer()
  implicit val ec: ExecutionContextExecutor = system.dispatcher
  implicit val timeout: Timeout = Timeout(30.seconds)

  val dbConfig = TestUtil.createTestDb("dicomstreamopstest")

  val metaDataDao = new MetaDataDAO(dbConfig)(ec)
  val propertiesDao = new PropertiesDAO(dbConfig)(ec)
  val anonymizationDao = new AnonymizationDAO(dbConfig)(ec)

  val metaDataService: ActorRef = system.actorOf(MetaDataServiceActor.props(metaDataDao, propertiesDao), name = "MetaDataService")
  val anonymizationService: ActorRef = system.actorOf(AnonymizationServiceActor.props(anonymizationDao, purgeEmptyAnonymizationKeys = true), name = "AnonymizationService")

  override def beforeAll(): Unit = {
    await(metaDataDao.create())
    await(propertiesDao.create())
    await(anonymizationDao.create())
  }

  override def afterAll: Unit = TestKit.shutdownActorSystem(system)

  override def afterEach(): Unit = {
    await(propertiesDao.clear())
    await(metaDataDao.clear())
    await(anonymizationDao.clear())
  }

  val storage = new RuntimeStorage

  class DicomStreamOpsImpl extends DicomStreamOps {

    override def callAnonymizationService[R: ClassTag](message: Any): Future[R] = anonymizationService.ask(message).mapTo[R]
    override def callMetaDataService[R: ClassTag](message: Any): Future[R] = metaDataService.ask(message).mapTo[R]
    override def scheduleTask(delay: FiniteDuration)(task: => Unit): Cancellable = system.scheduler.scheduleOnce(delay)(task)

    def storeDicomData(bytesSource: StreamSource[ByteString, _], source: Source): Future[MetaDataAdded] =
      storeDicomData(bytesSource, source, storage, Contexts.imageDataContexts, reverseAnonymization = true)

    def anonymizedDicomData(imageId: Long, tagValues: Seq[TagValue]): StreamSource[ByteString, NotUsed] =
      anonymizedDicomData(imageId, tagValues, storage)

    def modifyData(imageId: Long, tagModifications: Seq[TagModification]): Future[(MetaDataDeleted, MetaDataAdded)] =
      modifyData(imageId, tagModifications, storage)
  }

  val dicomStreamOpsImpl = new DicomStreamOpsImpl()

  def toSource(elements: Elements): StreamSource[ByteString, NotUsed] = StreamSource.single(elements.bytes)

  "Validating a DICOM file" should "throw an exception for a non-supported context" in {
    val bytes = preamble ++ fmiGroupLength(unsupportedMediaStorageSOPClassUID ++ tsuidExplicitLE) ++ unsupportedMediaStorageSOPClassUID ++ tsuidExplicitLE ++ patientNameJohnDoe
    val source = StreamSource.single(bytes)
    recoverToSucceededIf[DicomStreamException] {
      source.runWith(dicomStreamOpsImpl.dicomDataSink(storage.fileSink("name"), storage.parseFlow(None), (_, _) => Future.successful(Seq.empty), Contexts.imageDataContexts, reverseAnonymization = true))
    }
  }

  it should "pass a supported context" in {
    val bytes = preamble ++ fmiGroupLength(supportedMediaStorageSOPClassUID ++ tsuidExplicitLE) ++ supportedMediaStorageSOPClassUID ++ tsuidExplicitLE ++ patientNameJohnDoe
    val source = StreamSource.single(bytes)
    source.runWith(dicomStreamOpsImpl.dicomDataSink(storage.fileSink("name"), storage.parseFlow(None), (_, _) => Future.successful(Seq.empty), Contexts.imageDataContexts, reverseAnonymization = true))
      .map(_ => succeed)
  }

  it should "throw an exception for a context with an unknown SOP Class UID" in {
    val bytes = preamble ++ fmiGroupLength(unknownMediaStorageSOPClassUID ++ tsuidExplicitLE) ++ unknownMediaStorageSOPClassUID ++ tsuidExplicitLE ++ patientNameJohnDoe
    val source = StreamSource.single(bytes)
    recoverToSucceededIf[DicomStreamException] {
      source.runWith(dicomStreamOpsImpl.dicomDataSink(storage.fileSink("name"), storage.parseFlow(None), (_, _) => Future.successful(Seq.empty), Contexts.imageDataContexts, reverseAnonymization = true))
    }
  }

  it should "accept DICOM data with missing file meta information" in {
    val bytes = supportedSOPClassUID
    val source = StreamSource.single(bytes)
    source.runWith(dicomStreamOpsImpl.dicomDataSink(storage.fileSink("name"), storage.parseFlow(None), (_, _) => Future.successful(Seq.empty), Contexts.imageDataContexts, reverseAnonymization = true))
      .map(_ => succeed)
  }

  "Applying tag modifications when storing DICOM data" should "replace DICOM attributes" in {
    val t1 = TagValue(Tag.PatientName, "Mapped Patient Name")
    val t2 = TagValue(Tag.PatientID, "Mapped Patient ID")
    val t3 = TagValue(Tag.SeriesDescription, "Mapped Series Description")
    val bytes = preamble ++ fmiGroupLength(supportedMediaStorageSOPClassUID ++ tsuidExplicitLE) ++ supportedMediaStorageSOPClassUID ++ tsuidExplicitLE ++ patientNameJohnDoe
    val source = StreamSource.single(bytes).via(storage.parseFlow(None))
    val anonSource = dicomStreamOpsImpl.anonymizedDicomDataSource(
      source,
      _ => Future.successful(TestUtil.createAnonymizationKey(Elements.empty)),
      Seq(t1, t2, t3))

    anonSource.via(storage.parseFlow(None)).via(ElementFolds.elementsFlow).runWith(ElementFolds.elementsSink).map { elements =>
      elements(Tag.PatientName).get.toSingleString() should be("Mapped Patient Name")
      elements(Tag.PatientID).get.toSingleString() should be("Mapped Patient ID")
      elements(Tag.SeriesDescription).get.toSingleString() should be("Mapped Series Description")
    }
  }

  "Harmonizing DICOM attributes when storing DICOM data" should "replace attributes according to existing anonymization keys" in {
    val elements = testElements
    val key = TestUtil.createAnonymizationKey(elements, anonPatientName = "apn", anonPatientID = "apid", anonSeriesInstanceUID = "aseuid")

    val source = toSource(elements).via(storage.parseFlow(None))
    val anonSource = dicomStreamOpsImpl.anonymizedDicomDataSource(source, _ => Future.successful(key), Seq.empty)

    anonSource.via(storage.parseFlow(None)).via(ElementFolds.elementsFlow).runWith(ElementFolds.elementsSink).map { elements =>
      elements(Tag.PatientName).get.toSingleString() should be("apn")
      elements(Tag.PatientID).get.toSingleString() should be("apid")
      elements(Tag.SeriesInstanceUID).get.toSingleString() should be("aseuid")
      elements(Tag.Allergies) shouldBe empty
      elements(Tag.PatientIdentityRemoved).get.toSingleString() shouldBe "YES"
    }
  }

  "The DICOM data source with anonymization" should "anonymize the patient ID and harmonize it according to an anonymization key" in {
    val elements = TestUtil.createElements()
    val anonKey = TestUtil.createAnonymizationKey(elements)
    val source = toSource(elements).via(storage.parseFlow(None))

    val anonSource = dicomStreamOpsImpl.anonymizedDicomDataSource(source, _ => Future.successful(anonKey), Seq.empty)

    anonSource.via(storage.parseFlow(None)).via(ElementFolds.elementsFlow).runWith(ElementFolds.elementsSink).map { elements =>
        elements(Tag.PatientID).get.toSingleString() shouldBe anonKey.anonPatientID
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
          .via(DicomFlows.blacklistFilter(Set(TagPath.fromTag(Tag.PixelData))))
          .via(modifyFlow(
            TagModification.contains(
              TagPath.fromTag(Tag.MediaStorageSOPInstanceUID),
              uid => uid.dropRight(3) ++ ByteString(f"$sopInstanceUID%03d"),
              insert = true),
            TagModification.contains(
              TagPath.fromTag(Tag.SOPInstanceUID),
              uid => uid.dropRight(3) ++ ByteString(f"$sopInstanceUID%03d"),
              insert = true)))
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
            .via(ElementFolds.elementsFlow)
            .runWith(ElementFolds.elementsSink)
        }
      }
    }.map(_.map(elements => elements(Tag.PatientID).get.toSingleString()))

    anonymousPatientIds.map(_.toSet).map { uniqueUids =>
      uniqueUids should have size 1
    }
  }

  "Querying for anonymization keys during reverse anonymization" should "yield patient, study and series information when key and meta information match" in {
    val attributes = testElements
    val key = TestUtil.createAnonymizationKey(attributes, anonPatientName = "apn", anonPatientID = "apid", anonStudyInstanceUID = "astuid", anonSeriesInstanceUID = "aseuid")

    val metaData = DicomInfoPart(CharacterSets.defaultOnly, None, Some("apid"), Some("apn"), None, None, None, Some("YES"), Some("astuid"), None, None, None, Some("aseuid"), None, None, None)
    val query = dicomStreamOpsImpl.reverseAnonymizationKeyPartForPatient((_, _) => Future.successful(Seq(key)))
    query(metaData).map { parts =>
      parts should have length 2
      parts.head shouldBe a[DicomInfoPart]
      parts(1) shouldBe a[PartialAnonymizationKeyPart]
      val keyPart = parts(1).asInstanceOf[PartialAnonymizationKeyPart]
      keyPart.keyMaybe shouldBe defined
      keyPart.hasPatientInfo shouldBe true
      keyPart.hasStudyInfo shouldBe true
      keyPart.hasSeriesInfo shouldBe true
    }
  }

  it should "yield patient, but not study or series information when patient informtion match but study does not" in {
    val attributes = testElements
    val key = TestUtil.createAnonymizationKey(attributes, anonPatientName = "apn", anonPatientID = "apid", anonStudyInstanceUID = "astuid2", anonSeriesInstanceUID = "aseuid")

    val metaData = DicomInfoPart(CharacterSets.defaultOnly, None, Some("apid"), Some("apn"), None, None, None, Some("YES"), Some("astuid"), None, None, None, Some("aseuid"), None, None, None)
    val query = dicomStreamOpsImpl.reverseAnonymizationKeyPartForPatient((_, _) => Future.successful(Seq(key)))
    query(metaData).map { parts =>
      parts should have length 2
      parts.head shouldBe a[DicomInfoPart]
      parts(1) shouldBe a[PartialAnonymizationKeyPart]
      val keyPart = parts(1).asInstanceOf[PartialAnonymizationKeyPart]
      keyPart.keyMaybe shouldBe defined
      keyPart.hasPatientInfo shouldBe true
      keyPart.hasStudyInfo shouldBe false
      keyPart.hasSeriesInfo shouldBe false
    }
  }

  it should "create empty key info when data is not anonymized" in {
    val attributes = testElements
    val key = TestUtil.createAnonymizationKey(attributes, anonPatientName = "apn", anonPatientID = "apid", anonStudyInstanceUID = "astuid2", anonSeriesInstanceUID = "aseuid")

    val metaData = DicomInfoPart(CharacterSets.defaultOnly, None, Some("apid"), Some("apn"), None, None, None, Some("NO"), Some("astuid"), None, None, None, Some("aseuid"), None, None, None)
    val query = dicomStreamOpsImpl.reverseAnonymizationKeyPartForPatient((_, _) => Future.successful(Seq(key)))
    query(metaData).map { parts =>
      parts should have length 2
      parts.head shouldBe a[DicomInfoPart]
      parts(1) shouldBe a[PartialAnonymizationKeyPart]
      val keyPart = parts(1).asInstanceOf[PartialAnonymizationKeyPart]
      keyPart.keyMaybe should not be defined
    }
  }

  "Streaming storage of DICOM data" should "store the correct meta data and binary data under the expected name" in {
    val source = Source(SourceType.USER, "Jane", -1)
    val elements = TestUtil.testImageDicomData()
    val bytesSource = StreamSource.single(TestUtil.toBytes(elements))
    dicomStreamOpsImpl.storeDicomData(bytesSource, source).map { metaDataAdded =>
      storage.storage.get(storage.imageName(metaDataAdded.image.id)) shouldBe defined
      metaDataAdded.patient.patientName.value shouldBe elements(Tag.PatientName).get.toSingleString()
      metaDataAdded.patient.patientID.value shouldBe elements(Tag.PatientID).get.toSingleString()
      metaDataAdded.study.studyInstanceUID.value shouldBe elements(Tag.StudyInstanceUID).get.toSingleString()
      metaDataAdded.series.seriesInstanceUID.value shouldBe elements(Tag.SeriesInstanceUID).get.toSingleString()
      metaDataAdded.image.sopInstanceUID.value shouldBe elements(Tag.SOPInstanceUID).get.toSingleString()
    }
  }

  it should "reverse anonymization if data is anonymous and anonymization key exists" in {
    val source = Source(SourceType.USER, "Jane", -1)
    val elements = TestUtil.testImageDicomData()
      .update(Tag.PatientIdentityRemoved, Element.explicitLE(Tag.PatientIdentityRemoved, VR.CS, ByteString("YES")))
    val realName = "Real Name"
    val realID = "Real ID"
    val anonKey = TestUtil.createAnonymizationKey(
      elements,
      anonPatientName = elements(Tag.PatientName).get.toSingleString(),
      anonPatientID = elements(Tag.PatientID).get.toSingleString()
    ).copy(patientName = realName, patientID = realID)
    dicomStreamOpsImpl.callAnonymizationService[AnonymizationKeyAdded](AddAnonymizationKey(anonKey)).flatMap { _ =>
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
      metaDataAdded.patient.patientName.value shouldBe testData(Tag.PatientName).get.toSingleString()
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
      (_, metaDataAdded2) <- dicomStreamOpsImpl.modifyData(metaDataAdded1.image.id, Seq(TagModification.endsWith(TagPath.fromTag(Tag.PatientName), _ => ByteString(newName), insert = true)))
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
