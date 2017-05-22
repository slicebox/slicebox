package se.nimsa.sbx.dicom.streams

import akka.stream.scaladsl.Flow
import akka.stream.stage.{GraphStage, GraphStageLogic, InHandler, OutHandler}
import akka.stream.{Attributes, FlowShape, Inlet, Outlet}
import akka.util.ByteString
import org.dcm4che3.data.{SpecificCharacterSet, Tag}
import org.dcm4che3.io.DicomStreamException
import se.nimsa.dcm4che.streams.DicomParts._
import se.nimsa.sbx.anonymization.AnonymizationProtocol.AnonymizationKey


/**
  * A flow which buffers DICOM parts until PatientName, PatientId and PatientIdentityRemoved are known.
  * Pushes a DicomMetaPart first, and all buffered parts afterwards. Following parts a pushed downstream immediately.
  *
  */
class CollectMetaDataFlow() extends GraphStage[FlowShape[DicomPart, DicomPart]] {

  val MAX_BUFFER_SIZE = 1000000 // 1 MB
  val ASCII ="US-ASCII"

  val in = Inlet[DicomPart]("DicomAttributeBufferFlow.in")
  val out = Outlet[DicomPart]("DicomAttributeBufferFlow.out")
  override val shape = FlowShape.of(in, out)



