package se.nimsa.sbx.dicom.streams

import akka.NotUsed
import akka.stream.scaladsl.Flow
import org.dcm4che3.data.{SpecificCharacterSet, Tag}
import se.nimsa.dcm4che.streams.DicomFlows.toUndefinedLengthSequences
import se.nimsa.dcm4che.streams.DicomParts._
import se.nimsa.dcm4che.streams._
import se.nimsa.sbx.dicom.streams.DicomStreamOps.{DicomInfoPart, PartialAnonymizationKeyPart}

/**
  * A flow which harmonizes anonymization so that random attributes correspond between patients, studies and series
  */
object HarmonizeAnonymizationFlow {

  import DicomFlows.groupLengthDiscardFilter

  private val harmonizeTags = Seq(
    Tag.PatientName,
    Tag.PatientID,
    Tag.StudyInstanceUID,
    Tag.SeriesInstanceUID,
    Tag.FrameOfReferenceUID)

  def harmonizeAnonFlow: Flow[DicomPart, DicomPart, NotUsed] = Flow[DicomPart]
    .via(groupLengthDiscardFilter)
    .via(toUndefinedLengthSequences)
    .via(DicomFlowFactory.create(new IdentityFlow with GuaranteedValueEvent with StartEvent {
      var maybeInfo: Option[DicomInfoPart] = None
      var maybeKey: Option[PartialAnonymizationKeyPart] = None
      var currentAttribute: Option[DicomAttribute] = None

      def maybeHarmonize(attribute: DicomAttribute, keyPart: PartialAnonymizationKeyPart, cs: SpecificCharacterSet): List[DicomPart] = {
        val updatedAttribute = attribute.header.tag match {
          case Tag.PatientName => keyPart.keyMaybe
            .map(key => attribute.withUpdatedValue(key.anonPatientName, cs))
            .getOrElse(attribute)
          case Tag.PatientID => keyPart.keyMaybe
            .map(key => attribute.withUpdatedValue(key.anonPatientID, cs))
            .getOrElse(attribute)
          case Tag.StudyInstanceUID => keyPart.keyMaybe
            .map(key => attribute.withUpdatedValue(key.anonStudyInstanceUID))
            .getOrElse(attribute) // ASCII
          case Tag.SeriesInstanceUID => keyPart.keyMaybe
            .map(key => attribute.withUpdatedValue(key.anonSeriesInstanceUID))
            .getOrElse(attribute) // ASCII
          case Tag.FrameOfReferenceUID => keyPart.keyMaybe
            .map(key => attribute.withUpdatedValue(key.anonFrameOfReferenceUID))
            .getOrElse(attribute) // ASCII
          case _ => attribute
        }
        updatedAttribute.header :: updatedAttribute.valueChunks.toList
      }
      /*
       * do harmonize anon if:
       * - anomymization keys have been received in stream
       * - tag specifies attribute that needs to be harmonized
       */
      def needHarmonizeAnon(tag: Int, keyPart: Option[PartialAnonymizationKeyPart]): Boolean = canDoHarmonizeAnon(keyPart) && harmonizeTags.contains(tag)

      def canDoHarmonizeAnon(keyPartMaybe: Option[PartialAnonymizationKeyPart]): Boolean = keyPartMaybe.flatMap(_.keyMaybe).isDefined

      override def onPart(part: DicomPart): List[DicomPart] = part match {
        case info: DicomInfoPart =>
          maybeInfo = Some(info)
          super.onPart(part)

        case keyPart: PartialAnonymizationKeyPart =>
          maybeKey = Some(keyPart)
          super.onPart(keyPart)
      }

      override def onHeader(header: DicomHeader): List[DicomPart] =
        if (needHarmonizeAnon(header.tag, maybeKey)) {
          currentAttribute = Some(DicomAttribute(header, Seq.empty))
          super.onHeader(header).filterNot(_ == header)
        } else {
          currentAttribute = None
          super.onHeader(header)
        }

      override def onValueChunk(valueChunk: DicomValueChunk): List[DicomPart] =
        if (currentAttribute.isDefined && canDoHarmonizeAnon(maybeKey)) {
          currentAttribute = currentAttribute.map(attribute => attribute.copy(valueChunks = attribute.valueChunks :+ valueChunk))

          if (valueChunk.last) {
            val attribute = currentAttribute.get
            val key = maybeKey.get
            val cs = maybeInfo.flatMap(_.specificCharacterSet).getOrElse(SpecificCharacterSet.ASCII)

            currentAttribute = None
            maybeHarmonize(attribute, key, cs)
          } else
            super.onValueChunk(valueChunk).filterNot(_ == valueChunk)
        } else
          super.onValueChunk(valueChunk)

      override def onStart(): List[DicomPart] = {
        maybeInfo = None
        maybeKey = None
        currentAttribute = None
        super.onStart()
      }
    }))

  def maybeHarmonizeAnonFlow: Flow[DicomPart, DicomPart, NotUsed] = DicomStreamOps.conditionalFlow(
    {
      case keyPart: PartialAnonymizationKeyPart => keyPart.keyMaybe.isEmpty
    }, Flow.fromFunction(identity), harmonizeAnonFlow)

}








