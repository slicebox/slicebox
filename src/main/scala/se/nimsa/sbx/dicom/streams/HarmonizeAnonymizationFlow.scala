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
import se.nimsa.dicom.data.DicomParts.{DicomPart, HeaderPart, ValueChunk}
import se.nimsa.dicom.data.Elements.ValueElement
import se.nimsa.dicom.data.{DicomParts, Tag, Value}
import se.nimsa.dicom.streams.DicomFlows._
import se.nimsa.dicom.streams._
import se.nimsa.sbx.dicom.streams.DicomStreamUtil._

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

  def harmonizeAnonFlow: Flow[DicomPart, DicomPart, NotUsed] = Flow[DicomPart]
    .via(groupLengthDiscardFilter)
    .via(toIndeterminateLengthSequences)
    .via(toUtf8Flow)
    .via(DicomFlowFactory.create(new IdentityFlow with GuaranteedValueEvent[DicomPart] with StartEvent[DicomPart] with InSequence[DicomPart] {
      var maybeKey: Option[PartialAnonymizationKeyPart] = None
      var currentAttribute: Option[ValueElement] = None

      def maybeHarmonize(element: ValueElement, keyPart: PartialAnonymizationKeyPart): List[DicomPart] = {
        val updatedAttribute = element.tag match {
          case Tag.PatientName => keyPart.keyMaybe
            .map(key => element.setValue(Value(ByteString(key.anonPatientName))))
            .getOrElse(element)
          case Tag.PatientID => keyPart.keyMaybe
            .map(key => element.setValue(Value(ByteString(key.anonPatientID))))
            .getOrElse(element)
          case Tag.StudyInstanceUID => keyPart.keyMaybe
            .map(key => element.setValue(Value(ByteString(key.anonStudyInstanceUID))))
            .getOrElse(element)
          case Tag.SeriesInstanceUID => keyPart.keyMaybe
            .map(key => element.setValue(Value(ByteString(key.anonSeriesInstanceUID))))
            .getOrElse(element)
          case Tag.FrameOfReferenceUID => keyPart.keyMaybe
            .map(key => element.setValue(Value(ByteString(key.anonFrameOfReferenceUID))))
            .getOrElse(element)
          case _ => element
        }
        updatedAttribute.toParts
      }
      /*
       * do harmonize anon if:
       * - anomymization keys have been received in stream
       * - tag specifies attribute that needs to be harmonized
       */
      def needHarmonizeAnon(tag: Int, keyPart: Option[PartialAnonymizationKeyPart]): Boolean = canDoHarmonizeAnon(keyPart) && harmonizeTags.contains(tag)

      def canDoHarmonizeAnon(keyPartMaybe: Option[PartialAnonymizationKeyPart]): Boolean = keyPartMaybe.flatMap(_.keyMaybe).isDefined

      override def onPart(part: DicomPart): List[DicomPart] = part match {
        case keyPart: PartialAnonymizationKeyPart =>
          maybeKey = Some(keyPart)
          super.onPart(keyPart)
        case p => super.onPart(p)
      }

      override def onHeader(header: HeaderPart): List[DicomPart] =
        if (!inSequence && needHarmonizeAnon(header.tag, maybeKey)) {
          currentAttribute = Some(ValueElement.empty(header.tag, header.vr, header.bigEndian, header.explicitVR))
          super.onHeader(header).filterNot(_ == header)
        } else {
          currentAttribute = None
          super.onHeader(header)
        }

      override def onValueChunk(chunk: ValueChunk): List[DicomPart] =
        if (currentAttribute.isDefined && canDoHarmonizeAnon(maybeKey)) {
          currentAttribute = currentAttribute.map(attribute => attribute.copy(value = attribute.value ++ chunk.bytes))

          if (chunk.last) {
            val attribute = currentAttribute.get
            val key = maybeKey.get

            currentAttribute = None
            maybeHarmonize(attribute, key)
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

  def maybeHarmonizeAnonFlow: Flow[DicomPart, DicomPart, NotUsed] = conditionalFlow(
    {
      case keyPart: PartialAnonymizationKeyPart => keyPart.keyMaybe.isEmpty
    }, Flow.fromFunction(identity), harmonizeAnonFlow)

}








