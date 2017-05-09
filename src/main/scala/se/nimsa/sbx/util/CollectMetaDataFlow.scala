package se.nimsa.sbx.util

import akka.stream.scaladsl.Flow
import akka.stream.{Attributes, FlowShape, Inlet, Outlet}
import akka.stream.stage.{GraphStage, GraphStageLogic, InHandler, OutHandler}
import akka.util.ByteString
import se.nimsa.dcm4che.streams.DicomParts._
import org.dcm4che3.data.Tag
import org.dcm4che3.io.DicomStreamException
import se.nimsa.sbx.anonymization.AnonymizationProtocol.AnonymizationKeys

/**
  * A flow which buffers DICOM parts until PatientName, PatientId and PatientIdentityRemoved are known.
  * Pushes a DicomMetaPart first, and all buffered parts afterwards. Following parts a pushed downstream immediately.
  *
  * FIXME: Need to handle specific charactersets. default: ISO-IR-6
  *
  */
class CollectMetaDataFlow() extends GraphStage[FlowShape[DicomPart, DicomPart]] {
  val MAX_BUFFER_SIZE = 1000000 // 1 MB

  val in = Inlet[DicomPart]("DicomAttributeBufferFlow.in")
  val out = Outlet[DicomPart]("DicomAttributeBufferFlow.out")
  override val shape = FlowShape.of(in, out)

  override def createLogic(inheritedAttributes: Attributes): GraphStageLogic = new GraphStageLogic(shape) {
    var buffer: Seq[DicomPart] = Nil
    var reachedEnd = false
    var currentBufferSize = 0

    var patientName: Option[DicomAttribute] = None
    var patientID: Option[DicomAttribute] = None
    var patientIdentityRemoved: Option[DicomAttribute] = None
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

          part match {
            case header: DicomHeader if (header.tag == Tag.PatientName) =>
              patientName = Some(DicomAttribute(header, Seq.empty))
              currentMeta = Some("patientName")
              buffer = buffer :+ header

            case header: DicomHeader if (header.tag == Tag.PatientID) =>
              patientID = Some(DicomAttribute(header, Seq.empty))
              currentMeta = Some("patientID")
              buffer = buffer :+ header

            case header: DicomHeader if (header.tag == Tag.PatientIdentityRemoved) =>
              patientIdentityRemoved = Some(DicomAttribute(header, Seq.empty))
              currentMeta = Some("patientIdentityRemoved")
              buffer = buffer :+ header

            case header: DicomHeader =>
              currentMeta = None
              buffer = buffer :+ header

            case valueChunk: DicomValueChunk =>
              buffer = buffer :+ valueChunk

              currentMeta match {

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
                    reachedEnd = true
                    pushMetaAndBuffered()
                  }

                case _ => // just do nothing
              }

            case part: DicomPart =>
              buffer = buffer :+ part
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
        val name = patientName.get.bytes
        val id = patientID.get.bytes
        val isAnon = patientIdentityRemoved.get.bytes

        // FIXME: handle specific character set!
        val metaPart = new DicomMetaPart(name.decodeString("UTF-8").trim, id.decodeString("UTF-8").trim, isAnon.decodeString("UTF-8").trim)

        emitMultiple(out, (metaPart +: buffer).iterator)
        buffer = Nil
      }

      override def onUpstreamFinish(): Unit = {
        if (!buffer.isEmpty) emitMultiple(out, buffer.iterator)
        complete(out)
      }

    })
  }
}


object CollectMetaDataFlow {
  val collectMetaDataFlow = Flow[DicomPart].via(new CollectMetaDataFlow())

  val stripMetaDataFlow = Flow[DicomPart]
    .statefulMapConcat {
      () =>
      {
        case meta: DicomMetaPart =>
          Nil
        case part => part :: Nil
      }
    }
}


case class DicomMetaPart(patientId: String, patientName: String, identityRemoved: String, anonKeys: Option[AnonymizationKeys] = None) extends DicomPart {

  def bytes = ByteString.empty

  def bigEndian = false

  def isAnonymized = identityRemoved.toUpperCase == "YES"
}


