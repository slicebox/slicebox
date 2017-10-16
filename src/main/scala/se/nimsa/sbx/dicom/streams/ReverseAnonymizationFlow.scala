package se.nimsa.sbx.dicom.streams

import akka.NotUsed
import akka.stream.scaladsl.Flow
import org.dcm4che3.data.{SpecificCharacterSet, Tag}
import se.nimsa.dcm4che.streams.DicomFlows.toUndefinedLengthSequences
import se.nimsa.dcm4che.streams.DicomModifyFlow.TagModification
import se.nimsa.dcm4che.streams.DicomParts._
import se.nimsa.dcm4che.streams._

/**
  * A flow which performs reverse anonymization as soon as it has received an AnonymizationKeyPart (which means data is
  * anonymized)
  */
object ReverseAnonymizationFlow {

  import DicomFlows.groupLengthDiscardFilter

  private val reverseTags = Seq(
    Tag.PatientName,
    Tag.PatientID,
    Tag.PatientBirthDate,
    Tag.PatientIdentityRemoved,
    Tag.DeidentificationMethod,
    Tag.StudyInstanceUID,
    Tag.StudyDescription,
    Tag.StudyID,
    Tag.AccessionNumber,
    Tag.SeriesInstanceUID,
    Tag.SeriesDescription,
    Tag.ProtocolName,
    Tag.FrameOfReferenceUID)

  def reverseAnonFlow: Flow[DicomPart, DicomPart, NotUsed] = Flow[DicomPart]
    .via(groupLengthDiscardFilter)
    .via(toUndefinedLengthSequences)
    .via(DicomModifyFlow.modifyFlow(
      reverseTags.map(tag => TagModification.endsWith(TagPath.fromTag(tag), identity, insert = true)): _*))
    .via(DicomFlowFactory.create(new IdentityFlow with GuaranteedValueEvent with StartEvent {
      var maybeMeta: Option[DicomMetaPart] = None
      var maybeKeys: Option[AnonymizationKeysPart] = None
      var currentAttribute: Option[DicomAttribute] = None

      def maybeReverse(attribute: DicomAttribute, keys: AnonymizationKeysPart, cs: SpecificCharacterSet): List[DicomPart with Product with Serializable] = {
        val updatedAttribute = attribute.header.tag match {
          case Tag.PatientName => keys.patientKey.map(key => attribute.withUpdatedValue(key.patientName, cs)).getOrElse(attribute)
          case Tag.PatientID => keys.patientKey.map(key => attribute.withUpdatedValue(key.patientID, cs)).getOrElse(attribute)
          case Tag.PatientBirthDate => keys.patientKey.map(key => attribute.withUpdatedValue(key.patientBirthDate)).getOrElse(attribute) // ASCII
          case Tag.PatientIdentityRemoved => attribute.withUpdatedValue("NO") // ASCII
          case Tag.DeidentificationMethod => attribute.withUpdatedValue("")
          case Tag.StudyInstanceUID => keys.studyKey.map(key => attribute.withUpdatedValue(key.studyInstanceUID)).getOrElse(attribute) // ASCII
          case Tag.StudyDescription => keys.studyKey.map(key => attribute.withUpdatedValue(key.studyDescription, cs)).getOrElse(attribute)
          case Tag.StudyID => keys.studyKey.map(key => attribute.withUpdatedValue(key.studyID, cs)).getOrElse(attribute)
          case Tag.AccessionNumber => keys.studyKey.map(key => attribute.withUpdatedValue(key.accessionNumber, cs)).getOrElse(attribute)
          case Tag.SeriesInstanceUID => keys.seriesKey.map(key => attribute.withUpdatedValue(key.seriesInstanceUID)).getOrElse(attribute) // ASCII
          case Tag.SeriesDescription => keys.seriesKey.map(key => attribute.withUpdatedValue(key.seriesDescription, cs)).getOrElse(attribute)
          case Tag.ProtocolName => keys.seriesKey.map(key => attribute.withUpdatedValue(key.protocolName, cs)).getOrElse(attribute)
          case Tag.FrameOfReferenceUID => keys.seriesKey.map(key => attribute.withUpdatedValue(key.frameOfReferenceUID)).getOrElse(attribute) // ASCII
          case _ => attribute
        }
        updatedAttribute.header :: updatedAttribute.valueChunks.toList
      }

      /*
       * do reverse anon if:
       * - anomymization keys have been received in stream
       * - tag specifies attribute that needs to be reversed
       */
      def needReverseAnon(tag: Int, maybeKeys: Option[AnonymizationKeysPart]): Boolean = canDoReverseAnon(maybeKeys) && reverseTags.contains(tag)

      def canDoReverseAnon(maybeKeys: Option[AnonymizationKeysPart]): Boolean = maybeKeys.flatMap(_.patientKey).isDefined

      override def onPart(part: DicomPart): List[DicomPart] = part match {
        case meta: DicomMetaPart =>
          maybeMeta = Some(meta)
          super.onPart(meta)
        case keys: AnonymizationKeysPart =>
          maybeKeys = Some(keys)
          super.onPart(keys).filterNot(_ == keys)
        case p => super.onPart(p)
      }

      override def onHeader(header: DicomHeader): List[DicomPart] =
        if (needReverseAnon(header.tag, maybeKeys) && canDoReverseAnon(maybeKeys)) {
          currentAttribute = Some(DicomAttribute(header, Seq.empty))
          super.onHeader(header).filterNot(_ == header)
        } else {
          currentAttribute = None
          super.onHeader(header)
        }

      override def onValueChunk(chunk: DicomValueChunk): List[DicomPart] =
        if (currentAttribute.isDefined && canDoReverseAnon(maybeKeys)) {
          currentAttribute = currentAttribute.map(attribute => attribute.copy(valueChunks = attribute.valueChunks :+ chunk))

          if (chunk.last) {
            val attribute = currentAttribute.get
            val keys = maybeKeys.get
            val cs = maybeMeta.flatMap(_.specificCharacterSet).getOrElse(SpecificCharacterSet.ASCII)

            currentAttribute = None
            super.onValueChunk(chunk).filterNot(_ == chunk) ::: maybeReverse(attribute, keys, cs)
          } else
            super.onValueChunk(chunk).filterNot(_ == chunk)
        } else
          super.onValueChunk(chunk)

      override def onStart(): List[DicomPart] = {
        maybeMeta = None
        maybeKeys = None
        currentAttribute = None
        super.onStart()
      }
    }))

  def maybeReverseAnonFlow: Flow[DicomPart, DicomPart, NotUsed] = DicomStreamOps.conditionalFlow(
    {
      case keys: AnonymizationKeysPart => keys.patientKey.isEmpty
    }, Flow.fromFunction(identity), reverseAnonFlow)

}








