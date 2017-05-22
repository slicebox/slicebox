package se.nimsa.sbx.dicom.streams

import akka.stream.scaladsl.Flow
import com.typesafe.scalalogging.Logger
import org.dcm4che3.data.{SpecificCharacterSet, Tag}
import se.nimsa.dcm4che.streams.DicomParts._

/**
  * A flow which expects a DicomMetaPart as first part, and does reverse anonymization based on anonymization data lookup in DB.
  */
object ReverseAnonymizationFlow {

  val reverseAnonFlow = Flow[DicomPart]
    .statefulMapConcat {
      val log = Logger("ReverseAnonymizationFlow")

      val REVERSE_ANON_TAGS = Seq(
        Tag.PatientName,
        Tag.PatientID,
        Tag.PatientBirthDate,
        Tag.PatientIdentityRemoved,
        Tag.StudyInstanceUID,
        Tag.StudyDescription,
        Tag.StudyID,
        Tag.AccessionNumber,
        Tag.SeriesInstanceUID,
        Tag.SeriesDescription,
        Tag.ProtocolName,
        Tag.FrameOfReferenceUID
      )

      () =>
        var metaData: Option[DicomMetaPart] = None
        var currentAttribute: Option[DicomAttribute] = None

        /**
          * do reverse anon if:
          * - metaData is defined and data is anonymized
          * - anomymization keys found in DB
          * - tag specifies attribute that needs to be reversed
          */
        def needReverseAnon(tag: Int): Boolean = {
          canDoReverseAnon && REVERSE_ANON_TAGS.contains(tag)
        }

        def canDoReverseAnon: Boolean = metaData.exists(m => m.isAnonymized && m.anonKeys.isDefined)

      {
        case metaPart: DicomMetaPart =>
          metaData = Some(metaPart)
          log.debug(s"Collected meta data - isAnonymous: ${metaPart.isAnonymized}")
          log.debug(s"Collected meta data - canDoReverse: $canDoReverseAnon")
          metaPart :: Nil

        case header: DicomHeader if needReverseAnon(header.tag) =>
          currentAttribute = Some(DicomAttribute(header, Seq.empty))
          Nil

        case header: DicomHeader =>
          currentAttribute = None
          header :: Nil

        case valueChunk: DicomValueChunk if currentAttribute.isDefined && canDoReverseAnon =>
          currentAttribute = currentAttribute.map(attribute => attribute.copy(valueChunks = attribute.valueChunks :+ valueChunk))
          val cs = if (metaData.get.specificCharacterSet.isDefined) metaData.get.specificCharacterSet.get else SpecificCharacterSet.ASCII
          if (valueChunk.last) {

            val updatedAttribute = currentAttribute.get.header.tag match {
              case Tag.PatientName =>
                currentAttribute.get.withUpdatedStringValue(metaData.get.anonKeys.get.patientName, cs)

              case Tag.PatientID =>
                currentAttribute.get.withUpdatedStringValue(metaData.get.anonKeys.get.patientID, cs)

              case Tag.PatientBirthDate =>
                currentAttribute.get.withUpdatedDateValue(metaData.get.anonKeys.get.patientBirthDate) // ASCII

              case Tag.PatientIdentityRemoved =>
                currentAttribute.get.withUpdatedStringValue("NO") // ASCII

              case Tag.StudyInstanceUID =>
                currentAttribute.get.withUpdatedStringValue(metaData.get.anonKeys.get.studyInstanceUID) // ASCII

              case Tag.StudyDescription =>
                currentAttribute.get.withUpdatedStringValue(metaData.get.anonKeys.get.studyDescription, cs)

              case Tag.StudyID =>
                currentAttribute.get.withUpdatedStringValue(metaData.get.anonKeys.get.studyID, cs)

              case Tag.AccessionNumber =>
                currentAttribute.get.withUpdatedStringValue(metaData.get.anonKeys.get.accessionNumber, cs)

              case Tag.SeriesInstanceUID =>
                currentAttribute.get.withUpdatedStringValue(metaData.get.anonKeys.get.seriesInstanceUID) // ASCII

              case Tag.SeriesDescription =>
                currentAttribute.get.withUpdatedStringValue(metaData.get.anonKeys.get.seriesDescription, cs)

              case Tag.ProtocolName =>
                currentAttribute.get.withUpdatedStringValue(metaData.get.anonKeys.get.protocolName, cs)

              case Tag.FrameOfReferenceUID =>
                currentAttribute.get.withUpdatedStringValue(metaData.get.anonKeys.get.frameOfReferenceUID) // ASCII

              case _ =>
                currentAttribute.get
            }

            currentAttribute = None
            updatedAttribute.header :: updatedAttribute.valueChunks.toList
          } else
            Nil

        case part: DicomPart =>
          part :: Nil
      }
    }

}








