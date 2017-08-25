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

package se.nimsa.sbx.app.routing

import java.io.ByteArrayOutputStream
import java.util.zip.{ZipEntry, ZipOutputStream}

import akka.NotUsed
import akka.http.scaladsl.common.EntityStreamingSupport
import akka.http.scaladsl.model.HttpEntity
import akka.http.scaladsl.model.MediaTypes._
import akka.http.scaladsl.model.StatusCodes._
import akka.http.scaladsl.model.headers._
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.Route
import akka.pattern.ask
import akka.stream.scaladsl.{Sink, SourceQueueWithComplete, Source => StreamSource}
import akka.stream.{OverflowStrategy, QueueOfferResult}
import akka.util.ByteString
import se.nimsa.dcm4che.streams.DicomModifyFlow.TagModification
import se.nimsa.sbx.app.GeneralProtocol._
import se.nimsa.sbx.app.SliceboxBase
import se.nimsa.sbx.dicom.DicomHierarchy.{FlatSeries, Image, Patient, Study}
import se.nimsa.sbx.dicom._
import se.nimsa.sbx.lang.NotFoundException
import se.nimsa.sbx.metadata.MetaDataProtocol._
import se.nimsa.sbx.storage.StorageProtocol._
import se.nimsa.sbx.user.UserProtocol.ApiUser
import se.nimsa.sbx.util.SbxExtensions._

import scala.concurrent.Future
import scala.util.{Failure, Success}

trait ImageRoutes {
  this: SliceboxBase =>

  implicit val jsonStreamingSupport = EntityStreamingSupport.json()

