package se.nimsa.sbx.anonymization

import se.nimsa.sbx.anonymization.AnonymizationProfile.TagMask

case class AnonymizationProfile(options: Seq[ConfidentialityOption]) {

  import ConfidentialityOption._

  def toOps: Map[TagMask, AnonymizationOp] = options
    .sortWith(_.rank < _.rank)
    .foldLeft(Map.empty[TagMask, AnonymizationOp])((map, op) => map ++ AnonymizationProfiles.profiles(op))

  def safePrivate: Seq[TagMask] = if (options.contains(RETAIN_SAFE_PRIVATE)) AnonymizationProfiles.safePrivateAttributes else Seq.empty
}

object AnonymizationProfile {

  case class TagMask(tag: Int, mask: Int) {
    def contains(otherTag: Int): Boolean = (otherTag & mask) == tag
  }

}