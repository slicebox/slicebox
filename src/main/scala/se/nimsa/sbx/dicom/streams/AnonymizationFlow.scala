package se.nimsa.sbx.dicom.streams

import akka.NotUsed
import akka.stream.scaladsl.Flow
import akka.util.ByteString
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

  val insertTags: Seq[TagPath] = Seq(Tag.DeidentificationMethod, Tag.PatientIdentityRemoved,
    Tag.PatientID, Tag.PatientName,
    Tag.SeriesInstanceUID, Tag.SOPInstanceUID, Tag.StudyInstanceUID).map(TagPath.fromTag)

  private def containsAnyTag(tagPath: TagPath): TagMask => Boolean = tagMask => tagPath.toList.map(_.tag).exists(tagMask.contains)
  private def containsTag(tagPath: TagPath): TagMask => Boolean = _.contains(tagPath.tag)

  def anonFlow: Flow[DicomPart, DicomPart, NotUsed] = {
    tagFilter(_ => true) { tagPath =>
      insertTags.contains(tagPath) ||
      !profile.opOf(containsAnyTag(tagPath)).exists {
        case REMOVE => true
        case REMOVE_OR_ZERO => true // always remove (limitation)
        case REMOVE_OR_DUMMY => true // always remove (limitation)
        case REMOVE_OR_ZERO_OR_DUMMY => true // always remove (limitation)
        case REMOVE_OR_ZERO_OR_REPLACE_UID => true // always remove (limitation)
        case _ => false
      }
    }
      .via(modifyFlow(
        Seq(
          TagModification(tagPath =>
            !insertTags.contains(tagPath) && profile.opOf(containsTag(tagPath)).contains(REPLACE_UID), _ => createUid()),
          TagModification(tagPath =>
            !insertTags.contains(tagPath) && profile.opOf(containsTag(tagPath)).exists {
              case DUMMY => true // zero instead of replace with dummy (limitation)
              case CLEAN => true // zero instead of replace with cleaned value (limitation)
              case ZERO => true
              case ZERO_OR_DUMMY => true // always zero (limitation)
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