  def imageRoutes(apiUser: ApiUser): Route =
    pathPrefix("images") {
      pathEndOrSingleSlash {
        post {
          fileUpload("file") {
            case (_, bytes) => addDicomDataRoute(bytes, apiUser)
          } ~ extractDataBytes { bytes =>
            addDicomDataRoute(bytes, apiUser)
          }
        }
      } ~ pathPrefix(LongNumber) { imageId =>
        pathEndOrSingleSlash {
          get {
            complete(HttpEntity(`application/octet-stream`, storage.fileSource(imageId)))
          } ~ delete {
            complete(metaDataService.ask(DeleteMetaData(Seq(imageId))).map { _ =>
              system.eventStream.publish(ImagesDeleted(Seq(imageId)))
              storage.deleteFromStorage(Seq(imageId))
              NoContent
            })
          }
        } ~ path("attributes") {
          get {
            complete(storage.readImageAttributes(imageId))
          }
        } ~ path("imageinformation") {
          get {
            complete(storage.readImageInformation(imageId))
          }
        } ~ path("png") {
          parameters((
            'framenumber.as[Int] ? 1,
            'windowmin.as[Int] ? 0,
            'windowmax.as[Int] ? 0,
            'imageheight.as[Int] ? 0)) { (frameNumber, min, max, height) =>
            get {
              onComplete(storage.readPngImageData(imageId, frameNumber, min, max, height)) {
                case Success(bytes) => complete(HttpEntity(`image/png`, bytes))
                case Failure(_: NotFoundException) => complete(NotFound)
                case Failure(_) => complete(NotImplemented)
              }
            }
          }
        } ~ path("modify") {
          put {
            entity(as[Seq[TagMapping]]) { tagMappings =>
              val tagModifications = tagMappings
                .map(tm => TagModification(tm.tagPath, _ => DicomUtil.padToEvenLength(tm.value, tm.tagPath.tag), insert = true))
              onSuccess(modifyData(imageId, tagModifications, storage)) {
                case (metaDataDeleted, metaDataAdded) =>
                  system.eventStream.publish(ImagesDeleted(metaDataDeleted.imageIds))
                  system.eventStream.publish(ImageAdded(metaDataAdded.image.id, metaDataAdded.source, !metaDataAdded.imageAdded))
                  complete((Created, metaDataAdded.image))
              }
            }
          }
        }
      } ~ path("delete") {
        post {
          entity(as[Seq[Long]]) { imageIds =>
            val futureDeleted = metaDataService.ask(DeleteMetaData(imageIds)).map(_ =>
              storage.deleteFromStorage(imageIds))
            onSuccess(futureDeleted) {
              system.eventStream.publish(ImagesDeleted(imageIds))
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
            respondWithHeader(`Content-Disposition`(ContentDispositionTypes.attachment, Map("filename" -> "slicebox-export.zip"))) {
              onSuccess(storageService.ask(GetExportSetImageIds(exportSetId)).mapTo[Option[Seq[Long]]]) {
                case Some(imageIds) =>
                  val source: StreamSource[ByteString, _] = StreamSource.queue[ByteString](0, OverflowStrategy.fail)
                    .mapMaterializedValue(queue => new ImageZipper(queue).zipNext(imageIds))
                  complete(HttpEntity(`application/octet-stream`, source))
                case None =>
                  complete(NotFound)
              }
            }
          }
        }
      } ~ path("jpeg") {
        parameters(('studyid.as[Long], 'description.?)) { (studyId, optionalDescription) =>
          post {
            extractDataBytes { bytes =>
              val source = Source(SourceType.USER, apiUser.user, apiUser.id)
              val addedJpegFuture = metaDataService.ask(GetStudy(studyId)).mapTo[Option[Study]].flatMap { studyMaybe =>
                studyMaybe.map { study =>
                  metaDataService.ask(GetPatient(study.patientId)).mapTo[Option[Patient]].map { patientMaybe =>
                    patientMaybe.map { patient =>
                      bytes.fold(ByteString.empty)(_ ++ _).runWith(Sink.head).map { allBytes =>
                        val scBytes = Jpeg2Dcm(allBytes.toArray, patient, study, optionalDescription)
                        storeDicomData(StreamSource.single(ByteString(scBytes)), source, storage, Contexts.extendedContexts)
                          .map { metaData =>
                            system.eventStream.publish(ImageAdded(metaData.image.id, source, !metaData.imageAdded))
                            metaData.image
                          }
                      }
                    }
                  }.unwrap
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

  private def addDicomDataRoute(bytes: StreamSource[ByteString, _], apiUser: ApiUser): Route = {
    val source = Source(SourceType.USER, apiUser.user, apiUser.id)
    val futureUpload = storeDicomData(bytes, source, storage, Contexts.extendedContexts)

    onSuccess(futureUpload) { metaData =>
      system.eventStream.publish(ImageAdded(metaData.image.id, source, !metaData.imageAdded))
      val httpStatus = if (metaData.imageAdded) Created else OK
      complete((httpStatus, metaData.image))
    }
  }

  class ImageZipper(queue: SourceQueueWithComplete[ByteString]) {

    val byteStream = new ByteArrayOutputStream()
    val zipStream = new ZipOutputStream(byteStream)

    private def getImageData(imageId: Long): Future[Option[(Image, FlatSeries, StreamSource[ByteString, NotUsed])]] =
      metaDataService.ask(GetImage(imageId)).mapTo[Option[Image]].flatMap { imageMaybe =>
        imageMaybe.map { image =>
          metaDataService.ask(GetSingleFlatSeries(image.seriesId)).mapTo[Option[FlatSeries]].map { flatSeriesMaybe =>
            flatSeriesMaybe.map { flatSeries =>
              (image, flatSeries, storage.fileSource(imageId))
            }
          }
        }.unwrap
      }

    private def createZipEntry(image: Image, flatSeries: FlatSeries): ZipEntry = {

      def sanitize(string: String) = string.replace('/', '-').replace('\\', '-')

      val patientFolder = sanitize(s"${flatSeries.patient.id}_${flatSeries.patient.patientName.value}-${flatSeries.patient.patientID.value}")
      val studyFolder = sanitize(s"${flatSeries.study.id}_${flatSeries.study.studyDate.value}")
      val seriesFolder = sanitize(s"${flatSeries.series.id}_${flatSeries.series.seriesDate.value}_${flatSeries.series.modality.value}")
      val imageName = s"${image.id}.dcm"
      val entryName = s"$patientFolder/$studyFolder/$seriesFolder/$imageName"
      new ZipEntry(entryName)
    }

    def zipNext(imageIds: Seq[Long]): Unit = {
      if (imageIds.nonEmpty) {
        val imageId = imageIds.head
        getImageData(imageId).onComplete {

          case Success(Some((image, flatSeries, dicomDataSource))) =>
            val zipEntry = createZipEntry(image, flatSeries)
            zipStream.putNextEntry(zipEntry)
            dicomDataSource.map(_.toArray).runForeach(zipStream.write).andThen {
              case Success(_) =>
                val zippedBytes = byteStream.toByteArray
                byteStream.reset()
                queue.offer(ByteString(zippedBytes)).onComplete {

                  case Success(QueueOfferResult.Enqueued) =>
                    zipNext(imageIds.tail)

                  case Success(result) =>
                    queue.fail(new Exception(s"Unable to add image $imageId to export stream: $result"))

                  case Failure(error) =>
                    queue.fail(error)
                }
            }
          case Success(None) =>
            queue.fail(new Exception(s"Could not find image data for image id $imageId"))

          case Failure(error) =>
            queue.fail(error)
            zipStream.close()
        }
      } else {
        zipStream.close()
        val zippedBytes = byteStream.toByteArray
        queue.offer(ByteString(zippedBytes)).onComplete {
          case Success(_) => queue.complete()
          case Failure(error) => queue.fail(error)
        }
      }
    }

  }

}
