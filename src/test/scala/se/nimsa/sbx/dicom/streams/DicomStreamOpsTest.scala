package se.nimsa.sbx.dicom.streams

import java.io.ByteArrayOutputStream

import akka.NotUsed
import akka.actor.{ActorSystem, Cancellable}
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.{Source => StreamSource}
import akka.testkit.TestKit
import akka.util.{ByteString, Timeout}
import org.dcm4che3.data.{Attributes, Tag, UID, VR}
import org.dcm4che3.io.{DicomOutputStream, DicomStreamException}
import org.scalatest.{AsyncFlatSpecLike, BeforeAndAfterAll, BeforeAndAfterEach, Matchers}
import se.nimsa.dcm4che.streams.DicomFlows.attributeFlow
import se.nimsa.dcm4che.streams.DicomModifyFlow.TagModification
import se.nimsa.dcm4che.streams.DicomParseFlow.parseFlow
import se.nimsa.dcm4che.streams._
import se.nimsa.sbx.anonymization.AnonymizationDAO
import se.nimsa.sbx.anonymization.AnonymizationProtocol._
import se.nimsa.sbx.app.GeneralProtocol.{Source, SourceType}
import se.nimsa.sbx.dicom.DicomUtil._
import se.nimsa.sbx.dicom.{Contexts, DicomData}
import se.nimsa.sbx.lang.NotFoundException
import se.nimsa.sbx.metadata.MetaDataProtocol._
import se.nimsa.sbx.metadata.{MetaDataDAO, PropertiesDAO}
import se.nimsa.sbx.storage.RuntimeStorage
import se.nimsa.sbx.util.FutureUtil.await
import se.nimsa.sbx.util.{FutureUtil, TestUtil}

import scala.concurrent.duration.{DurationInt, FiniteDuration}
import scala.concurrent.{ExecutionContextExecutor, Future}
import scala.reflect.ClassTag

class DicomStreamOpsTest extends TestKit(ActorSystem("AnonymizationFlowSpec")) with AsyncFlatSpecLike with Matchers with BeforeAndAfterAll with BeforeAndAfterEach {

  import DicomTestData._

  implicit val materializer: ActorMaterializer = ActorMaterializer()
  implicit val ec: ExecutionContextExecutor = system.dispatcher
  implicit val timeout: Timeout = Timeout(30.seconds)

  val dbConfig = TestUtil.createTestDb("dicomstreamopstest")
  val metaDataDao = new MetaDataDAO(dbConfig)(ec)
  val propertiesDao = new PropertiesDAO(dbConfig)(ec)
  val anonymizationDao = new AnonymizationDAO(dbConfig)(ec)

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

  class DicomStreamOpsImpl extends DicomStreamOps {

    override def callAnonymizationService[R: ClassTag](message: Any): Future[R] = message match {
      case AddAnonymizationKey(anonymizationKey) =>
        val addFuture = anonymizationDao.insertAnonymizationKey(anonymizationKey).map(AnonymizationKeyAdded)
        addFuture.map(_.asInstanceOf[R])

      case GetAnonymizationKeysForPatient(patientName, patientID) =>
        val getFuture = anonymizationDao.anonymizationKeysForPatient(patientName, patientID).map(AnonymizationKeys)
        getFuture.map(_.asInstanceOf[R])

      case GetReverseAnonymizationKeysForPatient(anonPatientName, anonPatientID) =>
        val getFuture = anonymizationDao.anonymizationKeysForAnonPatient(anonPatientName, anonPatientID).map(AnonymizationKeys)
        getFuture.map(_.asInstanceOf[R])
    }

