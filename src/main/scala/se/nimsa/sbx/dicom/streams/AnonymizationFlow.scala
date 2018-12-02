package se.nimsa.sbx.dicom.streams

import akka.NotUsed
import akka.stream.scaladsl.Flow
import akka.util.ByteString
import se.nimsa.dicom.data.DicomParsing.isPrivate
import se.nimsa.dicom.data.DicomParts.DicomPart
import se.nimsa.dicom.data.{Tag, TagPath, VR}
import se.nimsa.dicom.streams.DicomFlows.tagFilter
import se.nimsa.dicom.streams.ModifyFlow.{TagInsertion, TagModification, modifyFlow}
import se.nimsa.sbx.anonymization.AnonymizationProfile.TagMask
import se.nimsa.sbx.anonymization.AnonymizationUtil._
import se.nimsa.sbx.anonymization.{AnonymizationOp, AnonymizationProfile}
import se.nimsa.sbx.dicom.DicomUtil.toAsciiBytes

class AnonymizationFlow(profile: AnonymizationProfile) {

  import AnonymizationOp._

  val insertTags: Seq[Int] = Seq(Tag.DeidentificationMethod, Tag.PatientIdentityRemoved,
    Tag.PatientID, Tag.PatientName,
    Tag.SeriesInstanceUID, Tag.SOPInstanceUID, Tag.StudyInstanceUID)

  val ops: Map[TagMask, AnonymizationOp] = profile.toOps
    .filterNot { case (tagMask, _) => insertTags.exists(tagMask.contains) } // make sure structure tag are inserted, not modified

  val safePrivate: Seq[TagMask] = profile.safePrivate

  val remove: List[TagMask] = ops
    .filter {
      case (tagMask, _) if isPrivate(tagMask.tag) => true // remove all private unless safe
      case (_, REMOVE) => true
      case (_, REMOVE_OR_ZERO) => true // always remove (limitation)
      case (_, REMOVE_OR_DUMMY) => true // always remove (limitation)
      case (_, REMOVE_OR_ZERO_OR_DUMMY) => true // always remove (limitation)
      case (_, REMOVE_OR_ZERO_OR_REPLACE_UID) => true // always remove (limitation)
      case _ => false
    }
    .keys.toList

  val modifications: Map[TagMask, AnonymizationOp] = ops
    .filter {
      case (_, DUMMY) => true
      case (_, CLEAN) => true
      case (_, ZERO) => true
      case (_, ZERO_OR_DUMMY) => true
      case (_, REPLACE_UID) => true
      case _ => false
    }

  def isSafePrivate(tag: Int): Boolean = safePrivate.exists(_.contains(tag))

  def anonFlow: Flow[DicomPart, DicomPart, NotUsed] = {
    tagFilter(_ => true) { tagPath =>
      val tags = tagPath.toList.map(_.tag)
      tags.exists(isSafePrivate) || !tags.exists(tag => remove.exists(_.contains(tag)))
    }
      .via(modifyFlow(
        Seq(
          TagModification(tagPath =>
            !isSafePrivate(tagPath.tag) && modifications.exists {
              case (tagMask, REPLACE_UID) => tagMask.contains(tagPath.tag)
              case _ => false
            }, _ => createUid()
          ),
          TagModification(tagPath =>
            !isSafePrivate(tagPath.tag) && modifications.filter(_._1.contains(tagPath.tag)).exists {
              case (_, DUMMY) => true // zero instead of replace with dummy (limitation)
              case (_, CLEAN) => true // zero instead of replace with cleaned value (limitation)
              case (_, ZERO) => true
              case (_, ZERO_OR_DUMMY) => true // always zero (limitation)
              case _ => false
            }, _ => ByteString.empty
          )
        ),
        Seq( // insert structure tags (slicebox-specific deviation from standard)
          TagInsertion(TagPath.fromTag(Tag.DeidentificationMethod), toAsciiBytes("Retain Longitudinal Full Dates Option", VR.LO)),
          TagInsertion(TagPath.fromTag(Tag.PatientIdentityRemoved), toAsciiBytes("YES", VR.CS)),
          TagInsertion(TagPath.fromTag(Tag.PatientID), createUid()),
          TagInsertion(TagPath.fromTag(Tag.PatientName), createUid()),
          TagInsertion(TagPath.fromTag(Tag.SeriesInstanceUID), createUid()),
          TagInsertion(TagPath.fromTag(Tag.SOPInstanceUID), createUid()),
          TagInsertion(TagPath.fromTag(Tag.StudyInstanceUID), createUid())
        )))
  }
}
