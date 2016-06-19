/*
 * Copyright 2016 Lars Edenbrandt
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

import java.io.ByteArrayOutputStream
import java.util.zip.{ZipEntry, ZipOutputStream}

import akka.actor.{Actor, ActorLogging, ActorRef, Props}
import akka.pattern.ask
import scala.util.{Success, Failure}
import org.dcm4che3.data.Attributes
import se.nimsa.sbx.anonymization.AnonymizationProtocol.ReverseAnonymization
import se.nimsa.sbx.app.GeneralProtocol._
import se.nimsa.sbx.app.SliceboxService
import se.nimsa.sbx.dicom.DicomHierarchy.{FlatSeries, Image, Patient, Study}
import se.nimsa.sbx.dicom._
import se.nimsa.sbx.metadata.MetaDataProtocol._
import se.nimsa.sbx.storage.StorageProtocol._
import se.nimsa.sbx.user.UserProtocol.ApiUser
import se.nimsa.sbx.util.SbxExtensions._
import spray.can.Http
import spray.http.ContentType.apply
import spray.http.HttpHeaders._
import spray.http.MediaTypes._
import spray.http.StatusCodes._
import spray.http._
import spray.routing.Route

import scala.concurrent.Future

trait ImageRoutes {
  this: SliceboxService =>

  val chunkSize = 524288
  val bufferSize = chunkSize

  def imageRoutes(apiUser: ApiUser): Route =
    pathPrefix("images") {
      pathEndOrSingleSlash {
        post {
          formField('file.as[FormFile]) { file =>
            addDicomDataRoute(file.entity.data.toByteArray, apiUser)
          } ~ entity(as[Array[Byte]]) { bytes =>
            addDicomDataRoute(bytes, apiUser)
          }
        }
      } ~ noop {
        import spray.httpx.SprayJsonSupport._
        pathPrefix(LongNumber) { imageId =>
          onSuccess(metaDataService.ask(GetImage(imageId)).mapTo[Option[Image]]) {
            case Some(image) =>
              pathEndOrSingleSlash {
                get {
                  onSuccess(storageService.ask(GetImageData(image)).mapTo[DicomDataArray]) { imageData =>
                    complete(HttpEntity(`application/octet-stream`, HttpData(imageData.data)))
                  }
                } ~ delete {
                  onSuccess(storageService.ask(DeleteDicomData(image)).flatMap { _ =>
                    metaDataService.ask(DeleteMetaData(image.id))
                  }) {
                    case _ =>
                      complete(NoContent)
                  }
                }
              } ~ path("attributes") {
                get {
                  onSuccess(storageService.ask(GetImageAttributes(image)).mapTo[List[ImageAttribute]]) {
                    complete(_)
                  }
                }
              } ~ path("imageinformation") {
                get {
                  onSuccess(storageService.ask(GetImageInformation(image)).mapTo[ImageInformation]) {
                    complete(_)
                  }
                }
              } ~ path("png") {
                parameters(
                  'framenumber.as[Int] ? 1,
                  'windowmin.as[Int] ? 0,
                  'windowmax.as[Int] ? 0,
                  'imageheight.as[Int] ? 0) { (frameNumber, min, max, height) =>
                  get {
                    onComplete(storageService.ask(GetPngDataArray(image, frameNumber, min, max, height))) {
                      case Success(PngDataArray(bytes)) => complete(HttpEntity(`image/png`, HttpData(bytes)))
                      case Failure(e) => complete(NoContent)
                      case _ => complete(InternalServerError)
                    }
                  }
                }
              }
            case None =>
              complete((NotFound, s"No image meta data found for image id $imageId"))
          }
        } ~ path("delete") {
          post {
            entity(as[Seq[Long]]) { imageIds =>
              val futureDeleted = Future.sequence {
                imageIds.map { imageId =>
                  metaDataService.ask(GetImage(imageId)).mapTo[Option[Image]].map { imageMaybe =>
                    imageMaybe.map { image =>
                      storageService.ask(DeleteDicomData(image)).flatMap { _ =>
                        metaDataService.ask(DeleteMetaData(image.id))
                      }
                    }
                  }.unwrap
                }
              }
              onSuccess(futureDeleted) { m =>
                complete(NoContent)
              }
            }
          }
        } ~ path("export") {
          post {
            entity(as[Seq[Long]]) { imageIds =>
              if (imageIds.isEmpty)
                complete(NoContent)
              else
                complete(storageService.ask(CreateExportSet(imageIds)).mapTo[ExportSetId])
            }
          } ~ get {
            parameter('id.as[Long]) { exportSetId =>
              respondWithHeader(`Content-Disposition`("attachment; filename=\"slicebox-export.zip\"")) {
                onSuccess(storageService.ask(GetExportSetImageIds(exportSetId)).mapTo[Option[Seq[Long]]]) {
                  case Some(imageIds) =>
                    ctx => {
                      val streamer = actorRefFactory.actorOf(Props(new ChunkedZipStreamer(ctx.responder, imageIds)))
                    }
                  case None =>
                    complete(NotFound)
                }
              }
            }
          }
        }
      } ~ path("jpeg") {
        parameters('studyid.as[Long]) { studyId =>
          post {
            entity(as[Array[Byte]]) { jpegBytes =>
              import spray.httpx.SprayJsonSupport._

              val source = Source(SourceType.USER, apiUser.user, apiUser.id)
              val addedJpegFuture = metaDataService.ask(GetStudy(studyId)).mapTo[Option[Study]].flatMap { studyMaybe =>
                studyMaybe.map { study =>
                  metaDataService.ask(GetPatient(study.patientId)).mapTo[Option[Patient]].map { patientMaybe =>
                    patientMaybe.map { patient =>
                      val dicomData = Jpeg2Dcm(jpegBytes, patient, study)
                      metaDataService.ask(AddMetaData(dicomData.attributes, source)).mapTo[MetaDataAdded].flatMap { metaData =>
                        storageService.ask(AddDicomData(dicomData, source, metaData.image)).map { _ => metaData.image }
                      }
                    }
                  }
                }.unwrap
              }.unwrap
              onSuccess(addedJpegFuture) {
                case Some(image) =>
                  complete((Created, image))
                case _ =>
                  complete(NotFound)
              }
            }
          }
        }
      }
    }

  private def addDicomDataRoute(bytes: Array[Byte], apiUser: ApiUser) = {
    import spray.httpx.SprayJsonSupport._

    val dicomData = DicomUtil.loadDicomData(bytes, withPixelData = true)
    val source = Source(SourceType.USER, apiUser.user, apiUser.id)
    val futureImageAndOverwrite =
      storageService.ask(CheckDicomData(dicomData, useExtendedContexts = true)).mapTo[Boolean].flatMap { status =>
        anonymizationService.ask(ReverseAnonymization(dicomData.attributes)).mapTo[Attributes].flatMap { reversedAttributes =>
          metaDataService.ask(AddMetaData(reversedAttributes, source)).mapTo[MetaDataAdded].flatMap { metaData =>
            storageService.ask(AddDicomData(dicomData.copy(attributes = reversedAttributes), source, metaData.image)).mapTo[DicomDataAdded].map { dicomDataAdded =>
              (metaData.image, dicomDataAdded.overwrite)
            }
          }
        }
      }
    onSuccess(futureImageAndOverwrite) {
      case (image, overwrite) =>
        if (overwrite)
          complete((OK, image))
        else
          complete((Created, image))
    }
  }

  class ChunkedZipStreamer(recipient: ActorRef, imageIds: Seq[Long]) extends Actor with ActorLogging {

    case class Ok(imageIds: Seq[Long])
    case object Finalize

    val byteStream = new ByteArrayOutputStream()
    val zipStream = new ZipOutputStream(byteStream)
    log.debug("Starting export zip stream")

    recipient ! ChunkedResponseStart(HttpResponse()).withAck(Ok(imageIds))

    def receive = {
      case Finalize =>
        log.debug("Finalizing images export zip stream")
        recipient ! ChunkedMessageEnd
        context.stop(self)

      case Ok(Nil) =>
        log.debug("Sending final chunk")
        zipStream.close()
        val zippedBytes = byteStream.toByteArray
        recipient ! MessageChunk(zippedBytes).withAck(Finalize)

      case Ok(remainingImageIds) =>
        val imageId = remainingImageIds.head
        log.debug(s"Sending images export zip stream chunk for image id $imageId")
        getImageData(imageId).foreach {
          case Some((image, flatSeries, imageData)) =>
            val zipEntry = createZipEntry(image, flatSeries)
            zipStream.putNextEntry(zipEntry)
            zipStream.write(imageData.data)
            val zippedBytes = byteStream.toByteArray
            byteStream.reset()
            recipient ! MessageChunk(zippedBytes).withAck(Ok(remainingImageIds.tail))
          case _ =>
            self ! Ok(remainingImageIds.tail)
        }

      case x: Http.ConnectionClosed =>
        log.debug("Canceling images export zip stream due to {}", x)
        context.stop(self)
    }

    def getImageData(imageId: Long): Future[Option[(Image, FlatSeries, DicomDataArray)]] =
      metaDataService.ask(GetImage(imageId)).mapTo[Option[Image]].flatMap { imageMaybe =>
        imageMaybe.map { image =>
          metaDataService.ask(GetSingleFlatSeries(image.seriesId)).mapTo[Option[FlatSeries]].map { flatSeriesMaybe =>
            flatSeriesMaybe.map { flatSeries =>
              storageService.ask(GetImageData(image)).mapTo[DicomDataArray].map { imageData =>
                (image, flatSeries, imageData)
              }
            }
          }.unwrap
        }.unwrap
      }

    def createZipEntry(image: Image, flatSeries: FlatSeries): ZipEntry = {

      def sanitize(string: String) = string.replace('/', '-').replace('\\', '-')

      val patientFolder = sanitize(s"${flatSeries.patient.id}_${flatSeries.patient.patientName.value}-${flatSeries.patient.patientID.value}")
      val studyFolder = sanitize(s"${flatSeries.study.id}_${flatSeries.study.studyDate.value}")
      val seriesFolder = sanitize(s"${flatSeries.series.id}_${flatSeries.series.seriesDate.value}_${flatSeries.series.modality.value}")
      val imageName = s"${image.id}.dcm"
      val entryName = s"$patientFolder/$studyFolder/$seriesFolder/$imageName"
      new ZipEntry(entryName)
    }

  }

}
