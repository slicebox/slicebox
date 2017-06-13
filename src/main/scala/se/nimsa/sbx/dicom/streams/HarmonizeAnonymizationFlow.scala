package se.nimsa.sbx.dicom.streams

import akka.stream.scaladsl.Flow
import org.dcm4che3.data.{SpecificCharacterSet, Tag}
import se.nimsa.dcm4che.streams.DicomParts._

/**
  * A flow which harmonizes anonymization so that random attributes correspond between patients, studies and series
  */
object HarmonizeAnonymizationFlow {

  private val harmonizeTags = Seq(
    Tag.PatientName,
    Tag.PatientID,
    Tag.StudyInstanceUID,
    Tag.SeriesInstanceUID,
    Tag.FrameOfReferenceUID)

  val harmonizeAnonFlow = Flow[DicomPart]
    .statefulMapConcat {

      () =>
        var maybeMeta: Option[DicomMetaPart] = None
        var maybeKeys: Option[AnonymizationKeysPart] = None

        var currentAttribute: Option[DicomAttribute] = None
        /*
         * do harmonize anon if:
         * - anomymization keys have been received in stream
         * - tag specifies attribute that needs to be harmonized
         */
        def needHarmonizeAnon(tag: Int): Boolean = canDoHarmonizeAnon && harmonizeTags.contains(tag)

        def canDoHarmonizeAnon: Boolean = maybeKeys.flatMap(_.patientKey).isDefined

      {
        case meta: DicomMetaPart =>
          maybeMeta = Some(meta)
          meta :: Nil

        case keys: AnonymizationKeysPart =>
          maybeKeys = Some(keys)
          keys :: Nil

        case header: DicomHeader if needHarmonizeAnon(header.tag) =>
          currentAttribute = Some(DicomAttribute(header, Seq.empty))
          Nil

        case header: DicomHeader =>
          currentAttribute = None
          header :: Nil

        case valueChunk: DicomValueChunk if currentAttribute.isDefined && canDoHarmonizeAnon =>
          currentAttribute = currentAttribute.map(attribute => attribute.copy(valueChunks = attribute.valueChunks :+ valueChunk))
          val attribute = currentAttribute.get
          val keys = maybeKeys.get
          val cs = maybeMeta.flatMap(_.specificCharacterSet).getOrElse(SpecificCharacterSet.ASCII)

          if (valueChunk.last) {
            val updatedAttribute = attribute.header.tag match {
              case Tag.PatientName => keys.patientKey.map(key => attribute.withUpdatedValue(key.anonPatientName, cs)).getOrElse(attribute)
              case Tag.PatientID => keys.patientKey.map(key => attribute.withUpdatedValue(key.anonPatientID, cs)).getOrElse(attribute)
              case Tag.StudyInstanceUID => keys.studyKey.map(key => attribute.withUpdatedValue(key.anonStudyInstanceUID)).getOrElse(attribute) // ASCII
              case Tag.SeriesInstanceUID => keys.seriesKey.map(key => attribute.withUpdatedValue(key.anonSeriesInstanceUID)).getOrElse(attribute) // ASCII
              case Tag.FrameOfReferenceUID => keys.seriesKey.map(key => attribute.withUpdatedValue(key.anonFrameOfReferenceUID)).getOrElse(attribute) // ASCII
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

  val maybeHarmonizeAnonFlow = DicomStreamOps.conditionalFlow(
    {
      case keys: AnonymizationKeysPart => keys.patientKey.isEmpty
    }, Flow.fromFunction(identity), harmonizeAnonFlow)

}








