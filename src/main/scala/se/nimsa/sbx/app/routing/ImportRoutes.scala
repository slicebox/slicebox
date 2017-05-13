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

import akka.NotUsed
import akka.http.scaladsl.model.StatusCodes._
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.Route
import akka.pattern.ask
import akka.stream.scaladsl.{Broadcast, Flow, GraphDSL, Merge, Sink, Source => StreamSource}
import akka.stream.{FlowShape, SinkShape}
import akka.util.ByteString
import org.dcm4che3.data.{Attributes, Tag, UID}
import org.dcm4che3.io.DicomStreamException
import se.nimsa.dcm4che.streams.DicomFlows._
import se.nimsa.dcm4che.streams.DicomPartFlow._
import se.nimsa.dcm4che.streams.DicomParts._
import se.nimsa.dcm4che.streams.{DicomAttributesSink, DicomFlows}
import se.nimsa.sbx.anonymization.AnonymizationProtocol.{AnonymizationKeys, GetReverseAnonymizationKeys}
import se.nimsa.sbx.app.GeneralProtocol._
import se.nimsa.sbx.app.SliceboxBase
import se.nimsa.sbx.dicom.Contexts
import se.nimsa.sbx.dicom.DicomHierarchy.Image
import se.nimsa.sbx.importing.ImportProtocol._
import se.nimsa.sbx.log.SbxLog
import se.nimsa.sbx.metadata.MetaDataProtocol.{AddMetaData, GetImage, MetaDataAdded}
import se.nimsa.sbx.storage.StorageProtocol._
import se.nimsa.sbx.user.UserProtocol.ApiUser
import se.nimsa.sbx.util.CollectMetaDataFlow.DicomMetaPart
import se.nimsa.sbx.util.{CollectMetaDataFlow, ReverseAnonymizationFlow}

