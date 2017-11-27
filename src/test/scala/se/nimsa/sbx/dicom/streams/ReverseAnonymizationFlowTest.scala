package se.nimsa.sbx.dicom.streams

import akka.NotUsed
import akka.actor.ActorSystem
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.{Sink, Source}
import akka.stream.testkit.scaladsl.TestSink
import akka.testkit.TestKit
import akka.util.ByteString
import org.dcm4che3.data.{Attributes, Tag, UID, VR}
import org.scalatest.{BeforeAndAfterAll, FlatSpecLike, Matchers}
import se.nimsa.dcm4che.streams.DicomModifyFlow.TagModification
import se.nimsa.dcm4che.streams.DicomParseFlow.parseFlow
import se.nimsa.dcm4che.streams.DicomParts.{DicomAttributes, DicomPart}
import se.nimsa.dcm4che.streams._
import se.nimsa.sbx.dicom.DicomData
import se.nimsa.sbx.dicom.streams.DicomStreamOps.PartialAnonymizationKeyPart
import se.nimsa.sbx.util.TestUtil
import se.nimsa.sbx.util.TestUtil._

import scala.concurrent.duration.DurationInt
import scala.concurrent.{Await, ExecutionContextExecutor}

class ReverseAnonymizationFlowTest extends TestKit(ActorSystem("ReverseAnonymizationFlowSpec")) with FlatSpecLike with Matchers with BeforeAndAfterAll {

  import DicomTestData._

  implicit val materializer: ActorMaterializer = ActorMaterializer()
  implicit val ec: ExecutionContextExecutor = system.dispatcher

  override def afterAll: Unit = TestKit.shutdownActorSystem(system)

  def attributesSource(dicomData: DicomData): Source[DicomPart, NotUsed] = {
    val bytes = ByteString(TestUtil.toByteArray(dicomData))
    Source.single(bytes)
      .via(parseFlow)
      .via(DicomFlows.tagFilter(_ => false)(tagPath => !DicomParsing.isFileMetaInformation(tagPath.tag)))
  }

  def anonKeyPart(dicomData: DicomData): PartialAnonymizationKeyPart = {
    val key = createAnonymizationKey(dicomData.attributes)
    PartialAnonymizationKeyPart(Some(key), hasPatientInfo = true, hasStudyInfo = true, hasSeriesInfo = true)
  }

  def anonSource(dicomData: DicomData): Source[DicomPart, NotUsed] = {
    val key = anonKeyPart(dicomData).keyMaybe.get
    attributesSource(dicomData)
      .via(AnonymizationFlow.anonFlow)
      .via(DicomModifyFlow.modifyFlow(
        TagModification.contains(TagPath.fromTag(Tag.PatientName), _ => toAsciiBytes(key.anonPatientName, VR.PN), insert = false),
        TagModification.contains(TagPath.fromTag(Tag.PatientID), _ => toAsciiBytes(key.anonPatientID, VR.LO), insert = false),
        TagModification.contains(TagPath.fromTag(Tag.StudyInstanceUID), _ => toAsciiBytes(key.anonStudyInstanceUID, VR.UI), insert = false),
        TagModification.contains(TagPath.fromTag(Tag.SeriesInstanceUID), _ => toAsciiBytes(key.anonSeriesInstanceUID, VR.UI), insert = false),
        TagModification.contains(TagPath.fromTag(Tag.FrameOfReferenceUID), _ => toAsciiBytes(key.anonFrameOfReferenceUID, VR.UI), insert = false)
      ))
  }

