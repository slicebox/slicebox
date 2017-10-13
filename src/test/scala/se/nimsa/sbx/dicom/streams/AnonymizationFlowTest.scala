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
import org.scalatest.{BeforeAndAfterAll, FlatSpecLike, Matchers}
import se.nimsa.dcm4che.streams.DicomFlows.collectAttributesFlow
import se.nimsa.dcm4che.streams.DicomParts.{DicomPart, DicomValueChunk}
import se.nimsa.dcm4che.streams.{DicomFlows, DicomParseFlow}
import se.nimsa.sbx.dicom.streams.DicomStreamOps.{attributesToMetaPart, metaTags2Collect}
import se.nimsa.sbx.util.TestUtil._

import scala.concurrent.{Await, ExecutionContextExecutor}
import scala.concurrent.duration.DurationInt

class AnonymizationFlowTest extends TestKit(ActorSystem("AnonymizationFlowSpec")) with FlatSpecLike with Matchers with BeforeAndAfterAll {

  implicit val materializer: ActorMaterializer = ActorMaterializer()
  implicit val ec: ExecutionContextExecutor = system.dispatcher

  override def afterAll: Unit = TestKit.shutdownActorSystem(system)

  def toSource(attributes: Attributes): Source[DicomPart, NotUsed] = {
    val baos = new ByteArrayOutputStream()
    val dos = new DicomOutputStream(baos, UID.ExplicitVRLittleEndian)
    dos.writeDataset(null, attributes)
    dos.close()

    Source.single(ByteString(baos.toByteArray))
      .via(DicomParseFlow.parseFlow)
  }

  def toAnonSource(attributes: Attributes): Source[DicomPart, NotUsed] =
    toSource(attributes).via(AnonymizationFlow.anonFlow)

  def toMaybeAnonSource(attributes: Attributes): Source[DicomPart, NotUsed] =
    toSource(attributes)
      .via(collectAttributesFlow(metaTags2Collect))
      .mapAsync(5)(attributesToMetaPart)
      .via(AnonymizationFlow.maybeAnonFlow)

  def checkBasicAttributes(source: Source[DicomPart, NotUsed]): PartProbe =
    source.runWith(TestSink.probe[DicomPart])
      .expectHeaderAndValueChunkPairs(
        Tag.SOPInstanceUID,
        Tag.Modality,
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
    val source = toAnonSource(attributes)

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

  it should "add basic hierarchy attributes also when not present" in {
    val attributes = new Attributes()
    attributes.setString(Tag.Modality, VR.CS, "NM")
    val source = toAnonSource(attributes)
    checkBasicAttributes(source)
  }

  it should "leave an empty accession number empty" in {
    val attributes = new Attributes()
    attributes.setString(Tag.AccessionNumber, VR.SH, "")
    val source = toAnonSource(attributes)

    source.runWith(TestSink.probe[DicomPart])
      .expectHeader(Tag.SOPInstanceUID)
      .expectValueChunk()
      .expectHeader(Tag.AccessionNumber)
      .expectHeader(Tag.PatientName)
  }

  it should "create an new UID from and existing UID" in {
    val attributes = new Attributes()
    attributes.setString(Tag.StudyInstanceUID, VR.UI, "1.2.3.4.5.6.7.8.9")
    val source = toAnonSource(attributes)

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
        case v: DicomValueChunk if v.bytes.utf8String.trim != attributes.getString(Tag.StudyInstanceUID) => true
      }
      .expectHeader(Tag.SeriesInstanceUID)
      .expectValueChunk()
      .expectDicomComplete()
  }