    override def callMetaDataService[R: ClassTag](message: Any): Future[R] = message match {
      case AddMetaData(attributes, source) =>
        val addFuture = propertiesDao.addMetaData(
          attributesToPatient(attributes),
          attributesToStudy(attributes),
          attributesToSeries(attributes),
          attributesToImage(attributes),
          source)
        addFuture.map(_.asInstanceOf[R])

      case DeleteMetaData(imageIds) =>
        val deleteFuture = propertiesDao.deleteFully(imageIds).map(MetaDataDeleted.tupled)
        deleteFuture.map(_.asInstanceOf[R])

      case GetImage(imageId) =>
        metaDataDao.imageById(imageId).map(_.asInstanceOf[R])

      case GetSourceForSeries(seriesId) =>
        propertiesDao.seriesSourceById(seriesId).map(_.asInstanceOf[R])

      case GetSeriesTagsForSeries(seriesId) =>
        propertiesDao.seriesTagsForSeries(seriesId).map(SeriesTags).map(_.asInstanceOf[R])

      case AddSeriesTagToSeries(seriesTag, seriesId) =>
        propertiesDao.addSeriesTagToSeries(seriesTag, seriesId)
          .map(_.getOrElse(throw new NotFoundException("Series not found")))
          .map(SeriesTagAddedToSeries)
          .map(_.asInstanceOf[R])
    }

    override def scheduleTask(delay: FiniteDuration)(task: => Unit): Cancellable = system.scheduler.scheduleOnce(delay)(task)
  }

  val dicomStreamOpsImpl = new DicomStreamOpsImpl()

  val storage = new RuntimeStorage

  def toSource(dicomData: DicomData): StreamSource[ByteString, NotUsed] = {
    val baos = new ByteArrayOutputStream()
    val dos = new DicomOutputStream(baos, UID.ExplicitVRLittleEndian)
    dos.writeDataset(dicomData.metaInformation, dicomData.attributes)
    dos.close()
    StreamSource.single(ByteString(baos.toByteArray))
  }

  "Validating a DICOM file" should "throw an exception for a non-supported context" in {
    val bytes = preamble ++ fmiGroupLength(unsupportedMediaStorageSOPClassUID ++ tsuidExplicitLE) ++ unsupportedMediaStorageSOPClassUID ++ tsuidExplicitLE ++ patientNameJohnDoe
    val source = StreamSource.single(bytes)
    recoverToSucceededIf[DicomStreamException] {
      source.runWith(DicomStreamOps.dicomDataSink(storage.fileSink("name"), (_, _) => Future.successful(Seq.empty), Contexts.imageDataContexts))
    }
  }

  it should "pass a supported context" in {
    val bytes = preamble ++ fmiGroupLength(supportedMediaStorageSOPClassUID ++ tsuidExplicitLE) ++ supportedMediaStorageSOPClassUID ++ tsuidExplicitLE ++ patientNameJohnDoe
    val source = StreamSource.single(bytes)
    source.runWith(DicomStreamOps.dicomDataSink(storage.fileSink("name"), (_, _) => Future.successful(Seq.empty), Contexts.imageDataContexts))
      .map(_ => succeed)
  }

  it should "throw an exception for a context with an unknown SOP Class UID" in {
    val bytes = preamble ++ fmiGroupLength(unknownMediaStorageSOPClassUID ++ tsuidExplicitLE) ++ unknownMediaStorageSOPClassUID ++ tsuidExplicitLE ++ patientNameJohnDoe
    val source = StreamSource.single(bytes)
    recoverToSucceededIf[DicomStreamException] {
      source.runWith(DicomStreamOps.dicomDataSink(storage.fileSink("name"), (_, _) => Future.successful(Seq.empty), Contexts.imageDataContexts))
    }
  }

  it should "accept DICOM data with missing file meta information" in {
    val bytes = supportedSOPClassUID
    val source = StreamSource.single(bytes)
    source.runWith(DicomStreamOps.dicomDataSink(storage.fileSink("name"), (_, _) => Future.successful(Seq.empty), Contexts.imageDataContexts))
      .map(_ => succeed)
  }

