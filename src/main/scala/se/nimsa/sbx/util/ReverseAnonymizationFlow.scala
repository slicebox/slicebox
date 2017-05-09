package se.nimsa.sbx.util

import akka.stream.scaladsl.Flow
import akka.stream.stage.{GraphStage, GraphStageLogic, InHandler, OutHandler}
import akka.stream.{Attributes, FlowShape, Inlet, Outlet}
import org.dcm4che3.data.Tag
import se.nimsa.dcm4che.streams.DicomParts._

/**
  * A flow which expects a DicomMetaPart as first part, and does reverse anonymization based on anonymization data lookup in DB.
  */
class ReverseAnonymizationFlow() extends GraphStage[FlowShape[DicomPart, DicomPart]] {
  val in = Inlet[DicomPart]("DicomAttributeBufferFlow.in")
  val out = Outlet[DicomPart]("DicomAttributeBufferFlow.out")
  override val shape = FlowShape.of(in, out)

  override def createLogic(inheritedAttributes: Attributes): GraphStageLogic = new GraphStageLogic(shape) {
    var metaData: Option[DicomMetaPart] = None
    val REVERSE_ANON_TAGS = Seq(Tag.PatientName,
      Tag.PatientID,
      Tag.PatientBirthDate,
      Tag.StudyInstanceUID,
      Tag.StudyDescription,
      Tag.StudyID,
      Tag.AccessionNumber,
      Tag.SeriesInstanceUID,
      Tag.SeriesDescription,
      Tag.ProtocolName,
      Tag.FrameOfReferenceUID
    )
    var currentAttribute: Option[DicomAttribute] = None


    def isAnonymized = if (metaData.isDefined) {
      metaData.get.isAnonymized
    } else {
      false
    }


    setHandlers(in, out, new InHandler with OutHandler {

      override def onPull(): Unit = {
        pull(in)
      }

      override def onPush(): Unit = {

        val part = grab(in)

        part match {
          case metaPart: DicomMetaPart =>
            metaData = Some(metaPart)
            println(">>>> grabbed meta, isAnon: " + isAnonymized)
            pull(in)



          case header: DicomHeader if REVERSE_ANON_TAGS.contains(header.tag)  =>
            // fixme
            println(">>>> DO REVERSE ANON for: " + header.tag)
            currentAttribute = Some(DicomAttribute(header, Seq.empty))
            //push(out, part)
            pull(in)

          case header: DicomHeader =>
            currentAttribute = None
            push(out, part)


          case valueChunk: DicomValueChunk if currentAttribute.isDefined =>
            println(">>>> value chunk for currentAttr: " + valueChunk)
            currentAttribute = currentAttribute.map(attribute => attribute.copy(valueChunks = attribute.valueChunks :+ valueChunk))
            if (valueChunk.last) {

              // FIXME: reverseAnon()
              if (currentAttribute.get.header.tag == Tag.PatientName && false) {
                val updatedAttribute = currentAttribute.get.updateStringValue("Theodore^Test") // FIXME: specific cs
                println(">>>> emit updatedAttribute: " + updatedAttribute)
                emitMultiple(out, (updatedAttribute.header +: updatedAttribute.valueChunks).iterator)
                //push(out, updatedAttribute)

              } else {
                println(">>>> emit currentAttribute: " + currentAttribute.get)
                emitMultiple(out, (currentAttribute.get.header +: currentAttribute.get.valueChunks).iterator)
                //push(out, currentAttribute.get)
              }

              currentAttribute = None
            } else {
              pull(in)
            }


          case part: DicomPart =>
            push(out, part)

          case _ => println(">>>>>> WOT???")


        }


      }

      override def onUpstreamFinish(): Unit = {
        complete(out)
      }

    })
  }

}


object ReverseAnonymizationFlow {
  val reverseAnonFlow = Flow[DicomPart].via(new ReverseAnonymizationFlow())
}