  "The reverse anonymization flow" should "reverse anonymization for attributes stored in anonymization key" in {
    val dicomData = createDicomData()

    val source = Source.single(anonKeyPart(dicomData))
      .concat(anonSource(dicomData))
      .via(ReverseAnonymizationFlow.reverseAnonFlow)
      .via(DicomFlows.attributeFlow)

    val (_, dsMaybe) = Await.result(source.runWith(DicomAttributesSink.attributesSink), 10.seconds)
    val ds = dsMaybe.get

    ds.getString(Tag.PatientName) shouldBe dicomData.attributes.getString(Tag.PatientName)
    ds.getString(Tag.PatientID) shouldBe dicomData.attributes.getString(Tag.PatientID)
    ds.getString(Tag.StudyInstanceUID) shouldBe dicomData.attributes.getString(Tag.StudyInstanceUID)
    ds.getString(Tag.SeriesInstanceUID) shouldBe dicomData.attributes.getString(Tag.SeriesInstanceUID)
    ds.getString(Tag.FrameOfReferenceUID) shouldBe dicomData.attributes.getString(Tag.FrameOfReferenceUID)
    ds.getString(Tag.PatientBirthDate) shouldBe dicomData.attributes.getString(Tag.PatientBirthDate)
    ds.getString(Tag.StudyDescription) shouldBe dicomData.attributes.getString(Tag.StudyDescription)
    ds.getString(Tag.StudyID) shouldBe dicomData.attributes.getString(Tag.StudyID)
    ds.getString(Tag.AccessionNumber) shouldBe dicomData.attributes.getString(Tag.AccessionNumber)
    ds.getString(Tag.SeriesDescription) shouldBe dicomData.attributes.getString(Tag.SeriesDescription)
    ds.getString(Tag.ProtocolName) shouldBe dicomData.attributes.getString(Tag.ProtocolName)
  }

  it should "insert anonymization key attributes into dataset even if they originally were not present" in {
    val attributes = new Attributes()
    attributes.setString(Tag.Modality, VR.CS, "NM")
    val fmi = new Attributes()
    fmi.setString(Tag.TransferSyntaxUID, VR.UI, UID.ExplicitVRLittleEndian)
    val dicomData = DicomData(attributes, fmi)
    val source = attributesSource(dicomData)
      .via(ReverseAnonymizationFlow.reverseAnonFlow)
      .via(DicomFlows.guaranteedValueFlow)

    source.runWith(TestSink.probe[DicomPart])
      .expectHeaderAndValueChunkPairs(
        List(
          Tag.Modality,
          Tag.PatientName,
          Tag.PatientID,
          Tag.PatientBirthDate,
          Tag.PatientIdentityRemoved,
          Tag.DeidentificationMethod,
          Tag.StudyInstanceUID,
          Tag.StudyDescription,
          Tag.StudyID,
          Tag.AccessionNumber,
          Tag.SeriesInstanceUID,
          Tag.SeriesDescription,
          Tag.ProtocolName,
          Tag.FrameOfReferenceUID
        ).sorted: _*
      )
      .expectDicomComplete()
  }

  "The conditional reverse anonymization flow" should "not perform reverse anonymization when stream is empty" in {
    val source = Source.empty
      .via(ReverseAnonymizationFlow.maybeReverseAnonFlow)

    source.runWith(TestSink.probe[DicomPart])
      .expectDicomComplete()
  }

  it should "not perform reverse anonymization when anonymization key is missing in stream" in {
    val dicomData = createDicomData()

    val source = anonSource(dicomData)
      .via(ReverseAnonymizationFlow.reverseAnonFlow)
      .via(DicomFlows.collectAttributesFlow(Set(Tag.PatientName)))
      .filter(_.isInstanceOf[DicomAttributes])
      .mapAsync(5) {
        case as: DicomAttributes => Source(as.attributes.toList).runWith(DicomAttributesSink.attributesSink)
      }

    val (_, dsMaybe) = Await.result(source.runWith(Sink.head), 10.seconds)
    val ds = dsMaybe.get

    ds.getString(Tag.PatientName) should not be dicomData.attributes.getString(Tag.PatientName)
  }

  it should "perform reverse anonymization when anonymization key is present in stream" in {
    val dicomData = createDicomData()

    val source = Source.single(anonKeyPart(dicomData))
      .concat(anonSource(dicomData))
      .via(ReverseAnonymizationFlow.reverseAnonFlow)
      .via(DicomFlows.collectAttributesFlow(Set(Tag.PatientName)))
      .filter(_.isInstanceOf[DicomAttributes])
      .mapAsync(5) {
        case as: DicomAttributes => Source(as.attributes.toList).runWith(DicomAttributesSink.attributesSink)
      }

    val (_, dsMaybe) = Await.result(source.runWith(Sink.head), 10.seconds)
    val ds = dsMaybe.get

    ds.getString(Tag.PatientName) shouldBe dicomData.attributes.getString(Tag.PatientName)
  }

}
