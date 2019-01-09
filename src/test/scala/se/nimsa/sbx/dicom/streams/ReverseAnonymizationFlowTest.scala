package se.nimsa.sbx.dicom.streams

import akka.NotUsed
import akka.actor.ActorSystem
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.{Flow, Sink, Source}
import akka.stream.testkit.scaladsl.TestSink
import akka.testkit.TestKit
import akka.util.ByteString
import org.scalatest.{BeforeAndAfterAll, FlatSpecLike, Matchers}
import se.nimsa.dicom.data.DicomParts.{DicomPart, ElementsPart, HeaderPart, ValueChunk}
import se.nimsa.dicom.data.Elements.{Item, Sequence, ValueElement}
import se.nimsa.dicom.data._
import se.nimsa.dicom.streams.ModifyFlow.TagModification
import se.nimsa.dicom.streams.{DicomFlows, ElementFlows, ElementSink, ModifyFlow}
import se.nimsa.sbx.anonymization.AnonymizationProtocol.{AnonymizationKeyOpResult, AnonymizationKeyValue}
import se.nimsa.sbx.anonymization.{AnonymizationProfile, ConfidentialityOption}
import se.nimsa.sbx.dicom.DicomHierarchy.DicomHierarchyLevel
import se.nimsa.sbx.dicom.streams.DicomStreamUtil._
import se.nimsa.sbx.storage.{RuntimeStorage, StorageService}
import se.nimsa.sbx.util.TestUtil._

import scala.concurrent.duration.DurationInt
import scala.concurrent.{Await, ExecutionContextExecutor}

class ReverseAnonymizationFlowTest extends TestKit(ActorSystem("ReverseAnonymizationFlowSpec")) with FlatSpecLike with Matchers with BeforeAndAfterAll {

  import DicomTestData._
  import ReverseAnonymizationFlow.reverseAnonFlow
  import ModifyFlow.modifyFlow
  import ElementFlows.elementFlow

  implicit val materializer: ActorMaterializer = ActorMaterializer()
  implicit val ec: ExecutionContextExecutor = system.dispatcher

  override def afterAll: Unit = TestKit.shutdownActorSystem(system)

  val storage: StorageService = new RuntimeStorage

  def anonFlow: Flow[DicomPart, DicomPart, NotUsed] = new AnonymizationFlow(
    AnonymizationProfile(Seq(
      ConfidentialityOption.BASIC_PROFILE,
      ConfidentialityOption.RETAIN_LONGITUDINAL_TEMPORAL_INFORMATION
    ))).anonFlow

  def elementsSource(elements: Elements): Source[DicomPart, NotUsed] =
    Source.single(elements.toBytes())
      .via(storage.parseFlow(None))
      .via(DicomFlows.tagFilter(_ => false)(tagPath => !DicomParsing.isFileMetaInformation(tagPath.tag)))

  def anonKeyResultPart(elements: Elements, matchLevel: DicomHierarchyLevel): AnonymizationKeyOpResultPart = {
    val key = createAnonymizationKey(elements)
    AnonymizationKeyOpResultPart(AnonymizationKeyOpResult(matchLevel, Some(key),
      Seq(
        AnonymizationKeyValue(-1, key.id, TagPath.fromTag(Tag.PatientName), key.patientName, key.anonPatientName),
        AnonymizationKeyValue(-1, key.id, TagPath.fromTag(Tag.PatientID), key.patientID, key.anonPatientID),
        AnonymizationKeyValue(-1, key.id, TagPath.fromTag(Tag.StudyInstanceUID), key.studyInstanceUID, key.anonStudyInstanceUID),
        AnonymizationKeyValue(-1, key.id, TagPath.fromTag(Tag.SeriesInstanceUID), key.seriesInstanceUID, key.anonSeriesInstanceUID),
        AnonymizationKeyValue(-1, key.id, TagPath.fromTag(Tag.SOPInstanceUID), key.sopInstanceUID, key.anonSOPInstanceUID),
        AnonymizationKeyValue(-1, key.id, TagPath.fromItem(Tag.DerivationCodeSequence, 1).thenTag(Tag.PatientName), "name", "anon name")
      )))
  }

  def anonSource(elements: Elements): Source[DicomPart, NotUsed] = {
    val key = anonKeyResultPart(elements, DicomHierarchyLevel.IMAGE).result.anonymizationKeyMaybe.get
    elementsSource(elements)
      .via(anonFlow)
      .via(modifyFlow(modifications = Seq(
        TagModification.equals(TagPath.fromTag(Tag.PatientName), _ => toAsciiBytes(key.anonPatientName, VR.PN)),
        TagModification.equals(TagPath.fromTag(Tag.PatientID), _ => toAsciiBytes(key.anonPatientID, VR.LO)),
        TagModification.equals(TagPath.fromTag(Tag.StudyInstanceUID), _ => toAsciiBytes(key.anonStudyInstanceUID, VR.UI)),
        TagModification.equals(TagPath.fromTag(Tag.SeriesInstanceUID), _ => toAsciiBytes(key.anonSeriesInstanceUID, VR.UI)),
        TagModification.equals(TagPath.fromTag(Tag.SOPInstanceUID), _ => toAsciiBytes(key.anonSOPInstanceUID, VR.UI))
      )))
  }