  it should "create a UID for UID tags which define DICOM hierarchy, regardless of whether value exists, is empty or has a previous value" in {
    val attributes1 = new Attributes()
    attributes1.setString(Tag.Modality, VR.CS, "NM")
    val attributes2 = new Attributes()
    attributes2.setString(Tag.Modality, VR.CS, "NM")
    attributes2.setString(Tag.StudyInstanceUID, VR.UI, "")
    val attributes3 = new Attributes()
    attributes3.setString(Tag.Modality, VR.CS, "NM")
    attributes3.setString(Tag.StudyInstanceUID, VR.UI, "1.2.3.4.5.6.7.8.9")

    val source1 = toAnonSource(attributes1)
    val source2 = toAnonSource(attributes2)
    val source3 = toAnonSource(attributes3)

    def check(source: Source[DicomPart, NotUsed]) =
      source.runWith(TestSink.probe[DicomPart])
        .expectHeaderAndValueChunkPairs(
          Tag.SOPInstanceUID,
          Tag.Modality,
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
    def source() = toAnonSource(attributes)
      .via(DicomFlows.tagFilter(_ => false)(tagPath => tagPath.tag == Tag.SOPInstanceUID))
      .via(DicomFlows.attributeFlow)

    val f1 = source().runWith(Sink.head)
    val f2 = source().runWith(Sink.head)
    val (sop1, sop2) = Await.result(f1.zip(f2), 5.seconds)

    sop1.bytes shouldBe sop2.bytes
  }

  it should "remove private tags" in {
    val attributes = new Attributes()
    attributes.setString(Tag.Modality, VR.CS, "NM")
    attributes.setString(Tag.SOPInstanceUID, VR.UI, "1.2.3.4.5.6.7.8")
    attributes.setString(0x80030010, VR.LO, "Private tag value")
    val source = toAnonSource(attributes)
    checkBasicAttributes(source)
  }

  it should "remove overlay data" in {
    val attributes = new Attributes()
    attributes.setString(Tag.Modality, VR.CS, "NM")
    attributes.setString(Tag.SOPInstanceUID, VR.UI, "1.2.3.4.5.6.7.8")
    attributes.setString(0x60020010, VR.PN, "34")
    val source = toAnonSource(attributes)
    checkBasicAttributes(source)
  }

  it should "remove birth date" in {
    val attributes = new Attributes()
    attributes.setString(Tag.Modality, VR.CS, "NM")
    attributes.setDate(Tag.PatientBirthDate, VR.DA, new Date(123456789876L))
    val source = toAnonSource(attributes)
    checkBasicAttributes(source)
  }

  it should "anonymize already anonymized data" in {
    val attributes = new Attributes()
    attributes.setString(Tag.PatientID, VR.LO, "John^Doe")
    val source1 = toAnonSource(attributes)
    val source2 = source1.via(AnonymizationFlow.anonFlow)

    val f1 = source1
      .via(DicomFlows.tagFilter(_ => false)(tagPath => tagPath.tag == Tag.PatientID))
      .map(_.bytes)
      .runWith(Sink.fold(ByteString.empty)(_ ++ _))
    val f2 = source2
      .via(DicomFlows.tagFilter(_ => false)(tagPath => tagPath.tag == Tag.PatientID))
      .map(_.bytes)
      .runWith(Sink.fold(ByteString.empty)(_ ++ _))
    val (ds1, ds2) = Await.result(f1.zip(f2), 5.seconds)

    ds1 should not be ds2
  }

  "The conditional anonymization flow" should "anonymize data which has not been anonymized" in {
    val attributes = new Attributes()
    attributes.setString(Tag.PatientID, VR.LO, "John^Doe")
    val source = toMaybeAnonSource(attributes).via(DicomFlows.tagFilter(_ => false)(tagPath => tagPath.tag == Tag.PatientID))

    source.runWith(TestSink.probe[DicomPart])
      .expectHeader(Tag.PatientID)
      .request(1)
      .expectNextChainingPF {
        case v: DicomValueChunk =>
          v.bytes should not be attributes.getBytes(Tag.PatientID)
      }
      .expectDicomComplete()
  }

  it should "not anonymize already anonymized data" in {
    val attributes = new Attributes()
    attributes.setString(Tag.PatientID, VR.LO, "John^Doe")
    attributes.setString(Tag.PatientIdentityRemoved, VR.CS, "YES")
    val source = toMaybeAnonSource(attributes).via(DicomFlows.tagFilter(_ => false)(tagPath => tagPath.tag == Tag.PatientID))

    source.runWith(TestSink.probe[DicomPart])
      .expectHeader(Tag.PatientID)
      .request(1)
      .expectNextChainingPF {
        case v: DicomValueChunk =>
          v.bytes shouldBe attributes.getBytes(Tag.PatientID)
      }
      .expectDicomComplete()
  }

  it should "anonymize if PatientIdentityRemoved=YES but DicomMetaData part is missing" in {
    val attributes = new Attributes()
    attributes.setString(Tag.PatientID, VR.LO, "John^Doe")
    attributes.setString(Tag.PatientIdentityRemoved, VR.CS, "YES")
    val source = toSource(attributes).via(AnonymizationFlow.maybeAnonFlow).via(DicomFlows.tagFilter(_ => false)(tagPath => tagPath.tag == Tag.PatientID))

    source.runWith(TestSink.probe[DicomPart])
      .expectHeader(Tag.PatientID)
      .request(1)
      .expectNextChainingPF {
        case v: DicomValueChunk =>
          v.bytes should not be attributes.getBytes(Tag.PatientID)
      }
      .expectDicomComplete()
  }
}
