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

package se.nimsa.sbx.storage

import java.nio.file.NoSuchFileException

import akka.actor.{Actor, Props}
import akka.event.{Logging, LoggingReceive}
import se.nimsa.sbx.app.GeneralProtocol.ImageAdded
import se.nimsa.sbx.dicom.{Contexts, DicomData, DicomUtil}
import se.nimsa.sbx.storage.StorageProtocol._
import se.nimsa.sbx.util.ExceptionCatching

import scala.concurrent.duration.DurationInt
import scala.util.control.NonFatal

class StorageServiceActor(storage: StorageService) extends Actor with ExceptionCatching {

  import scala.collection.mutable

  val log = Logging(context.system, this)

  implicit val ec = context.dispatcher

  val exportSets = mutable.Map.empty[Long, Seq[Long]]

  case class RemoveExportSet(id: Long)

  log.info("Storage service started")

  def receive = LoggingReceive {

    case msg: ImageRequest => catchAndReport {
      msg match {

        case CheckDataset(dicomData, useExtendedContexts) =>
          checkDataset(dicomData, useExtendedContexts)
          sender ! true

        case AddDataset(dicomData, source, image) =>
          val overwrite = storage.storeDataset(dicomData, image)
          if (overwrite)
            log.info(s"Updated existing file with image id ${image.id}")
          else
            log.info(s"Stored file with image id ${image.id}")
          val datasetAdded = DatasetAdded(image, overwrite)
          val imageAdded = ImageAdded(image, source, overwrite)
          context.system.eventStream.publish(imageAdded)
          sender ! datasetAdded

        case DeleteDataset(image) =>
          val datasetDeleted = DatasetDeleted(image)
          try {
            storage.deleteFromStorage(image)
            context.system.eventStream.publish(datasetDeleted)
          } catch {
            case e: NoSuchFileException => log.info(s"DICOM file for image with id ${image.id} could not be found, no need to delete.")
          }
          sender ! datasetDeleted

        case CreateExportSet(imageIds) =>
          val exportSetId = if (exportSets.isEmpty) 1 else exportSets.keys.max + 1
          exportSets(exportSetId) = imageIds
          context.system.scheduler.scheduleOnce(12.hours, self, RemoveExportSet(exportSetId))
          sender ! ExportSetId(exportSetId)

        case GetExportSetImageIds(exportSetId) =>
          sender ! exportSets.get(exportSetId)

        case GetImageData(image) =>
          val data = storage.imageAsByteArray(image).map(ImageData(_))
          sender ! data

        case GetDataset(image, withPixelData, useBulkDataURI) =>
          sender ! storage.readDataset(image, withPixelData, useBulkDataURI)

        case GetImageAttributes(image) =>
          sender ! storage.readImageAttributes(image)

        case GetImageInformation(image) =>
          sender ! storage.readImageInformation(image)

        case GetPngImageData(image, frameNumber, windowMin, windowMax, imageHeight) =>
          val pngResponse =
            try
              storage.readPngImageData(image, frameNumber, windowMin, windowMax, imageHeight).map(PngImageData(_))
            catch {
              case NonFatal(e1) =>
                try
                  storage.readSecondaryCaptureJpeg(image, imageHeight).map(PngImageData(_))
                catch {
                  case NonFatal(e2) =>
                    log.debug(s"Could not create PNG image data for image with ID ${image.id}, returning empty response")
                    Some(PngImageDataNotAvailable)
                }
            }
          sender ! pngResponse

      }
    }

    case RemoveExportSet(id) =>
      exportSets.remove(id)

  }

  def checkDataset(dicomData: DicomData, useExtendedContexts: Boolean): Unit = {
    if (dicomData == null || dicomData.attributes == null || dicomData.metaInformation == null)
      throw new IllegalArgumentException("Invalid DICOM data")
    val allowedContexts = if (useExtendedContexts) Contexts.extendedContexts else Contexts.imageDataContexts
    DicomUtil.checkContext(dicomData.metaInformation, allowedContexts)
  }

}

object StorageServiceActor {
  def props(storage: StorageService): Props = Props(new StorageServiceActor(storage))
}
