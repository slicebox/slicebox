package se.nimsa.sbx.dicom.streams

import akka.NotUsed
import akka.actor.ActorSystem
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.{Sink, Source}
import akka.stream.testkit.scaladsl.TestSink
import akka.testkit.TestKit
import akka.util.ByteString
import org.scalatest.{BeforeAndAfterAll, FlatSpecLike, Matchers}
import se.nimsa.dicom.DicomParts.DicomPart
import se.nimsa.dicom._
import se.nimsa.dicom.streams.ModifyFlow.TagModification
import se.nimsa.dicom.streams.{DicomFlows, ElementFolds, ModifyFlow}
import se.nimsa.sbx.dicom.streams.DicomStreamUtil._
import se.nimsa.sbx.storage.{RuntimeStorage, StorageService}
import se.nimsa.sbx.util.TestUtil._

import scala.concurrent.duration.DurationInt
import scala.concurrent.{Await, ExecutionContextExecutor}

class ReverseAnonymizationFlowTest extends TestKit(ActorSystem("ReverseAnonymizationFlowSpec")) with FlatSpecLike with Matchers with BeforeAndAfterAll {

  import DicomTestData._

  implicit val materializer: ActorMaterializer = ActorMaterializer()
  implicit val ec: ExecutionContextExecutor = system.dispatcher

  override def afterAll: Unit = TestKit.shutdownActorSystem(system)

  val storage: StorageService = new RuntimeStorage

  def elementsSource(elements: Elements): Source[DicomPart, NotUsed] =
    Source.single(elements.bytes)
      .via(storage.parseFlow(None))
      .via(DicomFlows.tagFilter(_ => false)(tagPath => !DicomParsing.isFileMetaInformation(tagPath.tag)))

  def anonKeyPart(elements: Elements): PartialAnonymizationKeyPart = {
    val key = createAnonymizationKey(elements)
    PartialAnonymizationKeyPart(Some(key), hasPatientInfo = true, hasStudyInfo = true, hasSeriesInfo = true)
  }

  def anonSource(elements: Elements): Source[DicomPart, NotUsed] = {
    val key = anonKeyPart(elements).keyMaybe.get
    elementsSource(elements)
      .via(AnonymizationFlow.anonFlow)
      .via(ModifyFlow.modifyFlow(
        TagModification.contains(TagPath.fromTag(Tag.PatientName), _ => toAsciiBytes(key.anonPatientName, VR.PN), insert = false),
        TagModification.contains(TagPath.fromTag(Tag.PatientID), _ => toAsciiBytes(key.anonPatientID, VR.LO), insert = false),
        TagModification.contains(TagPath.fromTag(Tag.StudyInstanceUID), _ => toAsciiBytes(key.anonStudyInstanceUID, VR.UI), insert = false),
        TagModification.contains(TagPath.fromTag(Tag.SeriesInstanceUID), _ => toAsciiBytes(key.anonSeriesInstanceUID, VR.UI), insert = false),
        TagModification.contains(TagPath.fromTag(Tag.FrameOfReferenceUID), _ => toAsciiBytes(key.anonFrameOfReferenceUID, VR.UI), insert = false)
      ))
  }

  "The reverse anonymization flow" should "reverse anonymization for attributes stored in anonymization key" in {
    val elements = createElements()

    val source = Source.single(anonKeyPart(elements))
      .concat(anonSource(elements))
      .via(ReverseAnonymizationFlow.reverseAnonFlow)
      .via(ElementFolds.elementsFlow)

    val reversedElements = Await.result(source.runWith(ElementFolds.elementsSink), 10.seconds)

    reversedElements(Tag.PatientName) shouldBe elements(Tag.PatientName)
    reversedElements(Tag.PatientID) shouldBe elements(Tag.PatientID)
    reversedElements(Tag.StudyInstanceUID) shouldBe elements(Tag.StudyInstanceUID)
    reversedElements(Tag.SeriesInstanceUID) shouldBe elements(Tag.SeriesInstanceUID)
    reversedElements(Tag.FrameOfReferenceUID) shouldBe elements(Tag.FrameOfReferenceUID)
    reversedElements(Tag.PatientBirthDate) shouldBe elements(Tag.PatientBirthDate)
    reversedElements(Tag.StudyDescription) shouldBe elements(Tag.StudyDescription)
    reversedElements(Tag.StudyID) shouldBe elements(Tag.StudyID)
    reversedElements(Tag.AccessionNumber) shouldBe elements(Tag.AccessionNumber)
    reversedElements(Tag.SeriesDescription) shouldBe elements(Tag.SeriesDescription)
    reversedElements(Tag.ProtocolName) shouldBe elements(Tag.ProtocolName)
  }

  it should "insert anonymization key attributes into dataset even if they originally were not present" in {
    val elements = Elements.empty
      .update(Tag.TransferSyntaxUID, Element.explicitLE(Tag.TransferSyntaxUID, VR.UI, ByteString(UID.ExplicitVRLittleEndian)))
      .update(Tag.Modality, Element.explicitLE(Tag.Modality, VR.CS, ByteString("NM")))
    val source = elementsSource(elements)
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
          Tag.SpecificCharacterSet, // inserted by utf8 flow
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
    val elements = createElements()

    val source = anonSource(elements)
      .via(ReverseAnonymizationFlow.reverseAnonFlow)
      .via(ElementFolds.elementsFlow)
      .filter(_.element.tag == Tag.PatientName)
      .take(1)

    val tpElement = Await.result(source.runWith(Sink.head), 10.seconds)

    tpElement.element should not be elements(Tag.PatientName)
  }

  it should "perform reverse anonymization when anonymization key is present in stream" in {
    val dicomData = createElements()

    val source = Source.single(anonKeyPart(dicomData))
      .concat(anonSource(dicomData))
      .via(ReverseAnonymizationFlow.reverseAnonFlow)
      .via(ElementFolds.elementsFlow)
      .filter(_.element.tag == Tag.PatientName)

    val tpElement = Await.result(source.runWith(Sink.head), 10.seconds)

   tpElement.element shouldBe dicomData(Tag.PatientName).get
  }

}
