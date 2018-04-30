package se.nimsa.sbx.dicom.streams

import akka.NotUsed
import akka.actor.ActorSystem
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.{Sink, Source}
import akka.stream.testkit.scaladsl.TestSink
import akka.testkit.TestKit
import akka.util.ByteString
import org.scalatest.{BeforeAndAfterAll, FlatSpecLike, Matchers}
import se.nimsa.dicom.DicomParts.{DicomPart, DicomValueChunk}
import se.nimsa.dicom._
import se.nimsa.dicom.streams.CollectFlow.collectFlow
import se.nimsa.dicom.streams.{DicomFlows, ElementFolds}
import se.nimsa.sbx.dicom.streams.DicomStreamUtil._
import se.nimsa.sbx.storage.{RuntimeStorage, StorageService}
import se.nimsa.sbx.util.TestUtil._

import scala.concurrent.duration.DurationInt
import scala.concurrent.{Await, ExecutionContextExecutor}

class AnonymizationFlowTest extends TestKit(ActorSystem("AnonymizationFlowSpec")) with FlatSpecLike with Matchers with BeforeAndAfterAll {

  implicit val materializer: ActorMaterializer = ActorMaterializer()
  implicit val ec: ExecutionContextExecutor = system.dispatcher

  override def afterAll: Unit = TestKit.shutdownActorSystem(system)

  val storage: StorageService = new RuntimeStorage

  def toSource(elements: Elements): Source[DicomPart, NotUsed] =
    Source.single(elements.bytes).via(storage.parseFlow(None))

  def toAnonSource(elements: Elements): Source[DicomPart, NotUsed] =
    toSource(elements).via(AnonymizationFlow.anonFlow)

  def toMaybeAnonSource(elements: Elements): Source[DicomPart, NotUsed] =
    toSource(elements)
      .via(collectFlow(basicInfoTags, "anon"))
      .map(attributesToInfoPart(_, "anon"))
      .via(AnonymizationFlow.maybeAnonFlow)

