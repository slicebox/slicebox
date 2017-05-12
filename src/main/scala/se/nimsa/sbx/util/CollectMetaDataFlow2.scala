package se.nimsa.sbx.util

import akka.stream.scaladsl.Flow
import akka.util.ByteString
import org.dcm4che3.data.Tag
import org.dcm4che3.io.DicomStreamException
import se.nimsa.dcm4che.streams.DicomParts._
import se.nimsa.sbx.anonymization.AnonymizationProtocol.AnonymizationKey
import scala.collection.mutable

object CollectMetaDataFlow2 {

  case class DicomMetaPart(transferSyntaxUid: Option[String], patientId: String, patientName: String, identityRemoved: String, studyInstanceUID: Option[String] = None, seriesInstanceUID: Option[String] = None, anonKeys: Option[AnonymizationKey] = None) extends DicomPart {
    def bytes = ByteString.empty
    def bigEndian = false
    def isAnonymized = identityRemoved.toUpperCase == "YES"
  }

  /**
    * A flow which buffers DICOM parts until PatientName, PatientId and PatientIdentityRemoved are known.
    * Pushes a DicomMetaPart first, and all buffered parts afterwards. Following parts a pushed downstream immediately.
    *
    * FIXME: Need to handle specific charactersets. default: ISO-IR-6
    *
    */
  val collectMetaDataFlow = Flow[DicomPart].statefulMapConcat {
    () =>
      var reachedEnd = false
      var currentBufferSize = 0
      var currentTag: Option[Int] = None

      val maxBufferSize = 1000000
      var buffer: List[DicomPart] = Nil

      val metaTags = Set(Tag.TransferSyntaxUID, Tag.PatientName, Tag.PatientID, Tag.PatientIdentityRemoved, Tag.StudyInstanceUID, Tag.SeriesInstanceUID)
      val stopTag = Tag.SeriesInstanceUID
      val metaAttr = mutable.Map.empty[Int, DicomAttribute]

      // push a DicomMetaPart as first element of the stream, then all other parts that have been buffered so far.
      def pushMetaAndBuffered() = {
        // FIXME: handle specific character set!
        val tsuid = metaAttr.get(Tag.TransferSyntaxUID).map(_.bytes.decodeString("US-ASCII").trim)
        val name = metaAttr.get(Tag.PatientName).map(_.bytes.decodeString("US-ASCII").trim).getOrElse("")
        val id = metaAttr.get(Tag.PatientID).map(_.bytes.decodeString("US-ASCII").trim).getOrElse("")
        val isAnon = metaAttr.get(Tag.PatientIdentityRemoved).map(_.bytes.decodeString("US-ASCII").trim).getOrElse("NO")
        val studyUID = metaAttr.get(Tag.StudyInstanceUID).map(_.bytes.decodeString("US-ASCII").trim)
        val seriesUID = metaAttr.get(Tag.SeriesInstanceUID).map(_.bytes.decodeString("US-ASCII").trim)

        val parts = DicomMetaPart(tsuid, id, name, isAnon, studyUID, seriesUID) :: buffer

        reachedEnd = true
        buffer = Nil
        currentBufferSize = 0

        parts
      }

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
                  if (tag == stopTag)
                    pushMetaAndBuffered()
                  else
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


