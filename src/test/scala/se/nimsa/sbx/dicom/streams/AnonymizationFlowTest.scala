package se.nimsa.sbx.dicom.streams

import akka.NotUsed
import akka.actor.ActorSystem
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.{Flow, Sink, Source}
import akka.stream.testkit.scaladsl.TestSink
import akka.testkit.TestKit
import akka.util.ByteString
import org.scalatest.{BeforeAndAfterAll, FlatSpecLike, Matchers}
import se.nimsa.dicom.data.DicomParts.{DicomPart, ElementsPart, ValueChunk}
import se.nimsa.dicom.data._
import se.nimsa.dicom.streams.CollectFlow.collectFlow
import se.nimsa.dicom.streams.{DicomFlows, ElementFlows}
import se.nimsa.sbx.anonymization.{AnonymizationProfile, ConfidentialityOption}
import se.nimsa.sbx.dicom.SliceboxTags._
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

  def anonFlow: Flow[DicomPart, DicomPart, NotUsed] = new AnonymizationFlow(
    AnonymizationProfile(Seq(
      ConfidentialityOption.BASIC_PROFILE,
      ConfidentialityOption.RETAIN_LONGITUDINAL_TEMPORAL_INFORMATION
    ))).anonFlow

  def toSource(elements: Elements): Source[DicomPart, NotUsed] = Source(elements.toParts)

  def toAnonSource(elements: Elements): Source[DicomPart, NotUsed] =
    toSource(elements).via(anonFlow)

  def toMaybeAnonSource(elements: Elements): Source[DicomPart, NotUsed] =
    toSource(elements)
      .via(collectFlow((encodingTags ++ anonymizationTags ++ anonKeysTags).map(TagPath.fromTag) ++ valueTags.map(_.tagPath), "anon"))
      .via(conditionalFlow({ case p: ElementsPart if p.label == "anon" => !isAnonymous(p.elements) }, anonFlow, identityFlow))

  def checkBasicAttributes(source: Source[DicomPart, NotUsed]): PartProbe =
    source.runWith(TestSink.probe[DicomPart])
      .expectHeaderAndValueChunkPairs(
        Tag.PatientIdentityRemoved,
        Tag.DeidentificationMethod)
      .expectDicomComplete()

  "The anonymization flow" should "replace an existing accession number a zero length value" in {
    val elements = Elements.empty()
      .setString(Tag.AccessionNumber, "ACC001")
    val source = toAnonSource(elements)

    source.runWith(TestSink.probe[DicomPart])
      .expectHeader(Tag.AccessionNumber)
      .expectHeaderAndValueChunkPairs(
        Tag.PatientIdentityRemoved,
        Tag.DeidentificationMethod
      )
      .expectDicomComplete()
  }

  it should "add anonymization attributes" in {
    val elements = Elements.empty()
      .setString(Tag.Modality, "NM")
    val source = toAnonSource(elements)

    source.runWith(TestSink.probe[DicomPart])
      .expectHeaderAndValueChunkPairs(
        Tag.Modality,
        Tag.PatientIdentityRemoved,
        Tag.DeidentificationMethod
      )
      .expectDicomComplete()
  }

  it should "leave an empty accession number empty" in {
    val elements = Elements.empty()
      .setString(Tag.AccessionNumber, "")
    val source = toAnonSource(elements)

    source.runWith(TestSink.probe[DicomPart])
      .expectHeader(Tag.AccessionNumber)
      .expectHeaderAndValueChunkPairs(
        Tag.PatientIdentityRemoved,
        Tag.DeidentificationMethod
      )
      .expectDicomComplete()
  }

  it should "create an new random UID from an existing UID" in {
    val elements = Elements.empty()
      .setString(Tag.StudyInstanceUID, "1.2.3.4.5.6.7.8.9")
    val source = toAnonSource(elements)

    source.runWith(TestSink.probe[DicomPart])
      .expectHeaderAndValueChunkPairs(
        Tag.PatientIdentityRemoved,
        Tag.DeidentificationMethod)
      .expectHeader(Tag.StudyInstanceUID)
      .request(1)
      .expectNextChainingPF {
        case v: ValueChunk =>
          v.bytes.utf8String.trim should not be elements.getString(Tag.StudyInstanceUID).get
      }
      .expectDicomComplete()
  }

  it should "create a new random UID for each anonymization from some fixed existing UID" in {
    val elements = Elements.empty()
      .setString(Tag.TargetUID, "1.2.3.4.5.6.7.8.9")

    def source() = toAnonSource(elements)
      .via(DicomFlows.tagFilter(_ => false)(tagPath => tagPath.tag == Tag.TargetUID))
      .via(ElementFlows.elementFlow)

    val f1 = source().runWith(Sink.head)
    val f2 = source().runWith(Sink.head)
    val (sop1, sop2) = Await.result(f1.zip(f2), 5.seconds)

    sop1 should not be sop2
  }

  it should "remove private tags" in {
    val elements = Elements.empty()
      .setString(Tag.Modality, "NM")
      .setString(Tag.SOPInstanceUID, "1.2.3.4.5.6.7.8")
      .setString(0x80030010, "Private tag value")
    val source = toAnonSource(elements)

    source.runWith(TestSink.probe[DicomPart])
      .expectHeaderAndValueChunkPairs(
        Tag.SOPInstanceUID,
        Tag.Modality,
        Tag.PatientIdentityRemoved,
        Tag.DeidentificationMethod
      )
      .expectDicomComplete()
  }

  it should "remove overlay data" in {
    val elements = Elements.empty()
      .setString(Tag.Modality, "NM")
      .setString(Tag.SOPInstanceUID, "1.2.3.4.5.6.7.8")
      .setString(0x60024000, "34")
    val source = toAnonSource(elements)

    source.runWith(TestSink.probe[DicomPart])
      .expectHeaderAndValueChunkPairs(
        Tag.SOPInstanceUID,
        Tag.Modality,
        Tag.PatientIdentityRemoved,
        Tag.DeidentificationMethod
      )
      .expectDicomComplete()
  }

  it should "remove birth date" in {
    val elements = Elements.empty()
      .setString(Tag.Modality, "NM")
      .setString(Tag.PatientBirthDate, "20040325")
    val source = toAnonSource(elements)

    source.runWith(TestSink.probe[DicomPart])
      .expectHeaderAndValueChunkPairs(Tag.Modality)
      .expectHeader(Tag.PatientBirthDate)
      .expectHeaderAndValueChunkPairs(
        Tag.PatientIdentityRemoved,
        Tag.DeidentificationMethod
      )
      .expectDicomComplete()
  }

  "The conditional anonymization flow" should "anonymize data which has not been anonymized" in {
    val elements = Elements.empty()
      .setString(Tag.PatientID, "12345678")
    val source = toMaybeAnonSource(elements)

    source.runWith(TestSink.probe[DicomPart])
      .expectMetaPart()
      .expectHeader(Tag.PatientID)
      .expectHeaderAndValueChunkPairs(
        Tag.PatientIdentityRemoved,
        Tag.DeidentificationMethod
      )
      .expectDicomComplete()
  }

  it should "not anonymize already anonymized data" in {
    val elements = Elements.empty()
      .setString(Tag.PatientID, "12345678")
      .setString(Tag.PatientIdentityRemoved, "YES")
    val source = toMaybeAnonSource(elements).via(DicomFlows.tagFilter(_ => false)(tagPath => tagPath.tag == Tag.PatientID))

    source.runWith(TestSink.probe[DicomPart])
      .expectHeader(Tag.PatientID)
      .request(1)
      .expectNextChainingPF {
        case v: ValueChunk =>
          v.bytes shouldBe elements.getBytes(Tag.PatientID).get
      }
      .expectDicomComplete()
  }

  it should "anonymize if PatientIdentityRemoved=YES but ElementsPart is missing" in {
    val elements = Elements.empty()
      .setString(Tag.PatientID, "12345678")
      .setString(Tag.PatientIdentityRemoved, "YES")
    val source = toSource(elements)
      .via(conditionalFlow({ case p: ElementsPart => !isAnonymous(p.elements) }, anonFlow, identityFlow))
      .via(DicomFlows.tagFilter(_ => false)(tagPath => tagPath.tag == Tag.PatientID))

    source.runWith(TestSink.probe[DicomPart])
      .expectHeader(Tag.PatientID)
      .expectDicomComplete()
  }

  it should "preserve patient characteristics" in {
    def anonFlow: Flow[DicomPart, DicomPart, NotUsed] = new AnonymizationFlow(
      AnonymizationProfile(Seq(
        ConfidentialityOption.BASIC_PROFILE,
        ConfidentialityOption.RETAIN_PATIENT_CHARACTERISTICS
      ))).anonFlow

    val elements = Elements.empty()
      .setString(Tag.PatientSex, "F")
      .setString(Tag.SmokingStatus, "Y")
    val source = toSource(elements).via(anonFlow)

    source.runWith(TestSink.probe[DicomPart])
      .expectHeader(Tag.PatientSex)
      .expectValueChunk(ByteString("F "))
      .expectHeader(Tag.SmokingStatus)
      .expectValueChunk(ByteString("Y "))
      .expectHeaderAndValueChunkPairs(
        Tag.PatientIdentityRemoved,
        Tag.DeidentificationMethod,
      )
      .expectDicomComplete()
  }

  it should "preserve safe private attributes" in {
    def anonFlow: Flow[DicomPart, DicomPart, NotUsed] = new AnonymizationFlow(
      AnonymizationProfile(Seq(
        ConfidentialityOption.BASIC_PROFILE,
        ConfidentialityOption.RETAIN_SAFE_PRIVATE
      ))).anonFlow

    val elements = Elements.empty()
      .setString(0x70534009, "1.23") // safe (Philips SUV factor)
      .setString(0x70534010, "Value") // unsafe (some other private attribute)
    val source = toSource(elements).via(anonFlow)

    source.runWith(TestSink.probe[DicomPart])
      .expectHeaderAndValueChunkPairs(
        Tag.PatientIdentityRemoved,
        Tag.DeidentificationMethod
      )
      .expectHeader(0x70534009)
      .expectValueChunk(ByteString("1.23"))
      .expectDicomComplete()
  }
}
