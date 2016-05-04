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
import se.nimsa.sbx.app.GeneralProtocol._
import se.nimsa.sbx.app.SliceboxService
import se.nimsa.sbx.dicom.DicomHierarchy.{FlatSeries, Image, Patient, Study}
import se.nimsa.sbx.dicom.{DicomUtil, ImageAttribute, Jpeg2Dcm}
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
            addDatasetRoute(file.entity.data.toByteArray, apiUser)
          } ~ entity(as[Array[Byte]]) { bytes =>
            addDatasetRoute(bytes, apiUser)
          }
        }
      } ~ noop {
        import spray.httpx.SprayJsonSupport._
        pathPrefix(LongNumber) { imageId =>
          onSuccess(metaDataService.ask(GetImage(imageId)).mapTo[Option[Image]]) {
            case Some(image) =>
              pathEndOrSingleSlash {
                get {
                  onSuccess(storageService.ask(GetImageData(image)).mapTo[Option[ImageData]]) {
                    case Some(imageData) =>
                      complete(HttpEntity(`application/octet-stream`, HttpData(imageData.data)))
                    case None =>
                      complete((NotFound, s"No file found for image id $imageId"))
                  }
                } ~ delete {
                  onSuccess(storageService.ask(DeleteDataset(image)).flatMap { _ =>
                    metaDataService.ask(DeleteMetaData(image.id))
                  }) {
                    case _ =>
                      complete(NoContent)
                  }
                }
              } ~ path("attributes") {
                get {
                  onSuccess(storageService.ask(GetImageAttributes(image)).mapTo[Option[List[ImageAttribute]]]) {
                    complete(_)
                  }
                }
              } ~ path("imageinformation") {
                get {
                  onSuccess(storageService.ask(GetImageInformation(image)).mapTo[Option[ImageInformation]]) {
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
                    onSuccess(storageService.ask(GetPngImageData(image, frameNumber, min, max, height))) {
                      case Some(PngImageData(bytes)) => complete(HttpEntity(`image/png`, HttpData(bytes)))
                      case Some(PngImageDataNotAvailable) => complete(NoContent)
                      case None => complete(NotFound)
                    }
                  }
                }
              }
            case None =>
              complete(NotFound)
          }
        } ~ path("delete") {
          post {
            entity(as[Seq[Long]]) { imageIds =>
              val futureDeleted = Future.sequence {
                imageIds.map { imageId =>
                  metaDataService.ask(GetImage(imageId)).mapTo[Option[Image]].map { imageMaybe =>
                    imageMaybe.map { image =>
                      storageService.ask(DeleteDataset(image)).flatMap { _ =>
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
                      val encapsulatedJpeg = Jpeg2Dcm(jpegBytes, patient, study)
                      metaDataService.ask(AddMetaData(encapsulatedJpeg, source)).mapTo[MetaDataAdded].flatMap { metaData =>
                        storageService.ask(AddJpeg(encapsulatedJpeg, source, metaData.image)).map { _ => metaData.image }
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

  private def addDatasetRoute(bytes: Array[Byte], apiUser: ApiUser) = {
    import spray.httpx.SprayJsonSupport._

    val dataset = DicomUtil.loadDataset(bytes, withPixelData = true, useBulkDataURI = false)
    val source = Source(SourceType.USER, apiUser.user, apiUser.id)
    val futureImageAndOverwrite =
      storageService.ask(CheckDataset(dataset, restrictSopClass = false)).mapTo[Boolean].flatMap {
        status =>
          metaDataService.ask(AddMetaData(dataset, source)).mapTo[MetaDataAdded].flatMap {
            metaData =>
              storageService.ask(AddDataset(dataset, source, metaData.image)).mapTo[DatasetAdded].map {
                datasetAdded => (metaData.image, datasetAdded.overwrite)
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

    val byteStream = new ByteArrayOutputStream()
    val zipStream = new ZipOutputStream(byteStream)
    log.debug("Starting export zip stream")

    recipient ! ChunkedResponseStart(HttpResponse()).withAck(Ok(imageIds))

    def receive = {
      case Ok(Nil) =>
        log.debug("Finalizing images export zip stream")
        zipStream.close()
        val zippedBytes = byteStream.toByteArray
        recipient ! MessageChunk(zippedBytes)
        recipient ! ChunkedMessageEnd
        context.stop(self)

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

    def getImageData(imageId: Long): Future[Option[(Image, FlatSeries, ImageData)]] =
      metaDataService.ask(GetImage(imageId)).mapTo[Option[Image]].flatMap { imageMaybe =>
        imageMaybe.map { image =>
          metaDataService.ask(GetSingleFlatSeries(image.seriesId)).mapTo[Option[FlatSeries]].flatMap { flatSeriesMaybe =>
            flatSeriesMaybe.map { flatSeries =>
              storageService.ask(GetImageData(image)).mapTo[Option[ImageData]].map { imageDataMaybe =>
                imageDataMaybe.map { imageData =>
                  (image, flatSeries, imageData)
                }
              }
            }.unwrap
          }
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