  "The reverse anonymization flow" should "reverse anonymization using information stored in anonymization key" in {
    val elements = createElements()

    val source = Source.single(anonKeyResultPart(elements, DicomHierarchyLevel.IMAGE))
      .concat(anonSource(elements))
      .via(reverseAnonFlow)
      .via(elementFlow)

    val reversedElements = Await.result(source.runWith(ElementSink.elementSink), 10.seconds)

    reversedElements(Tag.PatientName) shouldBe elements(Tag.PatientName)
    reversedElements(Tag.PatientID) shouldBe elements(Tag.PatientID)
    reversedElements(Tag.StudyInstanceUID) shouldBe elements(Tag.StudyInstanceUID)
    reversedElements(Tag.SeriesInstanceUID) shouldBe elements(Tag.SeriesInstanceUID)
    reversedElements(Tag.SOPInstanceUID) shouldBe elements(Tag.SOPInstanceUID)
  }

  it should "insert anonymization key attributes into dataset even if they originally were not present" in {
    val elements = Elements.empty()
      .setString(Tag.Modality, "NM")
    val source = Source.single(anonKeyResultPart(elements, DicomHierarchyLevel.IMAGE))
      .concat(anonSource(elements))
      .via(reverseAnonFlow)
      .mapConcat {
        case p: HeaderPart if p.length == 0 => p :: ValueChunk(bigEndian = false, ByteString.empty, last = true) :: Nil
        case p => p :: Nil
      }


    source.runWith(TestSink.probe[DicomPart])
      .expectHeaderAndValueChunkPairs(
        List(
          Tag.Modality,
          Tag.PatientName,
          Tag.PatientID,
          Tag.PatientIdentityRemoved,
          Tag.DeidentificationMethod,
          Tag.StudyInstanceUID,
          Tag.SeriesInstanceUID,
          Tag.SOPInstanceUID
        ).sorted: _*
      )
      .expectDicomComplete()
  }

  "The conditional reverse anonymization flow" should "not perform reverse anonymization when stream is empty" in {
    val source = Source.empty
      .via(detourFlow({ case p: ElementsPart => isAnonymous(p.elements) }, reverseAnonFlow))

    source.runWith(TestSink.probe[DicomPart])
      .expectDicomComplete()
  }

  it should "not perform reverse anonymization when elements part is missing in stream" in {
    val elements = createElements()

    val source = anonSource(elements)
      .via(detourFlow({ case p: ElementsPart => isAnonymous(p.elements) }, reverseAnonFlow))
      .via(ElementFlows.elementFlow)
      .mapConcat {
        case e: ValueElement if e.tag == Tag.PatientName => e :: Nil
        case _ => Nil
      }
      .take(1)

    val element = Await.result(source.runWith(Sink.head), 10.seconds)

    element.value.toSingleString(VR.PN) should not be elements.getString(Tag.PatientName).get
  }

  it should "perform reverse anonymization when elements part is present in stream" in {
    val elements = createElements()

    val source = Source.single(anonKeyResultPart(elements, DicomHierarchyLevel.IMAGE))
      .concat(anonSource(elements))
      .via(reverseAnonFlow)
      .via(ElementFlows.elementFlow)

    val processedElements = Await.result(source.runWith(ElementSink.elementSink), 10.seconds)

    processedElements.getString(Tag.PatientName).get shouldBe elements.getString(Tag.PatientName).get
  }

  it should "only reverse attributes in the root dataset" in {
    val elements = createElements()
      .set(Sequence(Tag.DerivationCodeSequence, -1, List(Item.fromElements(Elements.empty().setString(Tag.PatientName, "pat name")))))

    val source = Source.single(anonKeyResultPart(elements, DicomHierarchyLevel.IMAGE))
      .concat(anonSource(elements))
      .via(ModifyFlow.modifyFlow(modifications = Seq(
        TagModification.equals(TagPath.fromItem(Tag.DerivationCodeSequence, 1).thenTag(Tag.PatientName), _ => toAsciiBytes("anon patient name", VR.PN))
      )))
      .via(reverseAnonFlow)
      .via(ElementFlows.elementFlow)

    val processedElements = Await.result(source.runWith(ElementSink.elementSink), 10.seconds)

    processedElements.getString(Tag.PatientName).get shouldBe elements.getString(Tag.PatientName).get
    processedElements.getNested(Tag.DerivationCodeSequence, 1).get.getString(Tag.PatientName).get shouldBe "anon patient name"
  }
}
