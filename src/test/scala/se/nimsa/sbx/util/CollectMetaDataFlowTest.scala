package se.nimsa.sbx.util

import java.io.ByteArrayOutputStream

import akka.actor.ActorSystem
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.Source
import akka.stream.testkit.scaladsl.TestSink
import akka.testkit.TestKit
import akka.util.ByteString
import org.dcm4che3.data.{Attributes, Tag, UID, VR}
import org.dcm4che3.io.DicomOutputStream
import org.scalatest.{FlatSpecLike, Matchers}
import se.nimsa.dcm4che.streams.DicomPartFlow
import se.nimsa.dcm4che.streams.DicomParts.DicomPart


class CollectMetaDataFlowTest extends TestKit(ActorSystem("CollectMetaDataFlowSpec")) with FlatSpecLike with Matchers {

  import CollectMetaDataFlow._
  import DicomPartFlow.partFlow
  import TestUtil._

  implicit val materializer = ActorMaterializer()
  implicit val ec = system.dispatcher

  "A collect metadata flow" should "first produce a meta data object followed by the input dicom parts" in {
    val metaPart = DicomMetaPart(Some(UID.ExplicitVRLittleEndian), Some("John^Doe"), Some("12345678"), Some("NO"), Some("1.2.3.4"), Some("5.6.7.8"))
    val attr = new Attributes()
    attr.setString(Tag.PatientName, VR.PN, metaPart.patientName.get)
    attr.setString(Tag.PatientID, VR.LO, metaPart.patientId.get)
    attr.setString(Tag.PatientIdentityRemoved, VR.CS, metaPart.identityRemoved.get)
    attr.setString(Tag.StudyInstanceUID, VR.UI, metaPart.studyInstanceUID.get)
    attr.setString(Tag.SeriesInstanceUID, VR.UI, metaPart.seriesInstanceUID.get)
    val bytes = toByteString(attr)

    val source = Source.single(bytes)
      .via(partFlow)
      .via(collectMetaDataFlow)

    source.runWith(TestSink.probe[DicomPart])
      .expectMetaPart(metaPart)
      .expectHeader(Tag.PatientName)
      .expectValueChunk(ByteString(metaPart.patientName.get.getBytes))
      .expectHeader(Tag.PatientID)
      .expectValueChunk(ByteString(metaPart.patientId.get.getBytes))
      .expectHeader(Tag.PatientIdentityRemoved)
      .expectValueChunk(ByteString(metaPart.identityRemoved.get.getBytes))
      .expectHeader(Tag.StudyInstanceUID)
      .expectValueChunk(ByteString(metaPart.studyInstanceUID.get.getBytes))
      .expectHeader(Tag.SeriesInstanceUID)
      .expectValueChunk(ByteString(metaPart.seriesInstanceUID.get.getBytes))
      .expectDicomComplete()
  }

  it should "produce an empty meta data when stream is empty" in {
    val bytes = ByteString.empty

    val source = Source.single(bytes)
      .via(partFlow)
      .via(collectMetaDataFlow)

    source.runWith(TestSink.probe[DicomPart])
      .expectMetaPart(DicomMetaPart(None, None, None, None, None, None, None))
      .expectDicomComplete()
  }

  it should "produce an empty meta data when no relevant attributes are present" in {
    val attr = new Attributes()
    attr.setString(Tag.ReferringPhysicianName, VR.PN, "Doc")
    attr.setString(Tag.NumberOfFrames, VR.DS, "33")
    val bytes = toByteString(attr)

    val source = Source.single(bytes)
      .via(partFlow)
      .via(collectMetaDataFlow)

    source.runWith(TestSink.probe[DicomPart])
      .expectMetaPart(DicomMetaPart(None, None, None, None, None, None, None))
      .expectHeader(Tag.ReferringPhysicianName)
      .expectValueChunk()
      .expectHeader(Tag.NumberOfFrames)
      .expectValueChunk()
      .expectDicomComplete()
  }

  private def toByteString(attributes: Attributes) = {
    val baos = new ByteArrayOutputStream()
    val dos = new DicomOutputStream(baos, UID.ExplicitVRLittleEndian)
    dos.writeDataset(null, attributes)
    dos.close()
    ByteString(baos.toByteArray)
  }

}

