package se.nimsa.sbx.dicom.streams

import akka.NotUsed
import akka.stream.scaladsl.Flow
import org.dcm4che3.data.{SpecificCharacterSet, Tag}
import se.nimsa.dcm4che.streams.DicomFlows.toUndefinedLengthSequences
import se.nimsa.dcm4che.streams.DicomParts._
import se.nimsa.dcm4che.streams._

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

  val harmonizeAnonFlow: Flow[DicomPart, DicomPart, NotUsed] = Flow[DicomPart]
    .via(groupLengthDiscardFilter)
    .via(toUndefinedLengthSequences)
    .via(DicomFlowFactory.create(new IdentityFlow with GuaranteedValueEvent with StartEvent {
      var maybeMeta: Option[DicomMetaPart] = None
      var maybeKeys: Option[AnonymizationKeysPart] = None
      var currentAttribute: Option[DicomAttribute] = None

      def maybeHarmonize(attribute: DicomAttribute, keys: AnonymizationKeysPart, cs: SpecificCharacterSet): List[DicomPart with Product with Serializable] = {
        val updatedAttribute = attribute.header.tag match {
          case Tag.PatientName => keys.patientKey.map(key => attribute.withUpdatedValue(key.anonPatientName, cs)).getOrElse(attribute)
          case Tag.PatientID => keys.patientKey.map(key => attribute.withUpdatedValue(key.anonPatientID, cs)).getOrElse(attribute)
          case Tag.StudyInstanceUID => keys.studyKey.map(key => attribute.withUpdatedValue(key.anonStudyInstanceUID)).getOrElse(attribute) // ASCII
          case Tag.SeriesInstanceUID => keys.seriesKey.map(key => attribute.withUpdatedValue(key.anonSeriesInstanceUID)).getOrElse(attribute) // ASCII
          case Tag.FrameOfReferenceUID => keys.seriesKey.map(key => attribute.withUpdatedValue(key.anonFrameOfReferenceUID)).getOrElse(attribute) // ASCII
          case _ => attribute
        }
        updatedAttribute.header :: updatedAttribute.valueChunks.toList
      }
      /*
       * do harmonize anon if:
       * - anomymization keys have been received in stream
       * - tag specifies attribute that needs to be harmonized
       */
      def needHarmonizeAnon(tag: Int, maybeKeys: Option[AnonymizationKeysPart]): Boolean = canDoHarmonizeAnon(maybeKeys) && harmonizeTags.contains(tag)

      def canDoHarmonizeAnon(maybeKeys: Option[AnonymizationKeysPart]): Boolean = maybeKeys.flatMap(_.patientKey).isDefined

      override def onPart(part: DicomPart): List[DicomPart] = part match {
        case meta: DicomMetaPart =>
          maybeMeta = Some(meta)
          super.onPart(part)

        case keys: AnonymizationKeysPart =>
          maybeKeys = Some(keys)
          super.onPart(keys)
      }

      override def onHeader(header: DicomHeader): List[DicomPart] =
        if (needHarmonizeAnon(header.tag, maybeKeys)) {
          currentAttribute = Some(DicomAttribute(header, Seq.empty))
          super.onHeader(header).filterNot(_ == header)
        } else {
          currentAttribute = None
          super.onHeader(header)
        }

      override def onValueChunk(valueChunk: DicomValueChunk): List[DicomPart] =
        if (currentAttribute.isDefined && canDoHarmonizeAnon(maybeKeys)) {
          currentAttribute = currentAttribute.map(attribute => attribute.copy(valueChunks = attribute.valueChunks :+ valueChunk))

          if (valueChunk.last) {
            val attribute = currentAttribute.get
            val keys = maybeKeys.get
            val cs = maybeMeta.flatMap(_.specificCharacterSet).getOrElse(SpecificCharacterSet.ASCII)

            currentAttribute = None
            maybeHarmonize(attribute, keys, cs)
          } else
            super.onValueChunk(valueChunk).filterNot(_ == valueChunk)
        } else
          super.onValueChunk(valueChunk)

      override def onStart(): List[DicomPart] = {
        maybeMeta = None
        maybeKeys = None
        currentAttribute = None
        super.onStart()
      }
    }))

  def maybeHarmonizeAnonFlow: Flow[DicomPart, DicomPart, NotUsed] = DicomStreamOps.conditionalFlow(
    {
      case keys: AnonymizationKeysPart => keys.patientKey.isEmpty
    }, Flow.fromFunction(identity), harmonizeAnonFlow)

}








