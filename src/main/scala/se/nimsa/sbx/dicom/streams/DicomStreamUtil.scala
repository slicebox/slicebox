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
import akka.stream.FlowShape
import akka.stream.scaladsl.{Broadcast, Flow, GraphDSL, Merge}
import se.nimsa.dicom.data.DicomParts.{DicomPart, MetaPart}
import se.nimsa.dicom.data.TagPath.TagPathTag
import se.nimsa.dicom.data._
import se.nimsa.sbx.anonymization.AnonymizationProtocol.{AnonymizationKeyOpResult, TagValue}
import se.nimsa.sbx.dicom.DicomHierarchy.DicomHierarchyLevel

object DicomStreamUtil {

  case class AnonymizationKeyOpResultPart(result: AnonymizationKeyOpResult) extends MetaPart

  val encodingTags: Set[TagPath] = Set(Tag.TransferSyntaxUID, Tag.SpecificCharacterSet).map(TagPath.fromTag)

  val tagsToStoreInDB: Set[TagPath] = {
    val patientTags = Set(Tag.PatientName, Tag.PatientID, Tag.PatientSex, Tag.PatientBirthDate).map(TagPath.fromTag)
    val studyTags = Set(Tag.StudyInstanceUID, Tag.StudyDescription, Tag.StudyID, Tag.StudyDate, Tag.AccessionNumber, Tag.PatientAge).map(TagPath.fromTag)
    val seriesTags = Set(Tag.SeriesInstanceUID, Tag.SeriesDescription, Tag.SeriesDate, Tag.Modality, Tag.ProtocolName, Tag.BodyPartExamined, Tag.Manufacturer, Tag.StationName, Tag.FrameOfReferenceUID).map(TagPath.fromTag)
    val imageTags = Set(Tag.SOPInstanceUID, Tag.ImageType, Tag.InstanceNumber).map(TagPath.fromTag)

    (patientTags ++ studyTags ++ seriesTags ++ imageTags).map(_.asInstanceOf[TagPath])
  }

  val anonymizationTags: Set[TagPath] = Set(Tag.PatientIdentityRemoved, Tag.DeidentificationMethod).map(TagPath.fromTag)

  val anonKeysTags: Set[TagPath] = Set(Tag.PatientName, Tag.PatientID, Tag.StudyInstanceUID, Tag.SeriesInstanceUID, Tag.SOPInstanceUID).map(TagPath.fromTag)

  val imageInformationTags: Set[TagPath] = Set(Tag.InstanceNumber, Tag.ImageIndex, Tag.NumberOfFrames, Tag.SmallestImagePixelValue, Tag.LargestImagePixelValue).map(TagPath.fromTag)

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

  val optionalValueTags: Set[TagLevel] = Set(
    TagLevel(TagPath.fromTag(Tag.PatientBirthDate), DicomHierarchyLevel.PATIENT),
    TagLevel(TagPath.fromTag(Tag.StudyDescription), DicomHierarchyLevel.STUDY),
    TagLevel(TagPath.fromTag(Tag.StudyID), DicomHierarchyLevel.STUDY),
    TagLevel(TagPath.fromTag(Tag.AccessionNumber), DicomHierarchyLevel.STUDY),
    TagLevel(TagPath.fromTag(Tag.FrameOfReferenceUID), DicomHierarchyLevel.STUDY),
    TagLevel(TagPath.fromTag(Tag.SeriesDescription), DicomHierarchyLevel.SERIES),
    TagLevel(TagPath.fromTag(Tag.ProtocolName), DicomHierarchyLevel.SERIES),
  )

  val valueTags: Set[TagLevel] = mandatoryValueTags ++ optionalValueTags

  val identityFlow: Flow[DicomPart, DicomPart, NotUsed] = Flow[DicomPart]

  def isAnonymous(elements: Elements): Boolean = elements.getString(Tag.PatientIdentityRemoved).exists(_.toUpperCase == "YES")

  def elementsContainTagValues(elements: Elements, tagValues: Seq[TagValue]): Boolean = tagValues
    .forall(tv => tv.value.isEmpty || elements.getString(tv.tagPath).forall(_ == tv.value))

  def conditionalFlow(goA: PartialFunction[DicomPart, Boolean], flowA: Flow[DicomPart, DicomPart, _], flowB: Flow[DicomPart, DicomPart, _], routeADefault: Boolean = true): Flow[DicomPart, DicomPart, NotUsed] =
    Flow.fromGraph(GraphDSL.create() { implicit builder =>
      import GraphDSL.Implicits._

      var routeA = routeADefault // determines which path is taken in the graph

      // if any part indicates that alternate route should be taken, remember this
      val gateFlow = builder.add {
        identityFlow
          .map { part =>
            if (goA.isDefinedAt(part)) routeA = goA(part)
            part
          }
      }

      // split the flow
      val bcast = builder.add(Broadcast[DicomPart](2))

      // define gates for each path, only one path is used
      val gateA = identityFlow.filter(_ => routeA)
      val gateB = identityFlow.filterNot(_ => routeA)

      // merge the two paths
      val merge = builder.add(Merge[DicomPart](2))

      // surround each flow by gates, remember that flows may produce items without input
      gateFlow ~> bcast ~> gateA ~> flowA ~> gateA ~> merge
      bcast ~> gateB ~> flowB ~> gateB ~> merge

      FlowShape(gateFlow.in, merge.out)
    })

}
