package se.nimsa.sbx.dicom

import com.typesafe.config.ConfigFactory
import se.nimsa.dicom.data.TagPath.TagPathTag
import se.nimsa.dicom.data.{Tag, TagPath}
import se.nimsa.sbx.dicom.DicomHierarchy.DicomHierarchyLevel

import scala.collection.JavaConverters._

object SliceboxTags {

  val encodingTags: Set[Int] = Set(Tag.TransferSyntaxUID, Tag.SpecificCharacterSet)

  val tagsToStoreInDB: Set[Int] = {
    val patientTags = Set(Tag.PatientName, Tag.PatientID, Tag.PatientSex, Tag.PatientBirthDate)
    val studyTags = Set(Tag.StudyInstanceUID, Tag.StudyDescription, Tag.StudyID, Tag.StudyDate, Tag.AccessionNumber, Tag.PatientAge)
    val seriesTags = Set(Tag.SeriesInstanceUID, Tag.SeriesDescription, Tag.SeriesDate, Tag.Modality, Tag.ProtocolName, Tag.BodyPartExamined, Tag.Manufacturer, Tag.StationName, Tag.FrameOfReferenceUID)
    val imageTags = Set(Tag.SOPInstanceUID, Tag.ImageType, Tag.InstanceNumber)

    patientTags ++ studyTags ++ seriesTags ++ imageTags
  }

  val anonymizationTags: Set[Int] = Set(Tag.PatientIdentityRemoved, Tag.DeidentificationMethod)

  val anonKeysTags: Set[Int] = Set(Tag.PatientName, Tag.PatientID, Tag.StudyInstanceUID, Tag.SeriesInstanceUID, Tag.SOPInstanceUID)

  val imageInformationTags: Set[Int] = Set(Tag.InstanceNumber, Tag.ImageIndex, Tag.NumberOfFrames, Tag.SmallestImagePixelValue, Tag.LargestImagePixelValue)

  case class TagLevel(tagPath: TagPathTag, level: DicomHierarchyLevel)

  val mandatoryValueTags: Set[TagLevel] = Set(
    TagLevel(TagPath.fromTag(Tag.PatientName), DicomHierarchyLevel.PATIENT),
    TagLevel(TagPath.fromTag(Tag.PatientID), DicomHierarchyLevel.PATIENT),
    TagLevel(TagPath.fromTag(Tag.StudyInstanceUID), DicomHierarchyLevel.STUDY),
    TagLevel(TagPath.fromTag(Tag.SeriesInstanceUID), DicomHierarchyLevel.SERIES),
    TagLevel(TagPath.fromTag(Tag.SOPInstanceUID), DicomHierarchyLevel.IMAGE),
    TagLevel(TagPath.fromTag(Tag.PatientIdentityRemoved), DicomHierarchyLevel.IMAGE),
    TagLevel(TagPath.fromTag(Tag.DeidentificationMethod), DicomHierarchyLevel.IMAGE)
  )

  val optionalValueTags: Set[TagLevel] =
    ConfigFactory.load()
      .getConfigList("slicebox.anonymization.key-attributes").asScala
      .map(c => TagLevel(TagPathTag.parse(c.getString("tag-path")), DicomHierarchyLevel.withName(c.getString("level"))))
      .toSet

  val valueTags: Set[TagLevel] = mandatoryValueTags ++ optionalValueTags

}
