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
import se.nimsa.dicom.data.DicomParts.DicomPart
import se.nimsa.dicom.data.padToEvenLength
import se.nimsa.dicom.streams.ModifyFlow.{TagInsertion, TagModificationsPart, modifyFlow}
import se.nimsa.sbx.anonymization.AnonymizationProtocol.{AnonymizationKeyValue, TagValue}
import se.nimsa.sbx.dicom.SliceboxTags._
import se.nimsa.sbx.dicom.streams.DicomStreamUtil._

/**
  * A flow which harmonizes anonymization so that random attributes correspond between patients, studies and series
  */
object HarmonizeAnonymizationFlow {

  def harmonizeAnonFlow(customAnonValues: Seq[TagValue]): Flow[DicomPart, DicomPart, NotUsed] = identityFlow
    .map {
      case rp: AnonymizationKeyOpResultPart =>
        val r = rp.result
        val active = valueTags
          .filterNot(_.level > r.matchLevel)
          .map(_.tagPath)
          .flatMap(tp => r.values.find(_.tagPath == tp))
        val custom = customAnonValues.map(v => AnonymizationKeyValue(-1, -1, v.tagPath, "", v.value))
        val combined = active.foldLeft(custom)((m, tv) => if (m.map(_.tagPath).contains(tv.tagPath)) m else m :+ tv)
        val insertions = combined.map(tv => TagInsertion(tv.tagPath, _ => padToEvenLength(ByteString(tv.anonymizedValue), tv.tagPath.tag)))
        TagModificationsPart(Seq.empty, insertions.toList)
      case p => p
    }
    .via(modifyFlow())

}








