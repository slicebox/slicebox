/*
 * Copyright 2017 Lars Edenbrandt
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

package se.nimsa.sbx.app.routing


import akka.event.Logging

import scala.concurrent.Future
import akka.pattern.ask
import org.dcm4che3.data.{Attributes, Tag}
import se.nimsa.sbx.anonymization.AnonymizationProtocol.ReverseAnonymization
import se.nimsa.sbx.app.GeneralProtocol._
import se.nimsa.sbx.app.{Slicebox, SliceboxBase}
import se.nimsa.sbx.dicom.{Contexts, DicomUtil}
import se.nimsa.sbx.storage.StorageProtocol._
import se.nimsa.sbx.user.UserProtocol.ApiUser
import akka.http.scaladsl.model.StatusCodes._
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.Route
import akka.stream.{ClosedShape, FlowShape}
import akka.stream.alpakka.s3.scaladsl.S3Client
import akka.stream.scaladsl.{Broadcast, GraphDSL, RunnableGraph, Sink, Source => StreamSource}
import akka.util.ByteString
import org.dcm4che3.io.DicomStreamException
import se.nimsa.dcm4che.streams.DicomAttributesSink
import se.nimsa.sbx.metadata.MetaDataProtocol.{AddMetaData, GetImage, MetaDataAdded}
import se.nimsa.sbx.dicom.DicomHierarchy.Image
import se.nimsa.sbx.importing.ImportProtocol._
import se.nimsa.dcm4che.streams.DicomFlows._
import se.nimsa.dcm4che.streams.DicomPartFlow._
import se.nimsa.dcm4che.streams.DicomParts._
import se.nimsa.sbx.log.SbxLog
import se.nimsa.sbx.storage.{S3Facade, S3Stream}
import se.nimsa.sbx.util.{CollectMetaDataFlow, ReverseAnonymizationFlow}

import scala.util.{Failure, Success}

trait ImportRoutes {
  this: SliceboxBase =>

  def importRoutes(apiUser: ApiUser): Route =
    path("import" / "sessions" / LongNumber / "images") { id =>
      post {
        fileUpload("file") {
          case (_, bytes) => addImageToImportSessionRoute(bytes, id)
        } ~ extractDataBytes { bytes =>
          addImageToImportSessionRoute(bytes, id)
        }
      }
    } ~ pathPrefix("import") {

      pathPrefix("sessions") {
        pathEndOrSingleSlash {
          get {
            parameters(
              'startindex.as(nonNegativeFromStringUnmarshaller) ? 0,
              'count.as(nonNegativeFromStringUnmarshaller) ? 20) { (startIndex, count) =>
              onSuccess(importService.ask(GetImportSessions(startIndex, count))) {
                case ImportSessions(importSessions) =>
                  complete(importSessions)
              }
            }
          } ~ post {
            entity(as[ImportSession]) { importSession =>
              onSuccess(importService.ask(AddImportSession(importSession.copy(user = apiUser.user, userId = apiUser.id)))) {
                case importSession: ImportSession =>
                  complete((Created, importSession))
              }
            }
          }
        } ~ pathPrefix(LongNumber) { id =>
          pathEndOrSingleSlash {
            (get & rejectEmptyResponse) {
              complete(importService.ask(GetImportSession(id)).mapTo[Option[ImportSession]])
            } ~ delete {
              complete(importService.ask(DeleteImportSession(id)).map(_ =>
                NoContent))
            }
          } ~ path("images") {
            get {
              onSuccess(importService.ask(GetImportSessionImages(id))) {
                case ImportSessionImages(importSessionImages) =>
                  complete {
                    Future.sequence {
                      importSessionImages.map { importSessionImage =>
                        metaDataService.ask(GetImage(importSessionImage.imageId)).mapTo[Option[Image]]
                      }
                    }.map(_.flatten)
                  }
              }
            }
          }
        }
      }

    }









  private def tagsToStoreInDB = {
    val patientTags = Seq(Tag.PatientName, Tag.PatientID, Tag.PatientSex, Tag.PatientBirthDate)
    val studyTags = Seq(Tag.StudyInstanceUID, Tag.StudyDescription, Tag.StudyDate, Tag.AccessionNumber, Tag.PatientAge)
    val seriesTags = Seq(Tag.SeriesInstanceUID, Tag.SeriesDescription, Tag.SeriesDate, Tag.Modality, Tag.ProtocolName, Tag.BodyPartExamined, Tag.Manufacturer, Tag.StationName, Tag.FrameOfReferenceUID)
    val imageTags = Seq(Tag.SOPInstanceUID, Tag.ImageType, Tag.InstanceNumber)

    patientTags ++ studyTags ++ seriesTags ++ imageTags
  }



  def addImageToImportSessionRoute(bytes: StreamSource[ByteString, Any], importSessionId: Long): Route = {

    val tmpId = java.util.UUID.randomUUID().toString
    val tmpPath = s"tmp-$tmpId"
    SbxLog.info("Import", s"Storing to tmpPath $tmpPath")

    onSuccess(importService.ask(GetImportSession(importSessionId)).mapTo[Option[ImportSession]]) {
      case Some(importSession) =>

        val source = Source(SourceType.IMPORT, importSession.name, importSessionId)

        val dicomFileSink = this.storage.fileSink(tmpPath)
        val validationContexts = Contexts.asNamePairs(Contexts.imageDataContexts).map { pair =>
          ValidationContext(pair._1, pair._2)
        }
        val flow = validateFlowWithContext(validationContexts).
          via(partFlow).
          via(groupLengthDiscardFilter).
          via(CollectMetaDataFlow.collectMetaDataFlow).
          via(ReverseAnonymizationFlow.reverseAnonFlow)
        val dbAttributesSink = DicomAttributesSink.attributesSink


        // validateFlow -> partFlow -> groupLengthDiscardFilter -> metaDataGather
        // -> maybeReversAnonFlow -> broadcast -> (s3Sink, dbSink)
        // FIXME: in flows: deflated? change to inflated? change transferSyntax, group length
        val importGraph = RunnableGraph.fromGraph(GraphDSL.create(dicomFileSink, dbAttributesSink)(_ zip _) { implicit builder =>
          (dicomFileSink, dbAttributesSink) =>
            import GraphDSL.Implicits._

            // importing the partial graph will return its shape (inlets & outlets)
            val bcast = builder.add(Broadcast[DicomPart](2))

            bytes ~> flow ~> bcast.in
            bcast.out(0).map(_.bytes) ~> dicomFileSink
            //bcast.out(1) ~> whitelistFilter(tagsToStoreInDB) ~> attributeFlow ~> dbAttributesSink  //printFlow[DicomPart]
            bcast.out(1) ~> whitelistFilter(tagsToStoreInDB) ~> attributeFlow ~> dbAttributesSink

            // FIXME: reverse anon, inflate flow

            ClosedShape
        })

        onComplete(importGraph.run()) {
          case Success((uploadResult, attributes)) =>
            val dataAttributes: Attributes = attributes._2.get
            onSuccess(metaDataService.ask(AddMetaData(dataAttributes, source)).mapTo[MetaDataAdded]) { metaData =>
              onSuccess(importService.ask(AddImageToSession(importSession.id, metaData.image, !metaData.imageAdded)).mapTo[ImageAddedToSession]) { importSessionImage =>
                onSuccess(storageService.ask(MoveDicomData(tmpPath, s"${metaData.image.id}")).mapTo[DicomDataMoved]) { dataMoved =>
                  val httpStatus = if (metaData.imageAdded) Created else OK
                  complete((httpStatus, metaData.image))
                }
              }
            }
          case Failure(dicomStreamException: DicomStreamException) =>
            SbxLog.error("Exception during import", dicomStreamException.getMessage)
            importService.ask(UpdateSessionWithRejection(importSession))
            complete((BadRequest, dicomStreamException.getMessage))

          case Failure(failure) =>
            SbxLog.error("Exception during import", failure.getMessage)
            importService.ask(UpdateSessionWithRejection(importSession))
            complete(InternalServerError, failure.getMessage)
        }

      case None =>
        complete(NotFound)
    }





/*

    val is = bytes.runWith(StreamConverters.asInputStream())
    val dicomData = DicomUtil.loadDicomData(is, withPixelData = true)
    onSuccess(importService.ask(GetImportSession(importSessionId)).mapTo[Option[ImportSession]]) {
      case Some(importSession) =>

        val source = Source(SourceType.IMPORT, importSession.name, importSessionId)

        onComplete(storageService.ask(CheckDicomData(dicomData, useExtendedContexts = false)).mapTo[Boolean]) {

          case Success(status) =>
            onSuccess(anonymizationService.ask(ReverseAnonymization(dicomData.attributes)).mapTo[Attributes]) { reversedAttributes =>
              onSuccess(metaDataService.ask(AddMetaData(reversedAttributes, source)).mapTo[MetaDataAdded]) { metaData =>
                onSuccess(storageService.ask(AddDicomData(dicomData.copy(attributes = reversedAttributes), source, metaData.image)).mapTo[DicomDataAdded]) { dicomDataAdded =>
                  onSuccess(importService.ask(AddImageToSession(importSession.id, dicomDataAdded.image, dicomDataAdded.overwrite)).mapTo[ImageAddedToSession]) { importSessionImage =>
                    if (dicomDataAdded.overwrite)
                      complete((OK, dicomDataAdded.image))
                    else
                      complete((Created, dicomDataAdded.image))
                  }
                }
              }
            }

          case Failure(e) =>
            importService.ask(UpdateSessionWithRejection(importSession))
            complete((BadRequest, e.getMessage))

        }

      case None =>
        complete(NotFound)

    }*/
  }

}
