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
import akka.stream.scaladsl.{Broadcast, Flow, GraphDSL, Merge, Source}
import akka.stream.{FlowShape, Materializer}
import akka.util.ByteString
import org.dcm4che3.data.SpecificCharacterSet
import se.nimsa.dcm4che.streams.DicomAttributesSink
import se.nimsa.dicom.Tag
import se.nimsa.dicom.streams.DicomParts.{DicomAttributes, DicomPart}
import se.nimsa.sbx.anonymization.AnonymizationProtocol.AnonymizationKey

import scala.concurrent.{ExecutionContext, Future}

object DicomStreamUtil {

  val encodingTags = Set(Tag.TransferSyntaxUID, Tag.SpecificCharacterSet)

  val tagsToStoreInDB: Set[Int] = {
    val patientTags = Seq(Tag.PatientName, Tag.PatientID, Tag.PatientSex, Tag.PatientBirthDate)
    val studyTags = Seq(Tag.StudyInstanceUID, Tag.StudyDescription, Tag.StudyID, Tag.StudyDate, Tag.AccessionNumber, Tag.PatientAge)
    val seriesTags = Seq(Tag.SeriesInstanceUID, Tag.SeriesDescription, Tag.SeriesDate, Tag.Modality, Tag.ProtocolName, Tag.BodyPartExamined, Tag.Manufacturer, Tag.StationName, Tag.FrameOfReferenceUID)
    val imageTags = Seq(Tag.SOPInstanceUID, Tag.ImageType, Tag.InstanceNumber)

    encodingTags ++ patientTags ++ studyTags ++ seriesTags ++ imageTags
  }

  val basicInfoTags: Set[Int] = encodingTags ++ Set(Tag.PatientName, Tag.PatientID, Tag.PatientIdentityRemoved,
    Tag.StudyInstanceUID, Tag.SeriesInstanceUID)

  val extendedInfoTags: Set[Int] = basicInfoTags ++ Set(Tag.PatientSex, Tag.PatientBirthDate, Tag.PatientAge, Tag.StudyDescription, Tag.StudyID,
    Tag.AccessionNumber, Tag.SeriesDescription, Tag.ProtocolName, Tag.FrameOfReferenceUID)

  val imageInformationTags = Set(Tag.InstanceNumber, Tag.ImageIndex, Tag.NumberOfFrames, Tag.SmallestImagePixelValue, Tag.LargestImagePixelValue)

  case class PartialAnonymizationKeyPart(keyMaybe: Option[AnonymizationKey], hasPatientInfo: Boolean, hasStudyInfo: Boolean, hasSeriesInfo: Boolean) extends DicomPart {
    def bytes: ByteString = ByteString.empty
    def bigEndian: Boolean = false
  }

  case class DicomInfoPart(transferSyntaxUid: Option[String],
                           specificCharacterSet: Option[SpecificCharacterSet],
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

  def attributesToInfoPart(dicomPart: DicomPart, tag: String)
                          (implicit ec: ExecutionContext, materializer: Materializer): Future[DicomPart] = {
    dicomPart match {
      case da: DicomAttributes if da.tag == tag =>

        Source.fromIterator(() => da.attributes.iterator).runWith(DicomAttributesSink.attributesSink).map {
          case (fmiMaybe, dsMaybe) =>
            DicomInfoPart(
              fmiMaybe.flatMap(fmi => Option(fmi.getString(Tag.TransferSyntaxUID))),
              dsMaybe.flatMap(ds => Option(ds.getSpecificCharacterSet)),
              dsMaybe.flatMap(ds => Option(ds.getString(Tag.PatientID))),
              dsMaybe.flatMap(ds => Option(ds.getString(Tag.PatientName))),
              dsMaybe.flatMap(ds => Option(ds.getString(Tag.PatientSex))),
              dsMaybe.flatMap(ds => Option(ds.getString(Tag.PatientBirthDate))),
              dsMaybe.flatMap(ds => Option(ds.getString(Tag.PatientAge))),
              dsMaybe.flatMap(ds => Option(ds.getString(Tag.PatientIdentityRemoved))),
              dsMaybe.flatMap(ds => Option(ds.getString(Tag.StudyInstanceUID))),
              dsMaybe.flatMap(ds => Option(ds.getString(Tag.StudyDescription))),
              dsMaybe.flatMap(ds => Option(ds.getString(Tag.StudyID))),
              dsMaybe.flatMap(ds => Option(ds.getString(Tag.AccessionNumber))),
              dsMaybe.flatMap(ds => Option(ds.getString(Tag.SeriesInstanceUID))),
              dsMaybe.flatMap(ds => Option(ds.getString(Tag.SeriesDescription))),
              dsMaybe.flatMap(ds => Option(ds.getString(Tag.ProtocolName))),
              dsMaybe.flatMap(ds => Option(ds.getString(Tag.FrameOfReferenceUID))))
        }
      case part: DicomPart => Future.successful(part)
    }
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
