package se.nimsa.sbx.dicom.streams

import akka.stream.scaladsl.Flow
import org.dcm4che3.data.{SpecificCharacterSet, Tag}
import se.nimsa.dcm4che.streams.DicomModifyFlow.TagModification
import se.nimsa.dcm4che.streams.DicomParts._
import se.nimsa.dcm4che.streams.{DicomModifyFlow, TagPath}

/**
  * A flow which performs reverse anonymization as soon as it has received an AnonymizationKeyPart (which means data is
  * anonymized)
  */
object ReverseAnonymizationFlow {

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

  val reverseAnonFlow = Flow[DicomPart]
    .via(DicomModifyFlow.modifyFlow(
      reverseTags.map(tag => TagModification(TagPath.fromTag(tag), identity, insert = true)): _*))
    .statefulMapConcat {

      () =>
        var maybeMeta: Option[DicomMetaPart] = None
        var maybeKeys: Option[AnonymizationKeysPart] = None
        var currentAttribute: Option[DicomAttribute] = None
        /*
         * do reverse anon if:
         * - anomymization keys have been received in stream
         * - tag specifies attribute that needs to be reversed
         */
        def needReverseAnon(tag: Int): Boolean = canDoReverseAnon && reverseTags.contains(tag)

        def canDoReverseAnon: Boolean = maybeKeys.flatMap(_.patientKey).isDefined

      {
        case meta: DicomMetaPart =>
          maybeMeta = Some(meta)
          meta :: Nil

        case keys: AnonymizationKeysPart =>
          maybeKeys = Some(keys)
          Nil

        case header: DicomHeader if needReverseAnon(header.tag) =>
          currentAttribute = Some(DicomAttribute(header, Seq.empty))
          Nil

        case header: DicomHeader =>
          currentAttribute = None
          header :: Nil

        case valueChunk: DicomValueChunk if currentAttribute.isDefined && canDoReverseAnon =>
          currentAttribute = currentAttribute.map(attribute => attribute.copy(valueChunks = attribute.valueChunks :+ valueChunk))
          val attribute = currentAttribute.get
          val keys = maybeKeys.get
          val cs = maybeMeta.flatMap(_.specificCharacterSet).getOrElse(SpecificCharacterSet.ASCII)

          if (valueChunk.last) {
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

            currentAttribute = None
            updatedAttribute.header :: updatedAttribute.valueChunks.toList
          } else
            Nil

        case part: DicomPart =>
          part :: Nil
      }
    }

  val maybeReverseAnonFlow = DicomStreamOps.conditionalFlow(
    {
      case keys: AnonymizationKeysPart => keys.patientKey.isEmpty
    }, Flow.fromFunction(identity), reverseAnonFlow)

}








