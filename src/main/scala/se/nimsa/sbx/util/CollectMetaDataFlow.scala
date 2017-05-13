package se.nimsa.sbx.util

import akka.stream.scaladsl.{Flow, Source}
import akka.util.ByteString
import org.dcm4che3.data.{Attributes, Tag, VR}
import org.dcm4che3.io.DicomStreamException
import se.nimsa.dcm4che.streams.DicomParts._
import se.nimsa.sbx.anonymization.AnonymizationProtocol.AnonymizationKey

import scala.collection.mutable

object CollectMetaDataFlow {

  private case object DicomEndMarker extends DicomPart {
    def bigEndian = false
    def bytes = ByteString.empty
  }

  case class DicomMetaPart(transferSyntaxUid: Option[String],
                           patientId: Option[String],
                           patientName: Option[String],
                           identityRemoved: Option[String],
                           studyInstanceUID: Option[String] = None,
                           seriesInstanceUID: Option[String] = None,
                           anonKeys: Option[AnonymizationKey] = None) extends DicomPart {
    def bytes = ByteString.empty
    def bigEndian = false
    def isAnonymized = identityRemoved.exists(_.toUpperCase == "YES")
  }

  /**
    * A flow which buffers DICOM parts until PatientName, PatientId and PatientIdentityRemoved are known.
    * Pushes a DicomMetaPart first, and all buffered parts afterwards. Following parts a pushed downstream immediately.
    */
  val collectMetaDataFlow = Flow[DicomPart]
    .concat(Source.single(DicomEndMarker))
    .statefulMapConcat {
      val maxBufferSize = 1000000
      val metaTags = Set(Tag.TransferSyntaxUID, Tag.SpecificCharacterSet, Tag.PatientName, Tag.PatientID, Tag.PatientIdentityRemoved, Tag.StudyInstanceUID, Tag.SeriesInstanceUID)
      val stopTag = metaTags.max

      () =>
        var reachedEnd = false
        var currentBufferSize = 0
        var currentTag: Option[Int] = None
        var buffer: List[DicomPart] = Nil
        val metaAttr = mutable.Map.empty[Int, DicomAttribute]

        def metaDataAndBuffer() = {
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
            Option(attr.getString(Tag.PatientID, "")),
            Option(attr.getString(Tag.PatientName, "")),
            Option(attr.getString(Tag.PatientIdentityRemoved, "NO")),
            Option(attr.getString(Tag.StudyInstanceUID)),
            Option(attr.getString(Tag.SeriesInstanceUID)))

          val parts = metaPart :: buffer

          reachedEnd = true
          buffer = Nil
          currentBufferSize = 0

          parts
        }

      {
        case DicomEndMarker if reachedEnd =>
          Nil

        case part if reachedEnd =>
          part :: Nil

        case part =>
          currentBufferSize = currentBufferSize + part.bytes.size
          if (currentBufferSize > maxBufferSize) {
            throw new DicomStreamException("Error collecting meta data for reverse anonymization: max buffer size exceeded")
          }

          if (part != DicomEndMarker)
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

                case Some(tag) =>
                  metaAttr.get(tag).foreach(attribute => metaAttr.update(tag, attribute.copy(valueChunks = attribute.valueChunks :+ valueChunk)))
                  if (valueChunk.last) {
                    currentTag = None
                    if (tag >= stopTag) {
                      metaDataAndBuffer()
                    } else
                      Nil
                  } else
                    Nil

                case _ => Nil
              }

            case DicomEndMarker =>
              metaDataAndBuffer()

            case _ => Nil
          }
      }
    }

  val stripMetaDataFlow = Flow[DicomPart].filterNot(_.isInstanceOf[DicomMetaPart])
}


