package se.nimsa.sbx.dicom.streams

import akka.NotUsed
import akka.actor.ActorSystem
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.{Sink, Source}
import akka.testkit.TestKit
import akka.util.ByteString
import org.dcm4che3.data.Tag
import org.scalatest.{FlatSpecLike, Matchers}
import se.nimsa.dcm4che.streams.DicomFlows.TagModification
import se.nimsa.dcm4che.streams.DicomParts.{DicomAttributes, DicomPart}
import se.nimsa.dcm4che.streams.{DicomAttributesSink, DicomFlows, DicomParsing, DicomPartFlow}
import se.nimsa.sbx.dicom.DicomUtil
import se.nimsa.sbx.util.TestUtil._

import scala.concurrent.Await
import scala.concurrent.duration.DurationInt

class ReverseAnonymizationFlowTest extends TestKit(ActorSystem("ReverseAnonymizationFlowSpec")) with FlatSpecLike with Matchers {

  implicit val materializer = ActorMaterializer()
  implicit val ec = system.dispatcher

  val dicomData = createDicomData()

  val attributesSource: Source[DicomPart, NotUsed] = {
    val bytes = ByteString(DicomUtil.toByteArray(dicomData))
    Source.single(bytes)
      .via(DicomPartFlow.partFlow)
      .via(DicomFlows.blacklistFilter(DicomParsing.isFileMetaInformation, keepPreamble = false))
  }

  val anonKeyPart = AnonymizationKeyPart(Some(createAnonymizationKey(dicomData.attributes)))

  private def toAsciiBytes(s: String) = ByteString(s.getBytes("US-ASCII"))

  "The reverse anonymization flow" should "reverse anonymization for attributes stored in anonymization key" in {
    val key = anonKeyPart.anonymizationKey.get

    val source = Source.single(anonKeyPart).concat(attributesSource)
      .via(AnonymizationFlow.anonFlow)
      .via(DicomFlows.modifyFlow(
        TagModification(Tag.PatientName, _ => toAsciiBytes(key.patientName), insert = false),
        TagModification(Tag.PatientID, _ => toAsciiBytes(key.patientID), insert = false),
        TagModification(Tag.StudyInstanceUID, _ => toAsciiBytes(key.studyInstanceUID), insert = false),
        TagModification(Tag.SeriesInstanceUID, _ => toAsciiBytes(key.seriesInstanceUID), insert = false),
        TagModification(Tag.FrameOfReferenceUID, _ => toAsciiBytes(key.frameOfReferenceUID), insert = false)
      ))
      .via(ReverseAnonymizationFlow.reverseAnonFlow)
      .via(DicomFlows.collectAttributesFlow(Set(
        Tag.PatientName,
        Tag.PatientID,
        Tag.StudyInstanceUID,
        Tag.SeriesInstanceUID,
        Tag.FrameOfReferenceUID,
        Tag.PatientBirthDate,
        Tag.StudyDescription,
        Tag.StudyID,
        Tag.AccessionNumber,
        Tag.SeriesDescription,
        Tag.ProtocolName
      )))
      .filter(_.isInstanceOf[DicomAttributes])
      .mapAsync(5) {
        case as: DicomAttributes => Source(as.attributes.toList).runWith(DicomAttributesSink.attributesSink)
      }

    val (fmiMaybe, dsMaybe) = Await.result(source.runWith(Sink.head), 10.seconds)
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

}
