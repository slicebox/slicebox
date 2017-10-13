package se.nimsa.sbx.dicom.streams

import akka.NotUsed
import akka.actor.ActorSystem
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.Source
import akka.testkit.TestKit
import akka.util.ByteString
import org.dcm4che3.data.{Attributes, Tag}
import org.scalatest.{AsyncFlatSpecLike, BeforeAndAfterAll, Matchers}
import se.nimsa.dcm4che.streams.DicomParseFlow.parseFlow
import se.nimsa.dcm4che.streams.DicomParts.DicomPart
import se.nimsa.dcm4che.streams.{DicomAttributesSink, DicomFlows, DicomParsing}
import se.nimsa.sbx.anonymization.AnonymizationProtocol.AnonymizationKey
import se.nimsa.sbx.dicom.DicomData
import se.nimsa.sbx.util.TestUtil

import scala.concurrent.{ExecutionContextExecutor, Future}

class HarmonizeAnonymizationFlowTest extends TestKit(ActorSystem("ReverseAnonymizationFlowSpec")) with AsyncFlatSpecLike with Matchers with BeforeAndAfterAll {

  import DicomTestData._

  implicit val materializer: ActorMaterializer = ActorMaterializer()
  implicit val ec: ExecutionContextExecutor = system.dispatcher

  override def afterAll: Unit = TestKit.shutdownActorSystem(system)

  def attributesSource(dicomData: DicomData): Source[DicomPart, NotUsed] = {
    val bytes = ByteString(TestUtil.toByteArray(dicomData))
    Source.single(bytes)
      .via(parseFlow)
      .via(DicomFlows.tagFilter(_ => false)(tagPath => !DicomParsing.isFileMetaInformation(tagPath.tag)))
  }

  def anonKeyPart(key: AnonymizationKey) = AnonymizationKeysPart(Seq(key), Some(key), Some(key), Some(key))

  def harmonize(key: AnonymizationKey, attributes: Attributes): Future[(Option[Attributes], Option[Attributes])] =
    Source.single(anonKeyPart(key))
      .concat(attributesSource(DicomData(attributes, metaInformation)))
      .via(HarmonizeAnonymizationFlow.harmonizeAnonFlow)
      .via(DicomFlows.attributeFlow)
      .runWith(DicomAttributesSink.attributesSink)

  "The harmonize anonymization flow" should "not change attributes if anonymous info in key is equal to that in dataset" in {
    val attributes = createAttributes
    val key = TestUtil.createAnonymizationKey(attributes)
    harmonize(key, attributes).map {
      case (_, dsMaybe) =>
        val harmonizedAttributes = dsMaybe.get
        harmonizedAttributes.getString(Tag.PatientName) shouldBe key.anonPatientName
        harmonizedAttributes.getString(Tag.PatientID) shouldBe key.anonPatientID
        harmonizedAttributes.getString(Tag.StudyInstanceUID) shouldBe key.anonStudyInstanceUID
        harmonizedAttributes.getString(Tag.SeriesInstanceUID) shouldBe key.anonSeriesInstanceUID
    }
  }

  it should "change change patient ID when attribute in key is different from that in dataset" in {
    val attributes = createAttributes
    val key = TestUtil.createAnonymizationKey(attributes).copy(anonPatientID = "apid2")
    harmonize(key, attributes).map {
      case (_, dsMaybe) =>
        val harmonizedAttributes = dsMaybe.get
        harmonizedAttributes.getString(Tag.PatientID) shouldBe "apid2"
    }
  }

  it should "change patient and study properties" in {
    val attributes = createAttributes
    val key = TestUtil.createAnonymizationKey(attributes).copy(anonPatientID = "apid2", anonStudyInstanceUID = "astuid2")
    harmonize(key, attributes).map {
      case (_, dsMaybe) =>
        val harmonizedAttributes = dsMaybe.get
        harmonizedAttributes.getString(Tag.PatientID) shouldBe "apid2"
        harmonizedAttributes.getString(Tag.StudyInstanceUID) shouldBe "astuid2"
    }
  }

}