  override def createLogic(inheritedAttributes: Attributes): GraphStageLogic = new GraphStageLogic(shape) {
    var buffer: Seq[DicomPart] = Nil
    var reachedEnd = false
    var currentBufferSize = 0


    var transferSyntaxUid: Option[DicomAttribute] = None
    var specificCharacterSet: Option[DicomAttribute] = None
    var patientName: Option[DicomAttribute] = None
    var patientID: Option[DicomAttribute] = None
    var patientIdentityRemoved: Option[DicomAttribute] = None
    var studyInstanceUID: Option[DicomAttribute] = None
    var seriesInstanceUID: Option[DicomAttribute] = None
    var currentMeta: Option[String] = None

    setHandlers(in, out, new InHandler with OutHandler {

      override def onPull(): Unit = {
        pull(in)
      }

      override def onPush(): Unit = {

        val part = grab(in)

        if (reachedEnd) {
          push(out,part)
        } else {

          currentBufferSize = currentBufferSize + part.bytes.size
          if (currentBufferSize > MAX_BUFFER_SIZE) {
            reachedEnd = true
            failStage(new DicomStreamException("Error collecting meta data for reverse anonymization: max buffer size exceeded"))
          }

          buffer = buffer :+ part

          part match {

            case header: DicomHeader if header.tag == Tag.TransferSyntaxUID =>
              transferSyntaxUid = Some(DicomAttribute(header, Seq.empty))
              currentMeta = Some("transferSyntaxUid")

            case header: DicomHeader if header.tag == Tag.SpecificCharacterSet =>
              specificCharacterSet = Some(DicomAttribute(header, Seq.empty))
              currentMeta = Some("specificCharacterSet")

            case header: DicomHeader if header.tag == Tag.PatientName =>
              patientName = Some(DicomAttribute(header, Seq.empty))
              currentMeta = Some("patientName")

            case header: DicomHeader if header.tag == Tag.PatientID =>
              patientID = Some(DicomAttribute(header, Seq.empty))
              currentMeta = Some("patientID")

            case header: DicomHeader if header.tag == Tag.PatientIdentityRemoved =>
              patientIdentityRemoved = Some(DicomAttribute(header, Seq.empty))
              currentMeta = Some("patientIdentityRemoved")

            case header: DicomHeader if header.tag == Tag.StudyInstanceUID =>
              studyInstanceUID = Some(DicomAttribute(header, Seq.empty))
              currentMeta = Some("studyInstanceUID")

            case header: DicomHeader if header.tag == Tag.SeriesInstanceUID =>
              seriesInstanceUID = Some(DicomAttribute(header, Seq.empty))
              currentMeta = Some("seriesInstanceUID")

            case header: DicomHeader =>
              currentMeta = None

            case valueChunk: DicomValueChunk =>

              currentMeta match {
                case Some("specificCharacterSet") =>
                  specificCharacterSet = specificCharacterSet.map(attribute => attribute.copy(valueChunks = attribute.valueChunks :+ valueChunk))
                  if (valueChunk.last) {
                    currentMeta = None
                  }

                case Some("transferSyntaxUid") =>
                  transferSyntaxUid = transferSyntaxUid.map(attribute => attribute.copy(valueChunks = attribute.valueChunks :+ valueChunk))
                  if (valueChunk.last) {
                    currentMeta = None
                  }

                case Some("patientName") =>
                  patientName = patientName.map(attribute => attribute.copy(valueChunks = attribute.valueChunks :+ valueChunk))
                  if (valueChunk.last) {
                    currentMeta = None
                  }

                case Some("patientID") =>
                  patientID = patientID.map(attribute => attribute.copy(valueChunks = attribute.valueChunks :+ valueChunk))
                  if (valueChunk.last) {
                    currentMeta = None
                  }

                case Some("patientIdentityRemoved") =>
                  patientIdentityRemoved = patientIdentityRemoved.map(attribute => attribute.copy(valueChunks = attribute.valueChunks :+ valueChunk))
                  if (valueChunk.last) {
                    currentMeta = None
                    val isAnon = patientIdentityRemoved.get.valueBytes.decodeString(ASCII).trim.toUpperCase == "YES"
                    if (!isAnon) {
                      reachedEnd = true
                      pushMetaAndBuffered()
                    }
                  }

                case Some("studyInstanceUID") =>
                  studyInstanceUID = studyInstanceUID.map(attribute => attribute.copy(valueChunks = attribute.valueChunks :+ valueChunk))
                  if (valueChunk.last) {
                    currentMeta = None
                  }

                case Some("seriesInstanceUID") =>
                  seriesInstanceUID = seriesInstanceUID.map(attribute => attribute.copy(valueChunks = attribute.valueChunks :+ valueChunk))
                  if (valueChunk.last) {
                    currentMeta = None
                    reachedEnd = true
                    pushMetaAndBuffered()
                  }

                case _ => // just do nothing
              }

            case _: DicomPart => // just do nothing
          }

          if (!reachedEnd) {
            pull(in)
          }
        }
      }

      /**
        * Push the DicomMetaPart as first element of the stream,
        * and all other parts that have been buffered so far.
        */
      def pushMetaAndBuffered() = {

        val cs = if (specificCharacterSet.isDefined) {
          val codes = specificCharacterSet.get.valueBytes.decodeString(ASCII).trim.split("\\\\")
          SpecificCharacterSet.valueOf(codes:_*)
        } else {
          SpecificCharacterSet.ASCII
        }

        // PN, LO: use specific character set to decode
        val name = patientName.map(value => cs.decode(value.valueBytes.toArray).trim)
        val id = patientID.map(value => cs.decode(value.valueBytes.toArray).trim)

        // UI, CS: use ASCII
        val tsuid = transferSyntaxUid.map(_.valueBytes.decodeString(ASCII).trim)
        val isAnon = patientIdentityRemoved.map(_.valueBytes.decodeString(ASCII).trim)
        val studyUID = studyInstanceUID.map(_.valueBytes.decodeString(ASCII).trim)
        val seriesUID = seriesInstanceUID.map(_.valueBytes.decodeString(ASCII).trim)

        val metaPart = new DicomMetaPart(tsuid, Some(cs), id, name, isAnon, studyUID, seriesUID)


        emitMultiple(out, (metaPart +: buffer).iterator)
        buffer = Nil
      }

      override def onUpstreamFinish(): Unit = {
        if (!reachedEnd) {
          buffer = DicomMetaPart(None, None, None, None, None, None, None, None) +: buffer
        }
        if (!buffer.isEmpty) emitMultiple(out, buffer.iterator)
        complete(out)
      }

    })
  }
}


object CollectMetaDataFlow {
  val collectMetaDataFlow = Flow[DicomPart].via(new CollectMetaDataFlow())

  val stripMetaDataFlow = Flow[DicomPart].filterNot(_.isInstanceOf[DicomMetaPart])

}

  case class DicomMetaPart(transferSyntaxUid: Option[String],
                           specificCharacterSet: Option[SpecificCharacterSet],
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




