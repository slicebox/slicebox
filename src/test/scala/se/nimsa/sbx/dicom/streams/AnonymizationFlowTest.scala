package se.nimsa.sbx.dicom.streams

import java.io.ByteArrayOutputStream
import java.util.Date

import akka.NotUsed
import akka.actor.ActorSystem
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.{Sink, Source}
import akka.stream.testkit.scaladsl.TestSink
import akka.testkit.TestKit
import akka.util.ByteString
import org.dcm4che3.data.{Attributes, Tag, UID, VR}
import org.dcm4che3.io.DicomOutputStream
import org.scalatest.{FlatSpecLike, Matchers}
import se.nimsa.dcm4che.streams.DicomParts.{DicomPart, DicomValueChunk}
import se.nimsa.dcm4che.streams.{DicomFlows, DicomPartFlow}
import se.nimsa.sbx.util.TestUtil._

import scala.concurrent.Await
import scala.concurrent.duration.DurationInt

class AnonymizationFlowTest extends TestKit(ActorSystem("AnonymizationFlowSpec")) with FlatSpecLike with Matchers {

  implicit val materializer = ActorMaterializer()
  implicit val ec = system.dispatcher

  def toSource(attributes: Attributes): Source[DicomPart, NotUsed] = {
    val baos = new ByteArrayOutputStream()
    val dos = new DicomOutputStream(baos, UID.ExplicitVRLittleEndian)
    dos.writeDataset(null, attributes)
    dos.close()

    Source.single(ByteString(baos.toByteArray))
      .via(DicomPartFlow.partFlow)
      .via(AnonymizationFlow.anonFlow)
  }

  def checkBasicAttributes(source: Source[DicomPart, NotUsed]) =
    source.runWith(TestSink.probe[DicomPart])
      .expectHeaderAndValueChunkPairs(
        Tag.SOPInstanceUID,
        Tag.PatientName,
        Tag.PatientID,
        Tag.PatientIdentityRemoved,
        Tag.DeidentificationMethod,
        Tag.StudyInstanceUID,
        Tag.SeriesInstanceUID)
      .expectDicomComplete()

  "The anonymization flow" should "replace an existing accession number with a named based UID" in {
    val attributes = new Attributes()
    attributes.setString(Tag.AccessionNumber, VR.SH, "ACC001")
    val source = toSource(attributes)

    source.runWith(TestSink.probe[DicomPart])
      .expectHeader(Tag.SOPInstanceUID)
      .expectValueChunk()
      .expectHeader(Tag.AccessionNumber)
      .request(1)
      .expectNextChainingPF {
        case v: DicomValueChunk =>
          v.bytes should not be empty
          v.bytes should not equal attributes.getString(Tag.AccessionNumber)
      }
      .expectHeaderAndValueChunkPairs(
        Tag.PatientName,
        Tag.PatientID,
        Tag.PatientIdentityRemoved,
        Tag.DeidentificationMethod,
        Tag.StudyInstanceUID,
        Tag.SeriesInstanceUID
      )
      .expectDicomComplete()
  }

  it should "add basic hierarchy attributes to an empty dataset" in {
    val source = toSource(new Attributes())
    checkBasicAttributes(source)
  }

  it should "leave an empty accession number empty" in {
    val attributes = new Attributes()
    attributes.setString(Tag.AccessionNumber, VR.SH, "")
    val source = toSource(attributes)

    source.runWith(TestSink.probe[DicomPart])
      .expectHeader(Tag.SOPInstanceUID)
      .expectValueChunk()
      .expectHeader(Tag.AccessionNumber)
      .request(1)
      .expectNextChainingPF {
        case v: DicomValueChunk =>
          v.bytes shouldBe empty
      }
  }

  it should "create an new UID from and existing UID" in {
    val attributes = new Attributes()
    attributes.setString(Tag.StudyInstanceUID, VR.UI, "1.2.3.4.5.6.7.8.9")
    val source = toSource(attributes)

    source.runWith(TestSink.probe[DicomPart])
      .expectHeaderAndValueChunkPairs(
        Tag.SOPInstanceUID,
        Tag.PatientName,
        Tag.PatientID,
        Tag.PatientIdentityRemoved,
        Tag.DeidentificationMethod)
      .expectHeader(Tag.StudyInstanceUID)
      .request(1)
      .expectNextChainingPF {
        case v: DicomValueChunk if new String(v.bytes.toArray, "US-ASCII") != attributes.getString(Tag.StudyInstanceUID) => true
      }
      .expectHeader(Tag.SeriesInstanceUID)
      .expectValueChunk()
      .expectDicomComplete()
  }

  it should "create a UID for UID tags which define DICOM hierarchy, regardless of whether value exists, is empty or has a previous value" in {
    val attributes1 = new Attributes()
    val attributes2 = new Attributes()
    attributes2.setString(Tag.StudyInstanceUID, VR.UI, "")
    val attributes3 = new Attributes()
    attributes3.setString(Tag.StudyInstanceUID, VR.UI, "1.2.3.4.5.6.7.8.9")

    val source1 = toSource(attributes1)
    val source2 = toSource(attributes2)
    val source3 = toSource(attributes3)

    def check(source: Source[DicomPart, NotUsed]) =
      source.runWith(TestSink.probe[DicomPart])
        .expectHeaderAndValueChunkPairs(
          Tag.SOPInstanceUID,
          Tag.PatientName,
          Tag.PatientID,
          Tag.PatientIdentityRemoved,
          Tag.DeidentificationMethod)
        .expectHeader(Tag.StudyInstanceUID)
        .request(1)
        .expectNextChainingPF {
          case v: DicomValueChunk if v.bytes.nonEmpty => true
        }
        .expectHeader(Tag.SeriesInstanceUID)
        .expectValueChunk()
        .expectDicomComplete()

    check(source1)
    check(source2)
    check(source3)
  }

  it should "always create the same new UID from some fixed existing UID" in {
    val attributes = new Attributes()
    attributes.setString(Tag.SOPInstanceUID, VR.UI, "1.2.3.4.5.6.7.8.9")
    val source = toSource(attributes)
      .via(DicomFlows.whitelistFilter(_ == Tag.SOPInstanceUID))
      .via(DicomFlows.attributeFlow)

    val f1 = source.runWith(Sink.head)
    val f2 = source.runWith(Sink.head)
    val (sop1, sop2) = Await.result(f1.zip(f2), 5.seconds)

    sop1.bytes shouldBe sop2.bytes
  }

  it should "remove private tags" in {
    val attributes = new Attributes()
    attributes.setString(Tag.SOPInstanceUID, VR.UI, "1.2.3.4.5.6.7.8")
    attributes.setString(0x80030010, VR.LO, "Private tag value")
    val source = toSource(attributes)
    checkBasicAttributes(source)
  }

  it should "remove overlay data" in {
    val attributes = new Attributes()
    attributes.setString(Tag.SOPInstanceUID, VR.UI, "1.2.3.4.5.6.7.8")
    attributes.setString(0x60020010, VR.PN, "34")
    val source = toSource(attributes)
    checkBasicAttributes(source)
  }

  it should "remove birth date" in {
    val attributes = new Attributes()
    attributes.setDate(Tag.PatientBirthDate, VR.DA, new Date(123456789876L))
    val source = toSource(attributes)
    checkBasicAttributes(source)
  }

}
