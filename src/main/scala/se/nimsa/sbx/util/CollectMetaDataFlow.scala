package se.nimsa.sbx.util

import akka.stream.scaladsl.Flow
import akka.util.ByteString
import org.dcm4che3.data.{Attributes, Tag, VR}
import org.dcm4che3.io.DicomStreamException
import se.nimsa.dcm4che.streams.DicomParts._
import se.nimsa.sbx.anonymization.AnonymizationProtocol.AnonymizationKey

import scala.collection.mutable

object CollectMetaDataFlow {

  case class DicomMetaPart(transferSyntaxUid: Option[String], patientId: String, patientName: String, identityRemoved: String, studyInstanceUID: Option[String] = None, seriesInstanceUID: Option[String] = None, anonKeys: Option[AnonymizationKey] = None) extends DicomPart {
    def bytes = ByteString.empty
    def bigEndian = false
    def isAnonymized = identityRemoved.toUpperCase == "YES"
  }

  /**
    * A flow which buffers DICOM parts until PatientName, PatientId and PatientIdentityRemoved are known.
    * Pushes a DicomMetaPart first, and all buffered parts afterwards. Following parts a pushed downstream immediately.
    */
  val collectMetaDataFlow = Flow[DicomPart].statefulMapConcat {
    val maxBufferSize = 1000000
    val metaTags = Set(Tag.TransferSyntaxUID, Tag.SpecificCharacterSet, Tag.PatientName, Tag.PatientID, Tag.PatientIdentityRemoved, Tag.StudyInstanceUID, Tag.SeriesInstanceUID)
    val stopTag = Tag.SeriesInstanceUID

    () =>
      var reachedEnd = false
      var currentBufferSize = 0
      var currentTag: Option[Int] = None
      var buffer: List[DicomPart] = Nil
      val metaAttr = mutable.Map.empty[Int, DicomAttribute]

    {
      case part if reachedEnd =>
        part :: Nil

      case part =>
        currentBufferSize = currentBufferSize + part.bytes.size
        if (currentBufferSize > maxBufferSize) {
          throw new DicomStreamException("Error collecting meta data for reverse anonymization: max buffer size exceeded")
        }

        buffer = buffer :+ part

        part match {
          case header: DicomHeader if metaTags.contains(header.tag) =>
            metaAttr(header.tag) = DicomAttribute(header, Seq.empty)
            currentTag = Some(header.tag)
            Nil

          case _: DicomHeader =>
            currentTag = None
            Nil

          case valueChunk: DicomValueChunk =>

            currentTag match {

              case Some(tag) if metaAttr.contains(tag) =>
                metaAttr.get(tag).foreach(attribute => metaAttr.update(tag, attribute.copy(valueChunks = attribute.valueChunks :+ valueChunk)))
                if (valueChunk.last) {
                  currentTag = None
                  if (tag == stopTag) {
                    val attr = new Attributes()
                    metaAttr.get(Tag.TransferSyntaxUID).foreach(attribute => attr.setBytes(Tag.TransferSyntaxUID, VR.UI, attribute.bytes.toArray))
                    metaAttr.get(Tag.SpecificCharacterSet).foreach(attribute => attr.setBytes(Tag.SpecificCharacterSet, VR.CS, attribute.bytes.toArray))
                    metaAttr.get(Tag.PatientName).foreach(attribute => attr.setBytes(Tag.PatientName, VR.PN, attribute.bytes.toArray))
                    metaAttr.get(Tag.PatientID).foreach(attribute => attr.setBytes(Tag.PatientID, VR.LO, attribute.bytes.toArray))
                    metaAttr.get(Tag.PatientIdentityRemoved).foreach(attribute => attr.setBytes(Tag.PatientIdentityRemoved, VR.CS, attribute.bytes.toArray))
                    metaAttr.get(Tag.StudyInstanceUID).foreach(attribute => attr.setBytes(Tag.StudyInstanceUID, VR.UI, attribute.bytes.toArray))
                    metaAttr.get(Tag.SeriesInstanceUID).foreach(attribute => attr.setBytes(Tag.SeriesInstanceUID, VR.UI, attribute.bytes.toArray))

                    val metaPart = DicomMetaPart(
                      Option(attr.getString(Tag.TransferSyntaxUID)),
                      attr.getString(Tag.PatientID, ""),
                      attr.getString(Tag.PatientName, ""),
                      attr.getString(Tag.PatientIdentityRemoved, "NO"),
                      Option(attr.getString(Tag.StudyInstanceUID)),
                      Option(attr.getString(Tag.SeriesInstanceUID)))

                    val parts = metaPart :: buffer

                    reachedEnd = true
                    buffer = Nil
                    currentBufferSize = 0

                    parts
                  } else
                    Nil
                } else
                  Nil

              case _ => Nil
            }

          case _ => Nil
        }
    }
  }

  val stripMetaDataFlow = Flow[DicomPart].filterNot(_.isInstanceOf[DicomMetaPart])
}


