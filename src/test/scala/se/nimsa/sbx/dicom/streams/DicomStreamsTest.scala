package se.nimsa.sbx.dicom.streams

import java.io.ByteArrayOutputStream

import akka.NotUsed
import akka.actor.ActorSystem
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.{Compression, Source}
import akka.testkit.TestKit
import akka.util.{ByteString, Timeout}
import org.dcm4che3.data.{Tag, UID}
import org.dcm4che3.io.DicomOutputStream
import org.scalatest.{AsyncFlatSpecLike, Matchers}
import se.nimsa.dcm4che.streams.{DicomAttributesSink, DicomFlows, DicomPartFlow}
import se.nimsa.sbx.dicom.DicomData
import se.nimsa.sbx.util.TestUtil

import scala.concurrent.Future
import scala.concurrent.duration.DurationInt

class DicomStreamsTest extends TestKit(ActorSystem("AnonymizationFlowSpec")) with AsyncFlatSpecLike with Matchers {

  implicit val materializer = ActorMaterializer()
  implicit val ec = system.dispatcher
  implicit val timeout = Timeout(30.seconds)

  def toSource(dicomData: DicomData): Source[ByteString, NotUsed] = {
    val baos = new ByteArrayOutputStream()
    val dos = new DicomOutputStream(baos, UID.ExplicitVRLittleEndian)
    dos.writeDataset(dicomData.metaInformation, dicomData.attributes)
    dos.close()
    Source.single(ByteString(baos.toByteArray))
  }

  "The DICOM data source with anonymization" should "anonymize the patient ID and harmonize it according to a anonymization key" in {
    val dicomData = TestUtil.createDicomData()
    val anonKey = TestUtil.createAnonymizationKey(dicomData.attributes)
    val source = toSource(dicomData)

    val anonSource = DicomStreams.anonymizedDicomDataSource(source, (_, _) => Future.successful(Seq(anonKey)), a => Future.successful(a), Seq.empty)

    anonSource.via(Compression.inflate()).via(DicomPartFlow.partFlow).via(DicomFlows.attributeFlow).runWith(DicomAttributesSink.attributesSink).map {
      case (fmiMaybe, dsMaybe) =>
        val ds = dsMaybe.get
        ds.getString(Tag.PatientID) shouldBe anonKey.anonPatientID
    }
  }
}