import scala.concurrent.Future
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


  def maybeAnonymizationLookup(dicomPart: DicomPart): Future[DicomPart] = {
    dicomPart match {
      case meta: DicomMetaPart =>
        if (meta.isAnonymized && meta.patientName.isDefined && meta.patientId.isDefined) {
          anonymizationService.ask(GetReverseAnonymizationKeys(meta.patientName.get, meta.patientId.get)).mapTo[AnonymizationKeys].map { keys: AnonymizationKeys =>
            if (meta.studyInstanceUID.isEmpty) {
              throw new RuntimeException("StudyInstanceUID not found in DicomMetaPart")
            }
            if (meta.seriesInstanceUID.isEmpty) {
              throw new RuntimeException("SeriesInstanceUID not found in DicomMetaPart")
            }
            val filtered = keys.anonymizationKeys.filter(key => (key.anonStudyInstanceUID == meta.studyInstanceUID.get) && (key.anonSeriesInstanceUID == meta.seriesInstanceUID.get))
            DicomMetaPart(meta.transferSyntaxUid, meta.patientId, meta.patientName, meta.identityRemoved, meta.studyInstanceUID, meta.seriesInstanceUID, filtered.headOption)
          }
        } else {
          Future.successful(meta)
        }

      case part: DicomPart =>
        Future.successful(part)
    }
  }



  private def tagsToStoreInDB = {
    val patientTags = Seq(Tag.PatientName, Tag.PatientID, Tag.PatientSex, Tag.PatientBirthDate)
    val studyTags = Seq(Tag.StudyInstanceUID, Tag.StudyDescription, Tag.StudyDate, Tag.AccessionNumber, Tag.PatientAge)
    val seriesTags = Seq(Tag.SeriesInstanceUID, Tag.SeriesDescription, Tag.SeriesDate, Tag.Modality, Tag.ProtocolName, Tag.BodyPartExamined, Tag.Manufacturer, Tag.StationName, Tag.FrameOfReferenceUID)
    val imageTags = Seq(Tag.SOPInstanceUID, Tag.ImageType, Tag.InstanceNumber)

    patientTags ++ studyTags ++ seriesTags ++ imageTags
  }

  def maybeDeflateFlow: Flow[DicomPart, DicomPart, NotUsed] = Flow.fromGraph(GraphDSL.create() { implicit builder =>
    import GraphDSL.Implicits._

    var shouldDeflate = false // determines which path is taken in the graph, with or without deflating

    // check transfer syntax in the meta part
    val shouldDeflateFlow = builder.add {
      Flow[DicomPart].map {
        case p: DicomMetaPart =>
          if (p.transferSyntaxUid.contains(UID.DeflatedExplicitVRLittleEndian) || p.transferSyntaxUid.contains(UID.JPIPReferencedDeflate))
            shouldDeflate = true
          p
        case p => p
      }
    }

    // split the flow
    val bcast = builder.add(Broadcast[DicomPart](2))

    // define gates for each path, only one path is used
    val deflateYes = Flow[DicomPart].filter(_ =>    shouldDeflate)
    val deflateNo  = Flow[DicomPart].filterNot(_ => shouldDeflate)

    // the deflate stage
    val deflate = DicomFlows.deflateDatasetFlow()

    // merge the two paths
    val merge = builder.add(Merge[DicomPart](2))

    shouldDeflateFlow ~> bcast ~> deflateYes ~> deflate ~> merge
                         bcast ~> deflateNo             ~> merge

    FlowShape(shouldDeflateFlow.in, merge.out)
  })

  def addImageToImportSessionRoute(bytes: StreamSource[ByteString, Any], importSessionId: Long): Route = {

    val tmpId = java.util.UUID.randomUUID().toString
    val tmpPath = s"tmp-$tmpId"

    onSuccess(importService.ask(GetImportSession(importSessionId)).mapTo[Option[ImportSession]]) {
      case Some(importSession) =>

        val source = Source(SourceType.IMPORT, importSession.name, importSessionId)

        val dicomFileSink = this.storage.fileSink(tmpPath)
        val dbAttributesSink = DicomAttributesSink.attributesSink

        val validationContexts = Contexts.asNamePairs(Contexts.imageDataContexts).map { pair =>
          ValidationContext(pair._1, pair._2)
        }

        val importSink = Sink.fromGraph(GraphDSL.create(dicomFileSink, dbAttributesSink)(_ zip _) { implicit builder =>
          (dicomFileSink, dbAttributesSink) =>
            import GraphDSL.Implicits._

            val flow = builder.add {
              validateFlowWithContext(validationContexts).
                via(partFlow).
                via(groupLengthDiscardFilter).
                via(CollectMetaDataFlow.collectMetaDataFlow).
                mapAsync(5)(maybeAnonymizationLookup).
                via(ReverseAnonymizationFlow.reverseAnonFlow)
            }

            val bcast = builder.add(Broadcast[DicomPart](2))

            flow ~> bcast.in
            bcast.out(0) ~> maybeDeflateFlow.map(_.bytes) ~> dicomFileSink
            bcast.out(1) ~> whitelistFilter(tagsToStoreInDB) ~> attributeFlow ~> dbAttributesSink

            SinkShape(flow.in)
        })

        onComplete(bytes.runWith(importSink)) {
          case Success((_, attributes)) =>
            val dataAttributes: Attributes = attributes._2.get
            onSuccess(metaDataService.ask(AddMetaData(dataAttributes, source)).mapTo[MetaDataAdded]) { metaData =>
              onSuccess(importService.ask(AddImageToSession(importSession.id, metaData.image, !metaData.imageAdded)).mapTo[ImageAddedToSession]) { _ =>
                onSuccess(storageService.ask(MoveDicomData(tmpPath, s"${metaData.image.id}")).mapTo[DicomDataMoved]) { _ =>
                  system.eventStream.publish(ImageAdded(metaData.image, source, !metaData.imageAdded))
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
            complete((InternalServerError, failure.getMessage))
        }

      case None =>
        complete(NotFound)
    }
  }
}
