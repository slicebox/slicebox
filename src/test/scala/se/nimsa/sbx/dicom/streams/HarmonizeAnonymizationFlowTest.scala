package se.nimsa.sbx.dicom.streams

import akka.NotUsed
import akka.actor.ActorSystem
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.Source
import akka.testkit.TestKit
import org.scalatest.{AsyncFlatSpecLike, BeforeAndAfterAll, Matchers}
import se.nimsa.dicom.data.DicomParts.DicomPart
import se.nimsa.dicom.data.Elements.{Item, Sequence}
import se.nimsa.dicom.data.{Elements, Tag, TagPath, isFileMetaInformation}
import se.nimsa.dicom.streams.DicomFlows
import se.nimsa.dicom.streams.ElementFlows.elementFlow
import se.nimsa.dicom.streams.ElementSink.elementSink
import se.nimsa.sbx.anonymization.AnonymizationProtocol.{AnonymizationKey, AnonymizationKeyOpResult, AnonymizationKeyValue, TagValue}
import se.nimsa.sbx.dicom.DicomHierarchy.DicomHierarchyLevel
import se.nimsa.sbx.dicom.streams.DicomStreamUtil._
import se.nimsa.sbx.storage.{RuntimeStorage, StorageService}
import se.nimsa.sbx.util.TestUtil

import scala.concurrent.{ExecutionContextExecutor, Future}

class HarmonizeAnonymizationFlowTest extends TestKit(ActorSystem("ReverseAnonymizationFlowSpec")) with AsyncFlatSpecLike with Matchers with BeforeAndAfterAll {

  import DicomTestData._
  import HarmonizeAnonymizationFlow.harmonizeAnonFlow

  implicit val materializer: ActorMaterializer = ActorMaterializer()
  implicit val ec: ExecutionContextExecutor = system.dispatcher

  override def afterAll: Unit = TestKit.shutdownActorSystem(system)

  val storage: StorageService = new RuntimeStorage

  def elementsSource(elements: Elements): Source[DicomPart, NotUsed] = {
    val bytes = TestUtil.toBytes(elements)
    Source.single(bytes)
      .via(storage.parseFlow(None))
      .via(DicomFlows.tagFilter(_ => false)(tagPath => !isFileMetaInformation(tagPath.tag)))
  }

  def anonKeyPart(key: AnonymizationKey) = AnonymizationKeyOpResultPart(AnonymizationKeyOpResult(DicomHierarchyLevel.IMAGE, Some(key), Seq(
    AnonymizationKeyValue(-1, key.id, TagPath.fromTag(Tag.PatientName), key.patientName, key.anonPatientName),
    AnonymizationKeyValue(-1, key.id, TagPath.fromTag(Tag.PatientID), key.patientID, key.anonPatientID),
    AnonymizationKeyValue(-1, key.id, TagPath.fromTag(Tag.StudyInstanceUID), key.studyInstanceUID, key.anonStudyInstanceUID),
    AnonymizationKeyValue(-1, key.id, TagPath.fromTag(Tag.SeriesInstanceUID), key.seriesInstanceUID, key.anonSeriesInstanceUID),
    AnonymizationKeyValue(-1, key.id, TagPath.fromTag(Tag.SOPInstanceUID), key.sopInstanceUID, key.anonSOPInstanceUID),
    AnonymizationKeyValue(-1, key.id, TagPath.fromItem(Tag.DerivationCodeSequence, 1).thenTag(Tag.PatientName), "name", "anon name")
  )))

  def harmonize(key: AnonymizationKey, elements: Elements, customTagValues: Seq[TagValue]): Future[Elements] =
    Source.single(anonKeyPart(key))
      .concat(elementsSource(elements))
      .via(harmonizeAnonFlow(customTagValues))
      .via(elementFlow)
      .runWith(elementSink)

  "The harmonize anonymization flow" should "not change attributes if anonymous info in key is equal to that in dataset" in {
    val elements = testElements
    val key = TestUtil.createAnonymizationKey(elements)
    harmonize(key, elements, Seq.empty).map { harmonizedAttributes =>
        harmonizedAttributes.getString(Tag.PatientName).get shouldBe key.anonPatientName
        harmonizedAttributes.getString(Tag.PatientID).get shouldBe key.anonPatientID
        harmonizedAttributes.getString(Tag.StudyInstanceUID).get shouldBe key.anonStudyInstanceUID
        harmonizedAttributes.getString(Tag.SeriesInstanceUID).get shouldBe key.anonSeriesInstanceUID
    }
  }

  it should "change change patient ID when attribute in key is different from that in dataset" in {
    val elements = testElements
    val key = TestUtil.createAnonymizationKey(elements).copy(anonPatientID = "apid2")
    harmonize(key, elements, Seq.empty).map { harmonizedAttributes =>
        harmonizedAttributes.getString(Tag.PatientID).get shouldBe "apid2"
    }
  }

  it should "change patient and study properties" in {
    val attributes = testElements
    val key = TestUtil.createAnonymizationKey(attributes).copy(anonPatientID = "apid2", anonStudyInstanceUID = "astuid2")
    harmonize(key, attributes, Seq.empty).map { harmonizedAttributes =>
        harmonizedAttributes.getString(Tag.PatientID).get shouldBe "apid2"
        harmonizedAttributes.getString(Tag.StudyInstanceUID).get shouldBe "astuid2"
    }
  }

  it should "only change attributes as specified by tag paths" in {
    val attributes = testElements
      .set(Sequence(Tag.DerivationCodeSequence, -1, List(Item.fromElements(Elements.empty().setString(Tag.PatientName, "Bob")))))
    val key = TestUtil.createAnonymizationKey(attributes).copy(anonPatientName = "anon name")
    harmonize(key, attributes, Seq.empty).map { harmonizedAttributes =>
      harmonizedAttributes.getString(Tag.PatientName).get shouldBe "anon name"
      harmonizedAttributes.getSequence(Tag.DerivationCodeSequence).get.item(1).get.elements.getString(Tag.PatientName).get shouldBe "Bob"
    }
  }

  it should "apply custom tag values" in {
    val elements = testElements
    val key = TestUtil.createAnonymizationKey(elements).copy(anonPatientID = "apid2")
    harmonize(key, elements, Seq(TagValue(TagPath.fromTag(Tag.PatientName), "anon 001"))).map { harmonizedAttributes =>
      harmonizedAttributes.getString(Tag.PatientName).get shouldBe "anon 001"
    }
  }

}