  def checkBasicAttributes(source: Source[DicomPart, NotUsed]): PartProbe =
    source.runWith(TestSink.probe[DicomPart])
      .expectHeaderAndValueChunkPairs(
        Tag.SpecificCharacterSet,
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
    val elements = Elements.empty
        .update(Tag.AccessionNumber, Element.explicitLE(Tag.AccessionNumber, VR.SH, ByteString("ACC001")))
    val source = toAnonSource(elements)

    source.runWith(TestSink.probe[DicomPart])
      .expectHeader(Tag.SpecificCharacterSet)
      .expectValueChunk()
      .expectHeader(Tag.SOPInstanceUID)
      .expectValueChunk()
      .expectHeader(Tag.AccessionNumber)
      .request(1)
      .expectNextChainingPF {
        case v: DicomValueChunk =>
          v.bytes should not be empty
          v.bytes should not equal elements(Tag.AccessionNumber).get.value
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
    val elements = Elements.empty
      .update(Tag.Modality, Element.explicitLE(Tag.Modality, VR.CS, ByteString("NM")))
    val source = toAnonSource(elements)
    checkBasicAttributes(source)
  }

  it should "leave an empty accession number empty" in {
    val elements = Elements.empty
      .update(Tag.AccessionNumber, Element.explicitLE(Tag.AccessionNumber, VR.SH, ByteString("")))
    val source = toAnonSource(elements)

    source.runWith(TestSink.probe[DicomPart])
      .expectHeader(Tag.SpecificCharacterSet)
      .expectValueChunk()
      .expectHeader(Tag.SOPInstanceUID)
      .expectValueChunk()
      .expectHeader(Tag.AccessionNumber)
      .expectHeader(Tag.PatientName)
  }

  it should "create an new UID from and existing UID" in {
    val elements = Elements.empty
      .update(Tag.StudyInstanceUID, Element.explicitLE(Tag.StudyInstanceUID, VR.UI, ByteString("1.2.3.4.5.6.7.8.9")))
    val source = toAnonSource(elements)

    source.runWith(TestSink.probe[DicomPart])
      .expectHeaderAndValueChunkPairs(
        Tag.SpecificCharacterSet,
        Tag.SOPInstanceUID,
        Tag.PatientName,
        Tag.PatientID,
        Tag.PatientIdentityRemoved,
        Tag.DeidentificationMethod)
      .expectHeader(Tag.StudyInstanceUID)
      .request(1)
      .expectNextChainingPF {
        case v: DicomValueChunk if v.bytes.utf8String.trim != elements(Tag.StudyInstanceUID).get.toSingleString() => true
      }
      .expectHeader(Tag.SeriesInstanceUID)
      .expectValueChunk()
      .expectDicomComplete()
  }

  it should "create a UID for UID tags which define DICOM hierarchy, regardless of whether value exists, is empty or has a previous value" in {
    val elements1 = Elements.empty
      .update(Tag.Modality, Element.explicitLE(Tag.Modality, VR.CS, ByteString("NM")))
    val elements2 = Elements.empty
      .update(Tag.Modality, Element.explicitLE(Tag.Modality, VR.CS, ByteString("NM")))
      .update(Tag.StudyInstanceUID, Element.explicitLE(Tag.StudyInstanceUID, VR.UI, ByteString("")))
    val elements3 = Elements.empty
      .update(Tag.Modality, Element.explicitLE(Tag.Modality, VR.CS, ByteString("NM")))
      .update(Tag.StudyInstanceUID, Element.explicitLE(Tag.StudyInstanceUID, VR.UI, ByteString("1.2.3.4.5.6.7.8.9")))

    val source1 = toAnonSource(elements1)
    val source2 = toAnonSource(elements2)
    val source3 = toAnonSource(elements3)

    def check(source: Source[DicomPart, NotUsed]) =
      source.runWith(TestSink.probe[DicomPart])
        .expectHeaderAndValueChunkPairs(
          Tag.SpecificCharacterSet,
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
    val elements = Elements.empty
      .update(Tag.TargetUID, Element.explicitLE(Tag.TargetUID, VR.UI, ByteString("1.2.3.4.5.6.7.8.9")))

    def source() = toAnonSource(elements)
      .via(DicomFlows.tagFilter(_ => false)(tagPath => tagPath.tag == Tag.TargetUID))
      .via(ElementFolds.elementsFlow)

    val f1 = source().runWith(Sink.head)
    val f2 = source().runWith(Sink.head)
    val (sop1, sop2) = Await.result(f1.zip(f2), 5.seconds)

    sop1.element.value shouldBe sop2.element.value
  }

  it should "remove private tags" in {
    val elements = Elements.empty
      .update(Tag.Modality, Element.explicitLE(Tag.Modality, VR.CS, ByteString("NM")))
      .update(Tag.SOPInstanceUID, Element.explicitLE(Tag.SOPInstanceUID, VR.UI, ByteString("1.2.3.4.5.6.7.8")))
      .update(0x80030010, Element.explicitLE(0x80030010, VR.LO, ByteString("Private tag value")))
    val source = toAnonSource(elements)
    checkBasicAttributes(source)
  }

  it should "remove overlay data" in {
    val elements = Elements.empty
      .update(Tag.Modality, Element.explicitLE(Tag.Modality, VR.CS, ByteString("NM")))
      .update(Tag.SOPInstanceUID, Element.explicitLE(Tag.SOPInstanceUID, VR.UI, ByteString("1.2.3.4.5.6.7.8")))
      .update(0x60020010, Element.explicitLE(0x60020010, VR.PN, ByteString("34")))
    val source = toAnonSource(elements)
    checkBasicAttributes(source)
  }

  it should "remove birth date" in {
    val elements = Elements.empty
      .update(Tag.Modality, Element.explicitLE(Tag.Modality, VR.CS, ByteString("NM")))
      .update(Tag.PatientBirthDate, Element.explicitLE(Tag.PatientBirthDate, VR.DA, ByteString("20040325")))
    val source = toAnonSource(elements)
    checkBasicAttributes(source)
  }

  it should "anonymize already anonymized data" in {
    val elements = Elements.empty
      .update(Tag.PatientID, Element.explicitLE(Tag.PatientID, VR.LO, ByteString("12345678")))
    val source1 = toAnonSource(elements)
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
    val elements = Elements.empty
      .update(Tag.PatientID, Element.explicitLE(Tag.PatientID, VR.LO, ByteString("12345678")))
    val source = toMaybeAnonSource(elements).via(DicomFlows.tagFilter(_ => false)(tagPath => tagPath.tag == Tag.PatientID))

    source.runWith(TestSink.probe[DicomPart])
      .expectHeader(Tag.PatientID)
      .request(1)
      .expectNextChainingPF {
        case v: DicomValueChunk =>
          v.bytes should not be elements(Tag.PatientID).get.value
      }
      .expectDicomComplete()
  }

  it should "not anonymize already anonymized data" in {
    val elements = Elements.empty
      .update(Tag.PatientID, Element.explicitLE(Tag.PatientID, VR.LO, ByteString("12345678")))
      .update(Tag.PatientIdentityRemoved, Element.explicitLE(Tag.PatientIdentityRemoved, VR.CS, ByteString("YES")))
    val source = toMaybeAnonSource(elements).via(DicomFlows.tagFilter(_ => false)(tagPath => tagPath.tag == Tag.PatientID))

    source.runWith(TestSink.probe[DicomPart])
      .expectHeader(Tag.PatientID)
      .request(1)
      .expectNextChainingPF {
        case v: DicomValueChunk =>
          v.bytes shouldBe elements(Tag.PatientID).get.value
      }
      .expectDicomComplete()
  }

  it should "anonymize if PatientIdentityRemoved=YES but DicomInfoPart is missing" in {
    val elements = Elements.empty
      .update(Tag.PatientID, Element.explicitLE(Tag.PatientID, VR.LO, ByteString("12345678")))
      .update(Tag.PatientIdentityRemoved, Element.explicitLE(Tag.PatientIdentityRemoved, VR.CS, ByteString("YES")))
    val source = toSource(elements).via(AnonymizationFlow.maybeAnonFlow).via(DicomFlows.tagFilter(_ => false)(tagPath => tagPath.tag == Tag.PatientID))

    source.runWith(TestSink.probe[DicomPart])
      .expectHeader(Tag.PatientID)
      .request(1)
      .expectNextChainingPF {
        case v: DicomValueChunk =>
          v.bytes should not be elements(Tag.PatientID).get.value
      }
      .expectDicomComplete()
  }
}
