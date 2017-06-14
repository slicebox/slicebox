package se.nimsa.sbx.dicom.streams

import java.io.ByteArrayOutputStream

import akka.NotUsed
import akka.actor.ActorSystem
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.Source
import akka.testkit.TestKit
import akka.util.{ByteString, Timeout}
import org.dcm4che3.data.{Attributes, Tag, UID}
import org.dcm4che3.io.{DicomOutputStream, DicomStreamException}
import org.scalatest.{AsyncFlatSpecLike, Matchers}
import se.nimsa.dcm4che.streams.{DicomAttributesSink, DicomFlows, DicomPartFlow}
import se.nimsa.sbx.anonymization.AnonymizationProtocol.TagValue
import se.nimsa.sbx.dicom.{Contexts, DicomData}
import se.nimsa.sbx.storage.RuntimeStorage
import se.nimsa.sbx.util.TestUtil

import scala.concurrent.Future
import scala.concurrent.duration.DurationInt

class DicomStreamOpsTest extends TestKit(ActorSystem("AnonymizationFlowSpec")) with AsyncFlatSpecLike with Matchers {

  import DicomTestData._

  implicit val materializer = ActorMaterializer()
  implicit val ec = system.dispatcher
  implicit val timeout = Timeout(30.seconds)

  val storage = new RuntimeStorage

  def toSource(dicomData: DicomData): Source[ByteString, NotUsed] = {
    val baos = new ByteArrayOutputStream()
    val dos = new DicomOutputStream(baos, UID.ExplicitVRLittleEndian)
    dos.writeDataset(dicomData.metaInformation, dicomData.attributes)
    dos.close()
    Source.single(ByteString(baos.toByteArray))
  }

  "Validating a DICOM file" should "throw an exception for a non-supported context" in {
    val bytes = preamble ++ fmiGroupLength(unsupportedMediaStorageSOPClassUID ++ tsuidExplicitLE) ++ unsupportedMediaStorageSOPClassUID ++ tsuidExplicitLE ++ patientNameJohnDoe
    val source = Source.single(bytes)
    recoverToSucceededIf[DicomStreamException] {
      source.runWith(DicomStreamOps.dicomDataSink(storage.fileSink("name"), (_, _) => Future.successful(Seq.empty), Contexts.imageDataContexts))
    }
  }

  it should "pass a supported context" in {
    val bytes = preamble ++ fmiGroupLength(supportedMediaStorageSOPClassUID ++ tsuidExplicitLE) ++ supportedMediaStorageSOPClassUID ++ tsuidExplicitLE ++ patientNameJohnDoe
    val source = Source.single(bytes)
    source.runWith(DicomStreamOps.dicomDataSink(storage.fileSink("name"), (_, _) => Future.successful(Seq.empty), Contexts.imageDataContexts))
      .map(_ => succeed)
  }

  it should "throw an exception for a context with an unknown SOP Class UID" in {
    val bytes = preamble ++ fmiGroupLength(unknownMediaStorageSOPClassUID ++ tsuidExplicitLE) ++ unknownMediaStorageSOPClassUID ++ tsuidExplicitLE ++ patientNameJohnDoe
    val source = Source.single(bytes)
    recoverToSucceededIf[DicomStreamException] {
      source.runWith(DicomStreamOps.dicomDataSink(storage.fileSink("name"), (_, _) => Future.successful(Seq.empty), Contexts.imageDataContexts))
    }
  }

  it should "accept DICOM data with missing file meta information" in {
    val bytes = supportedSOPClassUID
    val source = Source.single(bytes)
    source.runWith(DicomStreamOps.dicomDataSink(storage.fileSink("name"), (_, _) => Future.successful(Seq.empty), Contexts.imageDataContexts))
      .map(_ => succeed)
  }

  "The DICOM data source with anonymization" should "anonymize the patient ID and harmonize it according to a anonymization key" in {
    val dicomData = TestUtil.createDicomData()
    val anonKey = TestUtil.createAnonymizationKey(dicomData.attributes)
    val source = toSource(dicomData)

    val anonSource = DicomStreamOps.anonymizedDicomDataSource(source, (_, _) => Future.successful(Seq(anonKey)), a => Future.successful(a), Seq.empty)

    anonSource.via(DicomPartFlow.partFlow).via(DicomFlows.attributeFlow).runWith(DicomAttributesSink.attributesSink).map {
      case (_, dsMaybe) =>
        val ds = dsMaybe.get
        ds.getString(Tag.PatientID) shouldBe anonKey.anonPatientID
    }
  }

  "Applying tag modifications when storing DICOM data" should "replace DICOM attributes" in {
    val t1 = TagValue(Tag.PatientName, "Mapped Patient Name")
    val t2 = TagValue(Tag.PatientID, "Mapped Patient ID")
    val t3 = TagValue(Tag.SeriesDescription, "Mapped Series Description")
    val bytes = preamble ++ fmiGroupLength(supportedMediaStorageSOPClassUID ++ tsuidExplicitLE) ++ supportedMediaStorageSOPClassUID ++ tsuidExplicitLE ++ patientNameJohnDoe
    val source = Source.single(bytes)
    val anonSource = DicomStreamOps.anonymizedDicomDataSource(
      source,
      (_, _) => Future.successful(Seq.empty),
      _ => Future.successful(TestUtil.createAnonymizationKey(new Attributes())),
      Seq(t1, t2, t3))

    anonSource.via(DicomPartFlow.partFlow).via(DicomFlows.attributeFlow).runWith(DicomAttributesSink.attributesSink).map {
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

    val source = toSource(DicomData(attributes, null))
    val anonSource = DicomStreamOps.anonymizedDicomDataSource(source, (_, _) => Future.successful(Seq(key)), _ => Future.successful(key), Seq.empty)

    anonSource.via(DicomPartFlow.partFlow).via(DicomFlows.attributeFlow).runWith(DicomAttributesSink.attributesSink).map {
      case (_, dsMaybe) =>
        val ds = dsMaybe.get
        ds.getString(Tag.PatientName) should be("apn")
        ds.getString(Tag.PatientID) should be("apid")
        ds.getString(Tag.SeriesInstanceUID) should be("aseuid")
        ds.getString(Tag.Allergies) shouldBe null
        ds.getString(Tag.PatientIdentityRemoved) shouldBe "YES"
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
      keysPart.allKeys should have length 1
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
      keysPart.allKeys should have length 1
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
      keysPart.allKeys should have length 1
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
      keysPart.allKeys should have length 1
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
      keysPart.allKeys shouldBe empty
    }
  }
}
