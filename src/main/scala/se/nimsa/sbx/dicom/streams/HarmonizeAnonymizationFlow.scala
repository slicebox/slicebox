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
import se.nimsa.dicom.Tag
import se.nimsa.dicom.streams.CollectFlow.CollectedElement
import se.nimsa.dicom.streams.DicomFlows._
import se.nimsa.dicom.streams.DicomParts.{DicomHeader, DicomPart, DicomValueChunk}
import se.nimsa.dicom.streams.{DicomFlowFactory, GuaranteedValueEvent, IdentityFlow, StartEvent}
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
    .via(toUndefinedLengthSequences)
    .via(toUtf8Flow)
    .via(DicomFlowFactory.create(new IdentityFlow with GuaranteedValueEvent[DicomPart] with StartEvent[DicomPart] {
      var maybeKey: Option[PartialAnonymizationKeyPart] = None
      var currentAttribute: Option[CollectedElement] = None

      def maybeHarmonize(attribute: CollectedElement, keyPart: PartialAnonymizationKeyPart): List[DicomPart] = {
        val updatedAttribute = attribute.header.tag match {
          case Tag.PatientName => keyPart.keyMaybe
            .map(key => attribute.withUpdatedValue(ByteString(key.anonPatientName)))
            .getOrElse(attribute)
          case Tag.PatientID => keyPart.keyMaybe
            .map(key => attribute.withUpdatedValue(ByteString(key.anonPatientID)))
            .getOrElse(attribute)
          case Tag.StudyInstanceUID => keyPart.keyMaybe
            .map(key => attribute.withUpdatedValue(ByteString(key.anonStudyInstanceUID)))
            .getOrElse(attribute)
          case Tag.SeriesInstanceUID => keyPart.keyMaybe
            .map(key => attribute.withUpdatedValue(ByteString(key.anonSeriesInstanceUID)))
            .getOrElse(attribute)
          case Tag.FrameOfReferenceUID => keyPart.keyMaybe
            .map(key => attribute.withUpdatedValue(ByteString(key.anonFrameOfReferenceUID)))
            .getOrElse(attribute)
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
        case keyPart: PartialAnonymizationKeyPart =>
          maybeKey = Some(keyPart)
          super.onPart(keyPart)
        case p => super.onPart(p)
      }

      override def onHeader(header: DicomHeader): List[DicomPart] =
        if (needHarmonizeAnon(header.tag, maybeKey)) {
          currentAttribute = Some(CollectedElement(header, Seq.empty))
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

            currentAttribute = None
            maybeHarmonize(attribute, key)
          } else
            super.onValueChunk(valueChunk).filterNot(_ == valueChunk)
        } else
          super.onValueChunk(valueChunk)

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








