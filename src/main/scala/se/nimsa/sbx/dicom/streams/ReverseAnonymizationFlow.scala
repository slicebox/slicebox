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
import se.nimsa.dicom.data.DicomParts._
import se.nimsa.dicom.data.Elements.ValueElement
import se.nimsa.dicom.data.{Tag, TagPath, Value}
import se.nimsa.dicom.streams.DicomFlows._
import se.nimsa.dicom.streams.ModifyFlow.{TagModification, modifyFlow}
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
    .via(modifyFlow(
      reverseTags.map(tag => TagModification.endsWith(TagPath.fromTag(tag), identity, insert = true)): _*))
    .via(DicomFlowFactory.create(new IdentityFlow with GuaranteedValueEvent[DicomPart] with StartEvent[DicomPart] {
      var maybeKey: Option[PartialAnonymizationKeyPart] = None
      var currentElement: Option[ValueElement] = None

      def maybeReverse(element: ValueElement, keyPart: PartialAnonymizationKeyPart): List[DicomPart] = {
        val updatedElement = element.tag match {
          case Tag.PatientName => keyPart.keyMaybe.filter(_ => keyPart.hasPatientInfo).map(key =>
            element.setValue(Value(ByteString(key.patientName)))).getOrElse(element)
          case Tag.PatientID => keyPart.keyMaybe.filter(_ => keyPart.hasPatientInfo).map(key =>
            element.setValue(Value(ByteString(key.patientID)))).getOrElse(element)
          case Tag.PatientBirthDate => keyPart.keyMaybe.filter(_ => keyPart.hasPatientInfo).map(key =>
            element.setValue(Value(ByteString(key.patientBirthDate)))).getOrElse(element)
          case Tag.PatientIdentityRemoved => element.setValue(Value(ByteString("NO")))
          case Tag.DeidentificationMethod => element.setValue(Value(ByteString.empty))
          case Tag.StudyInstanceUID => keyPart.keyMaybe.filter(_ => keyPart.hasStudyInfo).map(key =>
            element.setValue(Value(ByteString(key.studyInstanceUID)))).getOrElse(element)
          case Tag.StudyDescription => keyPart.keyMaybe.filter(_ => keyPart.hasStudyInfo).map(key =>
            element.setValue(Value(ByteString(key.studyDescription)))).getOrElse(element)
          case Tag.StudyID => keyPart.keyMaybe.filter(_ => keyPart.hasStudyInfo).map(key =>
            element.setValue(Value(ByteString(key.studyID)))).getOrElse(element)
          case Tag.AccessionNumber => keyPart.keyMaybe.filter(_ => keyPart.hasStudyInfo).map(key =>
            element.setValue(Value(ByteString(key.accessionNumber)))).getOrElse(element)
          case Tag.SeriesInstanceUID => keyPart.keyMaybe.filter(_ => keyPart.hasSeriesInfo).map(key =>
            element.setValue(Value(ByteString(key.seriesInstanceUID)))).getOrElse(element)
          case Tag.SeriesDescription => keyPart.keyMaybe.filter(_ => keyPart.hasSeriesInfo).map(key =>
            element.setValue(Value(ByteString(key.seriesDescription)))).getOrElse(element)
          case Tag.ProtocolName => keyPart.keyMaybe.filter(_ => keyPart.hasSeriesInfo).map(key =>
            element.setValue(Value(ByteString(key.protocolName)))).getOrElse(element)
          case Tag.FrameOfReferenceUID => keyPart.keyMaybe.filter(_ => keyPart.hasSeriesInfo).map(key =>
            element.setValue(Value(ByteString(key.frameOfReferenceUID)))).getOrElse(element)
          case _ => element
        }
        updatedElement.toParts
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

      override def onHeader(header: HeaderPart): List[DicomPart] =
        if (needReverseAnon(header.tag, maybeKey) && canDoReverseAnon(maybeKey)) {
          currentElement = Some(ValueElement.empty(header.tag, header.vr, header.bigEndian, header.explicitVR))
          super.onHeader(header).filterNot(_ == header)
        } else {
          currentElement = None
          super.onHeader(header)
        }

      override def onValueChunk(chunk: ValueChunk): List[DicomPart] =
        if (currentElement.isDefined && canDoReverseAnon(maybeKey)) {
          currentElement = currentElement.map(attribute => attribute.copy(value = attribute.value ++ chunk.bytes))

          if (chunk.last) {
            val e = currentElement.get
            val element = e.copy(value = e.value.ensurePadding(e.vr))
            val keys = maybeKey.get

            currentElement = None
            super.onValueChunk(chunk).filterNot(_ == chunk) ::: maybeReverse(element, keys)
          } else
            super.onValueChunk(chunk).filterNot(_ == chunk)
        } else
          super.onValueChunk(chunk)

      override def onStart(): List[DicomPart] = {
        maybeKey = None
        currentElement = None
        super.onStart()
      }
    }))

  def maybeReverseAnonFlow: Flow[DicomPart, DicomPart, NotUsed] = conditionalFlow(
    {
      case keyPart: PartialAnonymizationKeyPart => keyPart.keyMaybe.isEmpty
    }, Flow.fromFunction(identity), reverseAnonFlow)

}