  "Applying tag modifications when storing DICOM data" should "replace DICOM attributes" in {
    val t1 = TagValue(Tag.PatientName, "Mapped Patient Name")
    val t2 = TagValue(Tag.PatientID, "Mapped Patient ID")
    val t3 = TagValue(Tag.SeriesDescription, "Mapped Series Description")
    val bytes = preamble ++ fmiGroupLength(supportedMediaStorageSOPClassUID ++ tsuidExplicitLE) ++ supportedMediaStorageSOPClassUID ++ tsuidExplicitLE ++ patientNameJohnDoe
    val source = StreamSource.single(bytes).via(parseFlow)
    val anonSource = DicomStreamOps.anonymizedDicomDataSource(
      source,
      (_, _) => Future.successful(Seq.empty),
      _ => Future.successful(TestUtil.createAnonymizationKey(new Attributes())),
      Seq(t1, t2, t3))

    anonSource.via(parseFlow).via(DicomFlows.attributeFlow).runWith(DicomAttributesSink.attributesSink).map {
      case (_, dsMaybe) =>
        val ds = dsMaybe.get
        ds.getString(Tag.PatientName) should be("Mapped Patient Name")
        ds.getString(Tag.PatientID) should be("Mapped Patient ID")
        ds.getString(Tag.SeriesDescription) should be("Mapped Series Description")
    }
  }

  "Harmonizing DICOM attributes when storing DICOM data" should "replace attributes according to existing anonymization keys" in {
    val attributes = createAttributes
    val key = TestUtil.createAnonymizationKey(attributes, anonPatientName = "apn", anonPatientID = "apid", anonSeriesInstanceUID = "aseuid")

    val source = toSource(DicomData(attributes, null)).via(parseFlow)
    val anonSource = DicomStreamOps.anonymizedDicomDataSource(source, (_, _) => Future.successful(Seq(key)), _ => Future.successful(key), Seq.empty)

    anonSource.via(parseFlow).via(DicomFlows.attributeFlow).runWith(DicomAttributesSink.attributesSink).map {
      case (_, dsMaybe) =>
        val ds = dsMaybe.get
        ds.getString(Tag.PatientName) should be("apn")
        ds.getString(Tag.PatientID) should be("apid")
        ds.getString(Tag.SeriesInstanceUID) should be("aseuid")
        ds.getString(Tag.Allergies) shouldBe null
        ds.getString(Tag.PatientIdentityRemoved) shouldBe "YES"
    }
  }

  "The DICOM data source with anonymization" should "anonymize the patient ID and harmonize it according to an anonymization key" in {
    val dicomData = TestUtil.createDicomData()
    val anonKey = TestUtil.createAnonymizationKey(dicomData.attributes)
    val source = toSource(dicomData).via(parseFlow)

    val anonSource = DicomStreamOps.anonymizedDicomDataSource(source, (_, _) => Future.successful(Seq(anonKey)), a => Future.successful(a), Seq.empty)

    anonSource.via(parseFlow).via(DicomFlows.attributeFlow).runWith(DicomAttributesSink.attributesSink).map {
      case (_, dsMaybe) =>
        val ds = dsMaybe.get
        ds.getString(Tag.PatientID) shouldBe anonKey.anonPatientID
    }
  }

