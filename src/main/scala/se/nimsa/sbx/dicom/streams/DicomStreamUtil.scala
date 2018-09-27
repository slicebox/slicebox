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
import akka.util.ByteString
import se.nimsa.dicom.data.DicomParts.{DicomPart, ElementsPart, HeaderPart}
import se.nimsa.dicom.data._
import se.nimsa.sbx.anonymization.AnonymizationProtocol.{AnonymizationKey, AnonymizationKeyValues}
import se.nimsa.sbx.dicom.DicomHierarchy.DicomHierarchyLevel

import scala.concurrent.{ExecutionContext, Future}

object DicomStreamUtil {

  val encodingTags: Set[TagPath] = Set(Tag.TransferSyntaxUID, Tag.SpecificCharacterSet).map(TagPath.fromTag)

  val tagsToStoreInDB: Set[TagPath] = {
    val patientTags = Seq(Tag.PatientName, Tag.PatientID, Tag.PatientSex, Tag.PatientBirthDate).map(TagPath.fromTag)
    val studyTags = Seq(Tag.StudyInstanceUID, Tag.StudyDescription, Tag.StudyID, Tag.StudyDate, Tag.AccessionNumber, Tag.PatientAge).map(TagPath.fromTag)
    val seriesTags = Seq(Tag.SeriesInstanceUID, Tag.SeriesDescription, Tag.SeriesDate, Tag.Modality, Tag.ProtocolName, Tag.BodyPartExamined, Tag.Manufacturer, Tag.StationName, Tag.FrameOfReferenceUID).map(TagPath.fromTag)
    val imageTags = Seq(Tag.SOPInstanceUID, Tag.ImageType, Tag.InstanceNumber).map(TagPath.fromTag)

    encodingTags ++ patientTags ++ studyTags ++ seriesTags ++ imageTags
  }

  val basicInfoTags: Set[TagPath] = encodingTags ++ Set(Tag.PatientName, Tag.PatientID, Tag.PatientIdentityRemoved,
    Tag.StudyInstanceUID, Tag.SeriesInstanceUID, Tag.SOPInstanceUID).map(TagPath.fromTag)

  val extendedInfoTags: Set[TagPath] = basicInfoTags ++ Set(Tag.PatientSex, Tag.PatientAge).map(TagPath.fromTag)

  val imageInformationTags: Set[TagPath] = Set(Tag.InstanceNumber, Tag.ImageIndex, Tag.NumberOfFrames, Tag.SmallestImagePixelValue, Tag.LargestImagePixelValue).map(TagPath.fromTag)

  case class TagLevel(tag: Int, level: DicomHierarchyLevel)

  val reverseTags = Seq(
    TagLevel(Tag.PatientName, DicomHierarchyLevel.PATIENT),
    TagLevel(Tag.PatientID, DicomHierarchyLevel.PATIENT),
    TagLevel(Tag.PatientBirthDate, DicomHierarchyLevel.PATIENT),
    TagLevel(Tag.PatientIdentityRemoved, DicomHierarchyLevel.IMAGE),
    TagLevel(Tag.DeidentificationMethod, DicomHierarchyLevel.IMAGE),
    TagLevel(Tag.StudyInstanceUID, DicomHierarchyLevel.STUDY),
    TagLevel(Tag.StudyDescription, DicomHierarchyLevel.STUDY),
    TagLevel(Tag.StudyID, DicomHierarchyLevel.STUDY),
    TagLevel(Tag.AccessionNumber, DicomHierarchyLevel.STUDY),
    TagLevel(Tag.SeriesInstanceUID, DicomHierarchyLevel.SERIES),
    TagLevel(Tag.SeriesDescription, DicomHierarchyLevel.SERIES),
    TagLevel(Tag.ProtocolName, DicomHierarchyLevel.SERIES),
    TagLevel(Tag.FrameOfReferenceUID, DicomHierarchyLevel.STUDY))

  val reverseTag: HeaderPart => Boolean = header => header.vr == VR.UI || reverseTags.contains(header.tag)


  case class AnonymizationKeyValuesPart(anonymizationKeyValues: AnonymizationKeyValues) extends DicomPart {
    def bytes: ByteString = ByteString.empty
    def bigEndian: Boolean = false
  }

  def createAnonKeyFlow(createAnonKey: ElementsPart => Future[AnonymizationKey])
                       (implicit ec: ExecutionContext): DicomPart => Future[DicomPart] = {
    case p: ElementsPart if isAnonymous(p) => Future.successful(p)
    case p: ElementsPart => createAnonKey(p).map(_ => p)
    case p: DicomPart => Future.successful(p)
  }

  def isAnonymous(elementsPart: ElementsPart): Boolean = elementsPart.elements
    .getString(Tag.PatientIdentityRemoved)
    .exists(_.toUpperCase == "YES")

  def conditionalFlow(goA: PartialFunction[DicomPart, Boolean], flowA: Flow[DicomPart, DicomPart, _], flowB: Flow[DicomPart, DicomPart, _], routeADefault: Boolean = true): Flow[DicomPart, DicomPart, NotUsed] =
    Flow.fromGraph(GraphDSL.create() { implicit builder =>
      import GraphDSL.Implicits._

      var routeA = routeADefault // determines which path is taken in the graph

      // if any part indicates that alternate route should be taken, remember this
      val gateFlow = builder.add {
        Flow[DicomPart].map { part =>
          if (goA.isDefinedAt(part)) routeA = goA(part)
          part
        }
      }

      // split the flow
      val bcast = builder.add(Broadcast[DicomPart](2))

      // define gates for each path, only one path is used
      val gateA = Flow[DicomPart].filter(_ => routeA)
      val gateB = Flow[DicomPart].filterNot(_ => routeA)

      // merge the two paths
      val merge = builder.add(Merge[DicomPart](2))

      // surround each flow by gates, remember that flows may produce items without input
      gateFlow ~> bcast ~> gateA ~> flowA ~> gateA ~> merge
      bcast ~> gateB ~> flowB ~> gateB ~> merge

      FlowShape(gateFlow.in, merge.out)
    })

}
