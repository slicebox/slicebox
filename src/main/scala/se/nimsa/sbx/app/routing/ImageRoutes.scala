/*
 * Copyright 2015 Lars Edenbrandt
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

import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import scala.concurrent.Future
import scala.concurrent.duration.DurationInt
import org.dcm4che3.data.Attributes
import akka.actor.Props
import akka.actor.Actor
import akka.pattern.ask
import se.nimsa.sbx.user.UserProtocol._
import se.nimsa.sbx.anonymization.AnonymizationProtocol._
import se.nimsa.sbx.anonymization.AnonymizationUtil
import se.nimsa.sbx.app.RestApi
import se.nimsa.sbx.dicom.DicomUtil
import se.nimsa.sbx.storage.StorageProtocol._
import spray.http.FormFile
import spray.http.HttpData
import spray.http.HttpEntity
import spray.http.MediaTypes._
import spray.http.StatusCodes._
import spray.routing.Route
import spray.http.MediaType
import spray.http.HttpHeaders.`Content-Disposition`
import java.nio.file.Paths
import java.nio.file.Path
import java.io.File
import se.nimsa.sbx.dicom.ImageAttribute
import se.nimsa.sbx.app.GeneralProtocol._

trait ImageRoutes { this: RestApi =>

  val chunkSize = 524288
  val bufferSize = chunkSize

  def imageRoutes(apiUser: ApiUser): Route =
    pathPrefix("images") {
      pathEndOrSingleSlash {
        post {
          formField('file.as[FormFile]) { file =>
            val dataset = DicomUtil.loadDataset(file.entity.data.toByteArray, true)
            val source = Source(SourceType.USER, apiUser.user, apiUser.id)
            onSuccess(storageService.ask(AddDataset(dataset, source, false))) {
              case ImageAdded(image, source) =>
                import spray.httpx.SprayJsonSupport._
                complete((Created, image))
            }
          } ~ entity(as[Array[Byte]]) { bytes =>
            val dataset = DicomUtil.loadDataset(bytes, true)
            val source = Source(SourceType.USER, apiUser.user, apiUser.id)
            onSuccess(storageService.ask(AddDataset(dataset, source, false))) {
              case ImageAdded(image, source) =>
                import spray.httpx.SprayJsonSupport._
                complete((Created, image))
            }
          }
        }
      } ~ pathPrefix(LongNumber) { imageId =>
        pathEndOrSingleSlash {
          get {
            onSuccess(storageService.ask(GetImagePath(imageId)).mapTo[Option[ImagePath]]) {
              _.map(imagePath => {
                detach() {
                  autoChunk(chunkSize) {
                    complete(HttpEntity(`application/octet-stream`, HttpData(imagePath.imagePath.toFile)))
                  }
                }
              }).getOrElse {
                complete((NotFound, s"No file found for image id $imageId"))
              }
            }
          } ~ delete {
            onSuccess(storageService.ask(DeleteImage(imageId))) {
              case ImageDeleted(imageId) =>
                complete(NoContent)
            }
          }
        } ~ path("attributes") {
          get {
            onSuccess(storageService.ask(GetImageAttributes(imageId)).mapTo[Option[List[ImageAttribute]]]) {
              import spray.httpx.SprayJsonSupport._
              complete(_)
            }
          }
        } ~ path("imageinformation") {
          get {
            onSuccess(storageService.ask(GetImageInformation(imageId)).mapTo[Option[ImageInformation]]) {
              import spray.httpx.SprayJsonSupport._
              complete(_)
            }
          }
        } ~ path("anonymize") {
          post {
            import spray.httpx.SprayJsonSupport._
            entity(as[Seq[TagValue]]) { tagValues =>
              onSuccess(storageService.ask(GetDataset(imageId, true)).mapTo[Option[Attributes]]) {
                _ match {

                  case Some(dataset) =>
                    AnonymizationUtil.setAnonymous(dataset, false) // pretend not anonymized to force anonymization
                    onSuccess(anonymizationService.ask(Anonymize(dataset, tagValues)).mapTo[Attributes]) { anonDataset =>
                      onSuccess(storageService.ask(DeleteImage(imageId))) {
                        case ImageDeleted(imageId) =>
                          val source = Source(SourceType.USER, apiUser.user, apiUser.id)
                          onSuccess(storageService.ask(AddDataset(anonDataset, source, false))) {
                            case ImageAdded(image, source) =>
                              complete(NoContent)
                          }
                      }
                    }

                  case None =>
                    complete(NotFound)
                }
              }
            }
          }
        } ~ path("png") {
          parameters(
            'framenumber.as[Int] ? 1,
            'windowmin.as[Int] ? 0,
            'windowmax.as[Int] ? 0,
            'imageheight.as[Int] ? 0) { (frameNumber, min, max, height) =>
              get {
                onSuccess(storageService.ask(GetImageFrame(imageId, frameNumber, min, max, height)).mapTo[Option[Array[Byte]]]) {
                  _ match {
                    case Some(bytes) => complete(HttpEntity(`image/png`, HttpData(bytes)))
                    case None        => complete(NotFound)
                  }
                }
              }
            }
        }
      } ~ pathPrefix("anonymizationkeys") {
        pathEndOrSingleSlash {
          get {
            parameters(
              'startindex.as[Long] ? 0,
              'count.as[Long] ? 20,
              'orderby.as[String].?,
              'orderascending.as[Boolean] ? true,
              'filter.as[String].?) { (startIndex, count, orderBy, orderAscending, filter) =>
                onSuccess(anonymizationService.ask(GetAnonymizationKeys(startIndex, count, orderBy, orderAscending, filter))) {
                  case AnonymizationKeys(anonymizationKeys) =>
                    import spray.httpx.SprayJsonSupport._
                    complete(anonymizationKeys)
                }
              }
          }
        } ~ path(LongNumber) { anonymizationKeyId =>
          delete {
            onSuccess(anonymizationService.ask(RemoveAnonymizationKey(anonymizationKeyId))) {
              case AnonymizationKeyRemoved(anonymizationKeyId) =>
                complete(NoContent)
            }
          }
        }
      } ~ path("export") {
        post {
          import spray.httpx.SprayJsonSupport._
          entity(as[Seq[Long]]) { imageIds =>
            if (imageIds.isEmpty)
              complete(NoContent)
            else
              onSuccess(createTempZipFile(imageIds)) { file =>
                complete(FileName(file.getFileName.toString))
              }
          }
        } ~ get {
          parameter('filename) { fileName =>
            detach() {
              autoChunk(chunkSize) {
                respondWithHeader(`Content-Disposition`("attachment; filename=\"slicebox-export.zip\"")) {
                  complete(HttpEntity(`application/zip`, HttpData(new File(System.getProperty("java.io.tmpdir"), fileName))))
                }
              }
            }
          }
        }
      } ~ path("jpeg") {
        parameters('studyid.as[Long]) { studyId =>
          post {
            entity(as[Array[Byte]]) { jpegBytes =>
              val source = Source(SourceType.USER, apiUser.user, apiUser.id)
              onSuccess(storageService.ask(AddJpeg(jpegBytes, studyId, source))) {
                case ImageAdded(image, source) =>
                  import spray.httpx.SprayJsonSupport._
                  complete((Created, image))
              }
            }
          }
        }
      }
    }

  def createTempZipFile(imageIds: Seq[Long]): Future[Path] = {
    val futurePaths = Future.sequence(imageIds.map(imageId =>
      storageService.ask(GetImagePath(imageId)).mapTo[Option[ImagePath]]))
      .map(_.flatten.map(_.imagePath))

    futurePaths.map(paths => {
      val tempFile = Files.createTempFile("slicebox-export-", ".zip")

      val fos = Files.newOutputStream(tempFile)
      val zos = new ZipOutputStream(fos);

      paths.foreach(path => addToZipFile(path.toString, zos))

      zos.close
      fos.close

      scheduleDeleteTempFile(tempFile)

      tempFile
    })
  }

  def addToZipFile(fileName: String, zos: ZipOutputStream): Unit = {
    val path = Paths.get(fileName)
    val is = Files.newInputStream(path)
    val zipEntry = new ZipEntry(path.getFileName.toString + ".dcm")
    zos.putNextEntry(zipEntry)

    val bytes = new Array[Byte](bufferSize)
    var bytesLeft = true
    while (bytesLeft) {
      val length = is.read(bytes)
      bytesLeft = length > 0
      if (bytesLeft) zos.write(bytes, 0, length)
    }

    zos.closeEntry
    is.close
  }

  def scheduleDeleteTempFile(tempFile: Path) =
    actorRefFactory.actorOf {
      Props {
        new Actor {
          context.system.scheduler.scheduleOnce(12.hours, self, tempFile)
          def receive = {
            case file: Path =>
              Files.deleteIfExists(file)
              context.stop(self)
          }
        }
      }
    }

}