  "An anonymized data source" should "create anonymization record for first image and harmonize subsequent images" in {
    val source = Source(SourceType.USER, "Jane", -1)
    val testData = TestUtil.testImageByteArray
    val bytesSource = StreamSource.single(ByteString.fromArray(testData))

    // store 100 files in the same series
    val sopInstanceUIDs = 1 to 100
    val storedImageIds = FutureUtil.traverseSequentially(sopInstanceUIDs) { sopInstanceUID =>
      val modifiedBytesSource = bytesSource
        .via(parseFlow)
        .via(DicomFlows.blacklistFilter(Set(TagPath.fromTag(Tag.PixelData))))
        .via(DicomModifyFlow.modifyFlow(
          TagModification.contains(
            TagPath.fromTag(Tag.MediaStorageSOPInstanceUID),
            uid => uid.dropRight(3) ++ ByteString(f"$sopInstanceUID%03d"),
            insert = true),
          TagModification.contains(
            TagPath.fromTag(Tag.SOPInstanceUID),
            uid => uid.dropRight(3) ++ ByteString(f"$sopInstanceUID%03d"),
            insert = true)))
        .map(_.bytes)
      dicomStreamOpsImpl.storeDicomData(modifiedBytesSource, source, storage, Contexts.imageDataContexts, reverseAnonymization = false)
        .map(_.image.id)
    }

    // anonymize them in parallel
    val anonymousPatientIds = storedImageIds.flatMap { imageIds =>
      FutureUtil.traverseSequentially(imageIds) { imageId =>
        dicomStreamOpsImpl.anonymizedDicomData(imageId, Seq.empty, storage)
          .via(parseFlow)
          .via(attributeFlow)
          .runWith(DicomAttributesSink.attributesSink)
          .map(_._2)
      }
    }.map(_.flatten).map(_.map(_.getString(Tag.PatientID)))

    anonymousPatientIds.map(_.toSet).map { uniqueUids =>
      uniqueUids should have size 1
    }
  }

  "Querying for anonymization keys during anonymization" should "yield patient, study and series information when key and meta information match" in {
    val attributes = createAttributes
    val key = TestUtil.createAnonymizationKey(attributes, anonPatientName = "apn", anonPatientID = "apid", anonSeriesInstanceUID = "aseuid")

    val metaData = DicomMetaPart(None, None, Some("pid"), Some("pn"), Some("NO"), Some("stuid"), Some("seuid"))
    val query = DicomStreamOps.queryAnonymousAnonymizationKeys((_, _) => Future.successful(Seq(key)))
    query(metaData).map { parts =>
      parts should have length 2
      parts.head shouldBe a[DicomMetaPart]
      parts(1) shouldBe a[AnonymizationKeysPart]
      val keysPart = parts(1).asInstanceOf[AnonymizationKeysPart]
      keysPart.patientKey shouldBe defined
      keysPart.studyKey shouldBe defined
      keysPart.seriesKey shouldBe defined
      keysPart.patientKeys should have length 1
    }
  }

  it should "yield patient, but not study or series information when patient informtion match but study does not" in {
    val attributes = createAttributes
    val key = TestUtil.createAnonymizationKey(attributes, anonPatientName = "apn", anonPatientID = "apid", anonSeriesInstanceUID = "aseuid")
      .copy(studyInstanceUID = "stuid2")

    val metaData = DicomMetaPart(None, None, Some("pid"), Some("pn"), Some("NO"), Some("stuid"), Some("seuid"))
    val query = DicomStreamOps.queryAnonymousAnonymizationKeys((_, _) => Future.successful(Seq(key)))
    query(metaData).map { parts =>
      parts should have length 2
      parts.head shouldBe a[DicomMetaPart]
      parts(1) shouldBe a[AnonymizationKeysPart]
      val keysPart = parts(1).asInstanceOf[AnonymizationKeysPart]
      keysPart.patientKey shouldBe defined
      keysPart.studyKey should not be defined
      keysPart.seriesKey should not be defined
      keysPart.patientKeys should have length 1
    }
  }

  "Querying for anonymization keys during reverse anonymization" should "yield patient, study and series information when key and meta information match" in {
    val attributes = createAttributes
    val key = TestUtil.createAnonymizationKey(attributes, anonPatientName = "apn", anonPatientID = "apid", anonStudyInstanceUID = "astuid", anonSeriesInstanceUID = "aseuid")

    val metaData = DicomMetaPart(None, None, Some("apid"), Some("apn"), Some("YES"), Some("astuid"), Some("aseuid"))
    val query = DicomStreamOps.queryProtectedAnonymizationKeys((_, _) => Future.successful(Seq(key)))
    query(metaData).map { parts =>
      parts should have length 2
      parts.head shouldBe a[DicomMetaPart]
      parts(1) shouldBe a[AnonymizationKeysPart]
      val keysPart = parts(1).asInstanceOf[AnonymizationKeysPart]
      keysPart.patientKey shouldBe defined
      keysPart.studyKey shouldBe defined
      keysPart.seriesKey shouldBe defined
      keysPart.patientKeys should have length 1
    }
  }

