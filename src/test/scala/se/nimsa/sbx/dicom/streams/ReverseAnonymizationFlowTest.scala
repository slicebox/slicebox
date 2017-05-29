package se.nimsa.sbx.dicom.streams

import akka.NotUsed
import akka.actor.ActorSystem
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.{Sink, Source}
import akka.stream.testkit.scaladsl.TestSink
import akka.testkit.TestKit
import akka.util.ByteString
import org.dcm4che3.data.Tag
import org.scalatest.{FlatSpecLike, Matchers}
import se.nimsa.dcm4che.streams.DicomFlows.TagModification
import se.nimsa.dcm4che.streams.DicomParts.{DicomAttributes, DicomPart}
import se.nimsa.dcm4che.streams.{DicomAttributesSink, DicomFlows, DicomParsing, DicomPartFlow}
import se.nimsa.sbx.dicom.{DicomData, DicomUtil}
import se.nimsa.sbx.util.TestUtil._

import scala.concurrent.Await
import scala.concurrent.duration.DurationInt

class ReverseAnonymizationFlowTest extends TestKit(ActorSystem("ReverseAnonymizationFlowSpec")) with FlatSpecLike with Matchers {

  implicit val materializer = ActorMaterializer()
  implicit val ec = system.dispatcher

  def attributesSource(dicomData: DicomData): Source[DicomPart, NotUsed] = {
    val bytes = ByteString(DicomUtil.toByteArray(dicomData))
    Source.single(bytes)
      .via(DicomPartFlow.partFlow)
      .via(DicomFlows.blacklistFilter(DicomParsing.isFileMetaInformation, keepPreamble = false))
  }

  def anonKeyPart(dicomData: DicomData) = {
    val key = createAnonymizationKey(dicomData.attributes)
    AnonymizationKeysPart(Seq(key), Some(key), Some(key), Some(key))
  }

  private def toAsciiBytes(s: String) = ByteString(s.getBytes("US-ASCII"))

  def anonSource(dicomData: DicomData) = {
    val key = anonKeyPart(dicomData).patientKey.get
    attributesSource(dicomData)
      .via(AnonymizationFlow.anonFlow)
      .via(DicomFlows.modifyFlow(
        TagModification(Tag.PatientName, _ => toAsciiBytes(key.anonPatientName), insert = false),
        TagModification(Tag.PatientID, _ => toAsciiBytes(key.anonPatientID), insert = false),
        TagModification(Tag.StudyInstanceUID, _ => toAsciiBytes(key.anonStudyInstanceUID), insert = false),
        TagModification(Tag.SeriesInstanceUID, _ => toAsciiBytes(key.anonSeriesInstanceUID), insert = false),
        TagModification(Tag.FrameOfReferenceUID, _ => toAsciiBytes(key.anonFrameOfReferenceUID), insert = false)
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
    val source = Source.empty
      .via(ReverseAnonymizationFlow.reverseAnonFlow)

    source.runWith(TestSink.probe[DicomPart])
      .expectHeaderAndValueChunkPairs(
        List(
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
