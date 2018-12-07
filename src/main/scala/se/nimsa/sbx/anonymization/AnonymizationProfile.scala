package se.nimsa.sbx.anonymization

import se.nimsa.sbx.anonymization.AnonymizationOp._
import se.nimsa.sbx.anonymization.AnonymizationProfile._
import se.nimsa.sbx.anonymization.AnonymizationProfiles._
import se.nimsa.sbx.anonymization.ConfidentialityOption._

case class AnonymizationProfile(options: Seq[ConfidentialityOption]) {

  private lazy val activeOps: Map[ConfidentialityOption, Map[TagMask, AnonymizationOp]] =
    profiles.filterKeys(options.contains) ++ (
      if (options.contains(RETAIN_SAFE_PRIVATE))
        Map(RETAIN_SAFE_PRIVATE -> safePrivateAttributes.map(_ -> KEEP).toMap)
      else
        Map.empty
      )

  private lazy val sortedKeys = activeOps.keys.toList.sortWith(_.rank > _.rank)

  def opOf(f: TagMask => Boolean): Option[AnonymizationOp] = {
    var op: Option[AnonymizationOp] = None
    for (key <- sortedKeys if op.isEmpty) {
      val map = activeOps(key)
      op = map.filterKeys(f).values.headOption
    }
    op
  }

}

object AnonymizationProfile {

  case class TagMask(tag: Int, mask: Int) {
    def contains(otherTag: Int): Boolean = (otherTag & mask) == tag
  }

}