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
import se.nimsa.dicom.streams.ModifyFlow.{TagModification, TagModificationsPart, modifyFlow}
import se.nimsa.sbx.dicom.streams.DicomStreamUtil._

/**
  * A flow which performs reverse anonymization as soon as it has received an AnonymizationKeyPart (which means data is
  * anonymized)
  */
object ReverseAnonymizationFlow {

  def reverseAnonFlow: Flow[DicomPart, DicomPart, NotUsed] =
    conditionalFlow({ case vp: AnonymizationKeyQueryResultPart => !vp.anonymizationKeyValues.isEmpty },
      identityFlow
        .mapConcat {
          case vp: AnonymizationKeyQueryResultPart =>
            val v = vp.anonymizationKeyValues
            val active = valueTags
              .filterNot(_.level > v.matchLevel)
              .map(_.tagPath)
              .flatMap(tp => v.values.find(_.tagPath == tp))
            val mods = active.map(tv => TagModification.contains(tv.tagPath, _ => ByteString(tv.anonymizedValue), insert = true))
            TagModificationsPart(mods.toList) :: Nil
          case p => p :: Nil
        }
        .via(modifyFlow()),
      identityFlow
    )
}







