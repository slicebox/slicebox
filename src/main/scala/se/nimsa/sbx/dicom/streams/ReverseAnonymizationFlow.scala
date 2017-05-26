package se.nimsa.sbx.dicom.streams

import akka.stream.scaladsl.Flow
import org.dcm4che3.data.{SpecificCharacterSet, Tag}
import se.nimsa.dcm4che.streams.DicomFlows
import se.nimsa.dcm4che.streams.DicomFlows.TagModification
import se.nimsa.dcm4che.streams.DicomParts._
import se.nimsa.sbx.anonymization.AnonymizationProtocol.AnonymizationKey

/**
  * A flow which performs reverse anonymization as soon as it has received an AnonymizationKeyPart (which means data is
  * anonymized)
  */
object ReverseAnonymizationFlow {

  private val reverseAnonTags = Seq(
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
    Tag.FrameOfReferenceUID)

  val reverseAnonFlow = Flow[DicomPart]
    .via(DicomFlows.modifyFlow(
      reverseAnonTags.map(tag => TagModification(tag, identity, insert = true)): _*))
    .statefulMapConcat {

      () =>
        var anonKey: Option[AnonymizationKey] = None
        var characterSet: Option[SpecificCharacterSet] = None
        var currentAttribute: Option[DicomAttribute] = None

        /*
         * do reverse anon if:
         * - anomymization key has been received in stream
         * - tag specifies attribute that needs to be reversed
         */
        def needReverseAnon(tag: Int): Boolean = canDoReverseAnon && reverseAnonTags.contains(tag)

        def canDoReverseAnon: Boolean = anonKey.isDefined

      {
        case metaPart: DicomMetaPart =>
          characterSet = metaPart.specificCharacterSet
          metaPart :: Nil

        case keyPart: AnonymizationKeyPart =>
          anonKey = keyPart.anonymizationKey
          Nil

        case header: DicomHeader if needReverseAnon(header.tag) =>
          currentAttribute = Some(DicomAttribute(header, Seq.empty))
          Nil

        case header: DicomHeader =>
          currentAttribute = None
          header :: Nil

        case valueChunk: DicomValueChunk if currentAttribute.isDefined && canDoReverseAnon =>
          val attribute = currentAttribute.map(attribute => attribute.copy(valueChunks = attribute.valueChunks :+ valueChunk)).get
          val key = anonKey.get
          val cs = characterSet.getOrElse(SpecificCharacterSet.ASCII)
          currentAttribute = None

          if (valueChunk.last) {
            val updatedAttribute = attribute.header.tag match {
              case Tag.PatientName => attribute.withUpdatedValue(key.patientName, cs)
              case Tag.PatientID => attribute.withUpdatedValue(key.patientID, cs)
              case Tag.PatientBirthDate => attribute.withUpdatedValue(key.patientBirthDate) // ASCII
              case Tag.PatientIdentityRemoved => attribute.withUpdatedValue("NO") // ASCII
              case Tag.StudyInstanceUID => attribute.withUpdatedValue(key.studyInstanceUID) // ASCII
              case Tag.StudyDescription => attribute.withUpdatedValue(key.studyDescription, cs)
              case Tag.StudyID => attribute.withUpdatedValue(key.studyID, cs)
              case Tag.AccessionNumber => attribute.withUpdatedValue(key.accessionNumber, cs)
              case Tag.SeriesInstanceUID => attribute.withUpdatedValue(key.seriesInstanceUID) // ASCII
              case Tag.SeriesDescription => attribute.withUpdatedValue(key.seriesDescription, cs)
              case Tag.ProtocolName => attribute.withUpdatedValue(key.protocolName, cs)
              case Tag.FrameOfReferenceUID => attribute.withUpdatedValue(key.frameOfReferenceUID) // ASCII
              case _ => attribute
            }

            updatedAttribute.header :: updatedAttribute.valueChunks.toList
          } else
            Nil

        case part: DicomPart =>
          part :: Nil
      }
    }

}








