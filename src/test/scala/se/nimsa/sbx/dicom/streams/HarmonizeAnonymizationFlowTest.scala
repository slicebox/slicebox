package se.nimsa.sbx.dicom.streams

import akka.NotUsed
import akka.actor.ActorSystem
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.Source
import akka.testkit.TestKit
import org.scalatest.{AsyncFlatSpecLike, BeforeAndAfterAll, Matchers}
import se.nimsa.dicom.data.DicomParts.DicomPart
import se.nimsa.dicom.data.{DicomParsing, Elements, Tag}
import se.nimsa.dicom.streams.DicomFlows
import se.nimsa.dicom.streams.ElementFlows.elementFlow
import se.nimsa.dicom.streams.ElementSink.elementSink
import se.nimsa.sbx.anonymization.AnonymizationProtocol.AnonymizationKey
import se.nimsa.sbx.dicom.streams.DicomStreamUtil._
import se.nimsa.sbx.storage.{RuntimeStorage, StorageService}
import se.nimsa.sbx.util.TestUtil

import scala.concurrent.{ExecutionContextExecutor, Future}

class HarmonizeAnonymizationFlowTest extends TestKit(ActorSystem("ReverseAnonymizationFlowSpec")) with AsyncFlatSpecLike with Matchers with BeforeAndAfterAll {

  import DicomTestData._

  implicit val materializer: ActorMaterializer = ActorMaterializer()
  implicit val ec: ExecutionContextExecutor = system.dispatcher

  override def afterAll: Unit = TestKit.shutdownActorSystem(system)

  val storage: StorageService = new RuntimeStorage

  def elementsSource(elements: Elements): Source[DicomPart, NotUsed] = {
    val bytes = TestUtil.toBytes(elements)
    Source.single(bytes)
      .via(storage.parseFlow(None))
      .via(DicomFlows.tagFilter(_ => false)(tagPath => !DicomParsing.isFileMetaInformation(tagPath.tag)))
  }

  def anonKeyPart(key: AnonymizationKey) = PartialAnonymizationKeyPart(Some(key), hasPatientInfo = true, hasStudyInfo = true, hasSeriesInfo = true, hasFrameOfReferenceInfo = true)

  def harmonize(key: AnonymizationKey, elements: Elements): Future[Elements] =
    Source.single(anonKeyPart(key))
      .concat(elementsSource(elements))
      .via(HarmonizeAnonymizationFlow.harmonizeAnonFlow)
      .via(elementFlow)
      .runWith(elementSink)

  "The harmonize anonymization flow" should "not change attributes if anonymous info in key is equal to that in dataset" in {
    val elements = testElements
    val key = TestUtil.createAnonymizationKey(elements)
    harmonize(key, elements).map { harmonizedAttributes =>
        harmonizedAttributes.getString(Tag.PatientName).get shouldBe key.anonPatientName
        harmonizedAttributes.getString(Tag.PatientID).get shouldBe key.anonPatientID
        harmonizedAttributes.getString(Tag.StudyInstanceUID).get shouldBe key.anonStudyInstanceUID
        harmonizedAttributes.getString(Tag.SeriesInstanceUID).get shouldBe key.anonSeriesInstanceUID
    }
  }

  it should "change change patient ID when attribute in key is different from that in dataset" in {
    val elements = testElements
    val key = TestUtil.createAnonymizationKey(elements).copy(anonPatientID = "apid2")
    harmonize(key, elements).map { harmonizedAttributes =>
        harmonizedAttributes.getString(Tag.PatientID).get shouldBe "apid2"
    }
  }

  it should "change patient and study properties" in {
    val attributes = testElements
    val key = TestUtil.createAnonymizationKey(attributes).copy(anonPatientID = "apid2", anonStudyInstanceUID = "astuid2")
    harmonize(key, attributes).map { harmonizedAttributes =>
        harmonizedAttributes.getString(Tag.PatientID).get shouldBe "apid2"
        harmonizedAttributes.getString(Tag.StudyInstanceUID).get shouldBe "astuid2"
    }
  }

}
