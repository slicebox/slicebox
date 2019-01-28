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

package se.nimsa.sbx.anonymization

import se.nimsa.sbx.anonymization.AnonymizationOp._
import se.nimsa.sbx.anonymization.AnonymizationProfile._
import se.nimsa.sbx.anonymization.AnonymizationProfiles._
import se.nimsa.sbx.anonymization.ConfidentialityOption._

import scala.collection.mutable

class AnonymizationProfile private(val options: Seq[ConfidentialityOption]) {

  private lazy val sortedOptions = options.sortWith(_.rank > _.rank)

  /*
  A bit of optimization necessary for sufficient performance. Divide ops into one map for the majority of tags where the
  tag mask has all bits set. This is equivalent to checking for simple tag equality. Keep a separate map for the minority
  with tag masks spanning more than one tag. Lookups in the former map will be fast since this can use a standard map
  lookup. In the second map, linear search has to be performed but this is also fast as there are very few such elements.
  Once both lookups have been carried out, select the one with highest rank.
   */
  private lazy val (activeTagOps, activeMaskedOps): (Map[ConfidentialityOption, Map[Int, AnonymizationOp]], Map[ConfidentialityOption, Map[TagMask, AnonymizationOp]]) = {
    val activeOps: Map[ConfidentialityOption, Map[TagMask, AnonymizationOp]] =
      profiles.filterKeys(options.contains) ++ (
        if (options.contains(RETAIN_SAFE_PRIVATE))
          Map(RETAIN_SAFE_PRIVATE -> safePrivateAttributes.map(_ -> KEEP).toMap)
        else
          Map.empty
        )

    val tagMap = activeOps.map {
      case (option, inner) => option -> inner.filterKeys(_.mask == 0xFFFFFFFF).map {
        case (mask, op) => mask.tag -> op
      }
    }

    val maskMap = activeOps.map {
      case (option, inner) => option -> inner.filterKeys(_.mask != 0xFFFFFFFF)
    }

    (tagMap, maskMap)
  }

  def opOf(tag: Int): Option[AnonymizationOp] = {
    var tagOp: Option[(ConfidentialityOption, AnonymizationOp)] = None
    for (key <- sortedOptions if tagOp.isEmpty) {
      val map = activeTagOps(key)
      tagOp = map.get(tag).map(key -> _)
    }

    var maskOp: Option[(ConfidentialityOption, AnonymizationOp)] = None
    for (key <- sortedOptions if maskOp.isEmpty) {
      val map = activeMaskedOps(key)
      maskOp = map.filterKeys(_.contains(tag)).values.headOption.map(key -> _)
    }

    (tagOp, maskOp) match {
      case (Some(t), Some(m)) if t._1.rank > m._1.rank => Some(t._2)
      case (Some(_), Some(m)) => Some(m._2)
      case (None, Some(m)) => Some(m._2)
      case (Some(t), None) => Some(t._2)
      case _ => None
    }
  }

}

object AnonymizationProfile {

  private val cache = mutable.Map.empty[Seq[ConfidentialityOption], AnonymizationProfile]

  def apply(options: Seq[ConfidentialityOption]): AnonymizationProfile =
    cache.getOrElseUpdate(options, new AnonymizationProfile(options))

  case class TagMask(tag: Int, mask: Int) {
    def contains(otherTag: Int): Boolean = (otherTag & mask) == tag
  }

}