  it should "yield patient, but not study or series information when patient informtion match but study does not" in {
    val attributes = createAttributes
    val key = TestUtil.createAnonymizationKey(attributes, anonPatientName = "apn", anonPatientID = "apid", anonStudyInstanceUID = "astuid2", anonSeriesInstanceUID = "aseuid")

    val metaData = DicomMetaPart(None, None, Some("apid"), Some("apn"), Some("YES"), Some("astuid"), Some("aseuid"))
    val query = DicomStreamOps.queryProtectedAnonymizationKeys((_, _) => Future.successful(Seq(key)))
    query(metaData).map { parts =>
      parts should have length 2
      parts.head shouldBe a[DicomMetaPart]
      parts(1) shouldBe a[AnonymizationKeysPart]
      val keysPart = parts(1).asInstanceOf[AnonymizationKeysPart]
      keysPart.patientKey shouldBe defined
      keysPart.studyKey should not be defined
      keysPart.seriesKey should not be defined
      keysPart.patientKeys should have length 1
    }
  }

  it should "create empty key info when data is not anonymized" in {
    val attributes = createAttributes
    val key = TestUtil.createAnonymizationKey(attributes, anonPatientName = "apn", anonPatientID = "apid", anonStudyInstanceUID = "astuid2", anonSeriesInstanceUID = "aseuid")

    val metaData = DicomMetaPart(None, None, Some("apid"), Some("apn"), Some("NO"), Some("astuid"), Some("aseuid"))
    val query = DicomStreamOps.queryProtectedAnonymizationKeys((_, _) => Future.successful(Seq(key)))
    query(metaData).map { parts =>
      parts should have length 2
      parts.head shouldBe a[DicomMetaPart]
      parts(1) shouldBe a[AnonymizationKeysPart]
      val keysPart = parts(1).asInstanceOf[AnonymizationKeysPart]
      keysPart.patientKey should not be defined
      keysPart.studyKey should not be defined
      keysPart.seriesKey should not be defined
      keysPart.patientKeys shouldBe empty
    }
  }

  "Streaming storage of DICOM data" should "store the correct meta data and binary data under the expected name" in {
    val source = Source(SourceType.USER, "Jane", -1)
    val testData = TestUtil.testImageDicomData()
    val bytesSource = StreamSource.single(ByteString.fromArray(TestUtil.toByteArray(testData)))
    dicomStreamOpsImpl.storeDicomData(bytesSource, source, storage, Contexts.imageDataContexts, reverseAnonymization = false).map { metaDataAdded =>
      storage.storage.get(storage.imageName(metaDataAdded.image.id)) shouldBe defined
      metaDataAdded.patient.patientName.value shouldBe testData.attributes.getString(Tag.PatientName)
      metaDataAdded.patient.patientID.value shouldBe testData.attributes.getString(Tag.PatientID)
      metaDataAdded.study.studyInstanceUID.value shouldBe testData.attributes.getString(Tag.StudyInstanceUID)
      metaDataAdded.series.seriesInstanceUID.value shouldBe testData.attributes.getString(Tag.SeriesInstanceUID)
      metaDataAdded.image.sopInstanceUID.value shouldBe testData.attributes.getString(Tag.SOPInstanceUID)
    }
  }

