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

import java.time.ZoneOffset

import akka.NotUsed
import akka.stream.FlowShape
import akka.stream.scaladsl.{Broadcast, Flow, GraphDSL, Merge}
import akka.util.ByteString
import se.nimsa.dicom.data.DicomParts.{DicomPart, ElementsPart}
import se.nimsa.dicom.data._
import se.nimsa.sbx.anonymization.AnonymizationProtocol.AnonymizationKey

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
    Tag.StudyInstanceUID, Tag.SeriesInstanceUID, Tag.FrameOfReferenceUID).map(TagPath.fromTag)

  val extendedInfoTags: Set[TagPath] = basicInfoTags ++ Set(Tag.PatientSex, Tag.PatientBirthDate, Tag.PatientAge, Tag.StudyDescription, Tag.StudyID,
    Tag.AccessionNumber, Tag.SeriesDescription, Tag.ProtocolName, Tag.FrameOfReferenceUID).map(TagPath.fromTag)

  val imageInformationTags: Set[TagPath] = Set(Tag.InstanceNumber, Tag.ImageIndex, Tag.NumberOfFrames, Tag.SmallestImagePixelValue, Tag.LargestImagePixelValue).map(TagPath.fromTag)

  case class PartialAnonymizationKeyPart(keyMaybe: Option[AnonymizationKey], hasPatientInfo: Boolean, hasStudyInfo: Boolean, hasSeriesInfo: Boolean, hasFrameOfReferenceInfo: Boolean) extends DicomPart {
    def bytes: ByteString = ByteString.empty
    def bigEndian: Boolean = false
  }

  case class DicomInfoPart(characterSets: CharacterSets,
                           zoneOffset: ZoneOffset,
                           transferSyntaxUid: Option[String],
                           patientID: Option[String],
                           patientName: Option[String],
                           patientSex: Option[String],
                           patientBirthDate: Option[String],
                           patientAge: Option[String],
                           identityRemoved: Option[String],
                           studyInstanceUID: Option[String],
                           studyDescription: Option[String],
                           studyID: Option[String],
                           accessionNumber: Option[String],
                           seriesInstanceUID: Option[String],
                           seriesDescription: Option[String],
                           protocolName: Option[String],
                           frameOfReferenceUID: Option[String]) extends DicomPart {
    def bytes: ByteString = ByteString.empty
    def bigEndian: Boolean = false
    def isAnonymized: Boolean = identityRemoved.exists(_.toUpperCase == "YES")
  }

  def attributesToInfoPart(dicomPart: DicomPart, label: String): DicomPart = {
    dicomPart match {
      case ep: ElementsPart if ep.label == label =>
        DicomInfoPart(
          ep.elements.characterSets,
          ep.elements.zoneOffset,
          ep.elements.getString(Tag.TransferSyntaxUID),
          ep.elements.getString(Tag.PatientID),
          ep.elements.getString(Tag.PatientName),
          ep.elements.getString(Tag.PatientSex),
          ep.elements.getString(Tag.PatientBirthDate),
          ep.elements.getString(Tag.PatientAge),
          ep.elements.getString(Tag.PatientIdentityRemoved),
          ep.elements.getString(Tag.StudyInstanceUID),
          ep.elements.getString(Tag.StudyDescription),
          ep.elements.getString(Tag.StudyID),
          ep.elements.getString(Tag.AccessionNumber),
          ep.elements.getString(Tag.SeriesInstanceUID),
          ep.elements.getString(Tag.SeriesDescription),
          ep.elements.getString(Tag.ProtocolName),
          ep.elements.getString(Tag.FrameOfReferenceUID)
        )
      case part: DicomPart => part
    }
  }

  def getOrCreateAnonKeyPart(getOrCreateAnonKey: DicomInfoPart => Future[AnonymizationKey])
                                             (implicit ec: ExecutionContext): DicomPart => Future[DicomPart] = {
    case info: DicomInfoPart if info.isAnonymized =>
      Future.successful(PartialAnonymizationKeyPart(None, hasPatientInfo = false, hasStudyInfo = false, hasSeriesInfo = false, hasFrameOfReferenceInfo = false))
    case info: DicomInfoPart =>
      getOrCreateAnonKey(info).map(key => PartialAnonymizationKeyPart(Some(key), hasPatientInfo = true, hasStudyInfo = true, hasSeriesInfo = true, hasFrameOfReferenceInfo = true))
    case part: DicomPart => Future.successful(part)
  }

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
