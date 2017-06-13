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

package se.nimsa.sbx.storage

import java.nio.file.NoSuchFileException

import akka.actor.{Actor, Props}
import akka.event.{Logging, LoggingReceive}
import se.nimsa.sbx.app.GeneralProtocol.{ImageAdded, ImageDeleted}
import se.nimsa.sbx.dicom.{Contexts, DicomData, DicomUtil}
import se.nimsa.sbx.lang.NotFoundException
import se.nimsa.sbx.storage.StorageProtocol._
import se.nimsa.sbx.util.ExceptionCatching

import scala.concurrent.duration.{DurationInt, FiniteDuration}

class StorageServiceActor(storage: StorageService,
                          cleanupInterval: FiniteDuration = 6.hours,
                          cleanupMinimumFileAge: FiniteDuration = 6.hours) extends Actor with ExceptionCatching {

  import scala.collection.mutable

  val log = Logging(context.system, this)

  implicit val ec = context.dispatcher

  val exportSets = mutable.Map.empty[Long, Seq[Long]]

  case class RemoveExportSet(id: Long)

  // ensure dcm4che uses standard ImageIO image readers for parsing compressed image data
  // (see https://github.com/dcm4che/dcm4che/blob/3.3.7/dcm4che-imageio/src/main/java/org/dcm4che3/imageio/codec/ImageReaderFactory.java#L242)
  System.setProperty("dcm4che.useImageIOServiceRegistry", "true")

  log.info("Storage service started")

  def receive = LoggingReceive {

    case msg: ImageRequest => catchAndReport {
      msg match {

        case CheckDicomData(dicomData, useExtendedContexts) =>
          checkDicomData(dicomData, useExtendedContexts)
          sender ! true

        case AddDicomData(dicomData, source, image) =>
          val overwrite = storage.storeDicomData(dicomData, image)
          if (overwrite)
            log.info(s"Updated existing file with image id ${image.id}")
          else
            log.info(s"Stored file with image id ${image.id}")
          val dicomDataAdded = DicomDataAdded(image, overwrite)
          val imageAdded = ImageAdded(image, source, overwrite)
          context.system.eventStream.publish(imageAdded)
          sender ! dicomDataAdded

        case DeleteDicomData(image) =>
          val dicomDataDeleted = DicomDataDeleted(image)
          try {
            storage.deleteFromStorage(image)
            val imageDeleted = ImageDeleted(image.id)
            context.system.eventStream.publish(imageDeleted)
          } catch {
            case e: NoSuchFileException => log.info(s"DICOM file for image with id ${image.id} could not be found, no need to delete.")
          }
          sender ! dicomDataDeleted

        case CreateExportSet(imageIds) =>
          val exportSetId = if (exportSets.isEmpty) 1 else exportSets.keys.max + 1
          exportSets(exportSetId) = imageIds
          context.system.scheduler.scheduleOnce(cleanupInterval, self, RemoveExportSet(exportSetId))
          sender ! ExportSetId(exportSetId)

        case GetExportSetImageIds(exportSetId) =>
          sender ! exportSets.get(exportSetId)

        case GetImageData(image) =>
          sender ! DicomDataArray(storage.imageAsByteArray(image))

        case GetDicomData(image, withPixelData) =>
          val dicomData = storage.readDicomData(image, withPixelData)
          if (dicomData == null)
            throw new NotFoundException(s"DICOM data not found for image ID ${image.id}")
          if (dicomData.attributes == null || dicomData.metaInformation == null)
            throw new RuntimeException(s"DICOM data corrupt for image ID ${image.id}")
          sender ! dicomData

        case GetImageAttributes(image) =>
          sender ! storage.readImageAttributes(image)

        case GetImageInformation(image) =>
          sender ! storage.readImageInformation(image)

        case GetPngDataArray(image, frameNumber, windowMin, windowMax, imageHeight) =>
          sender ! PngDataArray(storage.readPngImageData(image, frameNumber, windowMin, windowMax, imageHeight))
      }
    }

    case RemoveExportSet(id) =>
      exportSets.remove(id)
  }

  def checkDicomData(dicomData: DicomData, useExtendedContexts: Boolean): Unit = {
    if (dicomData == null || dicomData.attributes == null)
      throw new IllegalArgumentException("Invalid DICOM data")
    if (dicomData.metaInformation == null)
      throw new IllegalArgumentException("DICOM data does not contain necessary meta information")
    val allowedContexts = if (useExtendedContexts) Contexts.extendedContexts else Contexts.imageDataContexts
    DicomUtil.checkContext(dicomData, allowedContexts)
  }

}
object StorageServiceActor {
  def props(storage: StorageService): Props = Props(new StorageServiceActor(storage))
}