  it should "reverse anonymization if data is anonymous and anonymization key exists" in {
    val source = Source(SourceType.USER, "Jane", -1)
    val testData = TestUtil.testImageDicomData()
    testData.attributes.setString(Tag.PatientIdentityRemoved, VR.CS, "YES")
    val realName = "Real Name"
    val realID = "Real ID"
    val anonKey = TestUtil.createAnonymizationKey(
      testData.attributes,
      anonPatientName = testData.attributes.getString(Tag.PatientName),
      anonPatientID = testData.attributes.getString(Tag.PatientID)
    ).copy(patientName = realName, patientID = realID)
    dicomStreamOpsImpl.callAnonymizationService[AnonymizationKeyAdded](AddAnonymizationKey(anonKey)).flatMap { _ =>
      val bytesSource = StreamSource.single(ByteString.fromArray(TestUtil.toByteArray(testData)))
      dicomStreamOpsImpl.storeDicomData(bytesSource, source, storage, Contexts.imageDataContexts).map { metaDataAdded =>
        metaDataAdded.patient.patientName.value shouldBe realName
        metaDataAdded.patient.patientID.value shouldBe realID
      }
    }
  }

  it should "not reverse anonymization if data is not anonymous" in {
    val source = Source(SourceType.USER, "Jane", -1)
    val testData = TestUtil.testImageDicomData()
    val bytesSource = StreamSource.single(ByteString.fromArray(TestUtil.toByteArray(testData)))
    dicomStreamOpsImpl.storeDicomData(bytesSource, source, storage, Contexts.imageDataContexts).map { metaDataAdded =>
      metaDataAdded.patient.patientName.value shouldBe testData.attributes.getString(Tag.PatientName)
    }
  }

  "Modifying DICOM data" should "remove the old data and replace it with new" in {
    val source = Source(SourceType.USER, "Jane", -1)
    val testData = TestUtil.testImageDicomData()
    val bytesSource = StreamSource.single(ByteString.fromArray(TestUtil.toByteArray(testData)))
    for {
      metaDataAdded1 <- dicomStreamOpsImpl.storeDicomData(bytesSource, source, storage, Contexts.imageDataContexts)
      (_, metaDataAdded2) <- dicomStreamOpsImpl.modifyData(metaDataAdded1.image.id, Seq.empty, storage)
    } yield {
      storage.storage.get(storage.imageName(metaDataAdded1.image.id)) should not be defined
      storage.storage.get(storage.imageName(metaDataAdded2.image.id)) shouldBe defined
    }
  }

  it should "modify data according to input tag modifications" in {
    val newName = "New Name"
    val source = Source(SourceType.USER, "Jane", -1)
    val testData = TestUtil.testImageDicomData()
    val bytesSource = StreamSource.single(ByteString.fromArray(TestUtil.toByteArray(testData)))
    for {
      metaDataAdded1 <- dicomStreamOpsImpl.storeDicomData(bytesSource, source, storage, Contexts.imageDataContexts)
      (_, metaDataAdded2) <- dicomStreamOpsImpl.modifyData(metaDataAdded1.image.id, Seq(TagModification.endsWith(TagPath.fromTag(Tag.PatientName), _ => ByteString(newName), insert = true)), storage)
    } yield {
      metaDataAdded2.patient.patientName.value shouldBe newName
    }
  }

  it should "transfer series source and tags to new, modified data" in {
    val source = Source(SourceType.USER, "Jane", -1)
    val testData = TestUtil.testImageDicomData()
    val bytesSource = StreamSource.single(ByteString.fromArray(TestUtil.toByteArray(testData)))
    for {
      metaDataAdded1 <- dicomStreamOpsImpl.storeDicomData(bytesSource, source, storage, Contexts.imageDataContexts)
      _ <- dicomStreamOpsImpl.callMetaDataService[SeriesTagAddedToSeries](AddSeriesTagToSeries(SeriesTag(42, "tag1"), metaDataAdded1.series.id))
      (_, metaDataAdded2) <- dicomStreamOpsImpl.modifyData(metaDataAdded1.image.id, Seq.empty, storage)
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
