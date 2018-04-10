/*
 * Copyright 2014 Lars Edenbrandt
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package se.nimsa.sbx.dicom.streams

import akka.NotUsed
import akka.stream.scaladsl.Flow
import akka.util.ByteString
import se.nimsa.dicom.{Tag, TagPath}
import se.nimsa.dicom.streams.DicomFlows._
import se.nimsa.dicom.streams.DicomModifyFlow.TagModification
import se.nimsa.dicom.streams.DicomParts.{DicomAttribute, DicomHeader, DicomPart, DicomValueChunk}
import se.nimsa.dicom.streams._
import se.nimsa.sbx.dicom.streams.DicomStreamUtil._

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

  def reverseAnonFlow: Flow[DicomPart, DicomPart, NotUsed] = Flow[DicomPart]
    .via(groupLengthDiscardFilter)
    .via(toUndefinedLengthSequences)
    .via(toUtf8Flow)
    .via(DicomModifyFlow.modifyFlow(
      reverseTags.map(tag => TagModification.endsWith(TagPath.fromTag(tag), identity, insert = true)): _*))
    .via(DicomFlowFactory.create(new IdentityFlow with GuaranteedValueEvent with StartEvent {
      var maybeKey: Option[PartialAnonymizationKeyPart] = None
      var currentAttribute: Option[DicomAttribute] = None

      def maybeReverse(attribute: DicomAttribute, keyPart: PartialAnonymizationKeyPart): List[DicomPart with Product with Serializable] = {
        val updatedAttribute = attribute.header.tag match {
          case Tag.PatientName => keyPart.keyMaybe.filter(_ => keyPart.hasPatientInfo).map(key =>
            attribute.withUpdatedValue(ByteString(key.patientName))).getOrElse(attribute)
          case Tag.PatientID => keyPart.keyMaybe.filter(_ => keyPart.hasPatientInfo).map(key =>
            attribute.withUpdatedValue(ByteString(key.patientID))).getOrElse(attribute)
          case Tag.PatientBirthDate => keyPart.keyMaybe.filter(_ => keyPart.hasPatientInfo).map(key =>
            attribute.withUpdatedValue(ByteString(key.patientBirthDate))).getOrElse(attribute)
          case Tag.PatientIdentityRemoved => attribute.withUpdatedValue(ByteString("NO"))
          case Tag.DeidentificationMethod => attribute.withUpdatedValue(ByteString.empty)
          case Tag.StudyInstanceUID => keyPart.keyMaybe.filter(_ => keyPart.hasStudyInfo).map(key =>
            attribute.withUpdatedValue(ByteString(key.studyInstanceUID))).getOrElse(attribute)
          case Tag.StudyDescription => keyPart.keyMaybe.filter(_ => keyPart.hasStudyInfo).map(key =>
            attribute.withUpdatedValue(ByteString(key.studyDescription))).getOrElse(attribute)
          case Tag.StudyID => keyPart.keyMaybe.filter(_ => keyPart.hasStudyInfo).map(key =>
            attribute.withUpdatedValue(ByteString(key.studyID))).getOrElse(attribute)
          case Tag.AccessionNumber => keyPart.keyMaybe.filter(_ => keyPart.hasStudyInfo).map(key =>
            attribute.withUpdatedValue(ByteString(key.accessionNumber))).getOrElse(attribute)
          case Tag.SeriesInstanceUID => keyPart.keyMaybe.filter(_ => keyPart.hasSeriesInfo).map(key =>
            attribute.withUpdatedValue(ByteString(key.seriesInstanceUID))).getOrElse(attribute)
          case Tag.SeriesDescription => keyPart.keyMaybe.filter(_ => keyPart.hasSeriesInfo).map(key =>
            attribute.withUpdatedValue(ByteString(key.seriesDescription))).getOrElse(attribute)
          case Tag.ProtocolName => keyPart.keyMaybe.filter(_ => keyPart.hasSeriesInfo).map(key =>
            attribute.withUpdatedValue(ByteString(key.protocolName))).getOrElse(attribute)
          case Tag.FrameOfReferenceUID => keyPart.keyMaybe.filter(_ => keyPart.hasSeriesInfo).map(key =>
            attribute.withUpdatedValue(ByteString(key.frameOfReferenceUID))).getOrElse(attribute)
          case _ => attribute
        }
        updatedAttribute.header :: updatedAttribute.valueChunks.toList
      }

      /*
       * do reverse anon if:
       * - anomymization keys have been received in stream
       * - tag specifies attribute that needs to be reversed
       */
      def needReverseAnon(tag: Int, maybeKeys: Option[PartialAnonymizationKeyPart]): Boolean = canDoReverseAnon(maybeKeys) && reverseTags.contains(tag)

      def canDoReverseAnon(keyPartMaybe: Option[PartialAnonymizationKeyPart]): Boolean = keyPartMaybe.flatMap(_.keyMaybe).isDefined

      override def onPart(part: DicomPart): List[DicomPart] = part match {
        case keys: PartialAnonymizationKeyPart =>
          maybeKey = Some(keys)
          super.onPart(keys).filterNot(_ == keys)
        case p => super.onPart(p)
      }

      override def onHeader(header: DicomHeader): List[DicomPart] =
        if (needReverseAnon(header.tag, maybeKey) && canDoReverseAnon(maybeKey)) {
          currentAttribute = Some(DicomAttribute(header, TagPath.fromTag(header.tag), Seq.empty))
          super.onHeader(header).filterNot(_ == header)
        } else {
          currentAttribute = None
          super.onHeader(header)
        }

      override def onValueChunk(chunk: DicomValueChunk): List[DicomPart] =
        if (currentAttribute.isDefined && canDoReverseAnon(maybeKey)) {
          currentAttribute = currentAttribute.map(attribute => attribute.copy(valueChunks = attribute.valueChunks :+ chunk))

          if (chunk.last) {
            val attribute = currentAttribute.get
            val keys = maybeKey.get

            currentAttribute = None
            super.onValueChunk(chunk).filterNot(_ == chunk) ::: maybeReverse(attribute, keys)
          } else
            super.onValueChunk(chunk).filterNot(_ == chunk)
        } else
          super.onValueChunk(chunk)

      override def onStart(): List[DicomPart] = {
        maybeKey = None
        currentAttribute = None
        super.onStart()
      }
    }))

  def maybeReverseAnonFlow: Flow[DicomPart, DicomPart, NotUsed] = conditionalFlow(
    {
      case keyPart: PartialAnonymizationKeyPart => keyPart.keyMaybe.isEmpty
    }, Flow.fromFunction(identity), reverseAnonFlow)

}








