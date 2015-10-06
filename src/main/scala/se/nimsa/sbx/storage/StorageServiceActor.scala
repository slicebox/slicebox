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

package se.nimsa.sbx.storage

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.NoSuchFileException
import scala.collection.mutable.ListBuffer
import scala.concurrent.Future
import akka.actor.Actor
import akka.actor.Props
import akka.event.Logging
import akka.event.LoggingReceive
import akka.pattern.pipe
import org.dcm4che3.data.Attributes
import org.dcm4che3.data.Attributes.Visitor
import org.dcm4che3.data.Tag
import org.dcm4che3.data.VR
import org.dcm4che3.imageio.plugins.dcm.DicomImageReadParam
import org.dcm4che3.data.Keyword
import org.dcm4che3.util.TagUtils
import se.nimsa.sbx.app.DbProps
import se.nimsa.sbx.util.ExceptionCatching
import java.awt.image.BufferedImage
import java.awt.Color
import java.awt.RenderingHints
import javax.imageio.ImageIO
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Date
import se.nimsa.sbx.storage.StorageProtocol._
import se.nimsa.sbx.seriestype.SeriesTypeProtocol.SeriesTypes
import se.nimsa.sbx.dicom.DicomHierarchy._
import se.nimsa.sbx.dicom.DicomPropertyValue._
import se.nimsa.sbx.dicom.DicomUtil
import se.nimsa.sbx.dicom.DicomUtil._
import se.nimsa.sbx.dicom.ImageAttribute
import se.nimsa.sbx.lang.NotFoundException
import akka.dispatch.ExecutionContexts
import java.util.concurrent.Executors
import se.nimsa.sbx.log.SbxLog
import scala.slick.jdbc.JdbcBackend.Session
import se.nimsa.sbx.app.GeneralProtocol._

class StorageServiceActor(dbProps: DbProps, storage: Path) extends Actor with ExceptionCatching {

  import context.system

  val log = Logging(context.system, this)

  val db = dbProps.db
  val dao = new MetaDataDAO(dbProps.driver)
  val propertiesDao = new PropertiesDAO(dbProps.driver)

  implicit val ec = ExecutionContexts.fromExecutor(Executors.newWorkStealingPool())

  override def preStart {
    context.system.eventStream.subscribe(context.self, classOf[DatasetReceived])
    context.system.eventStream.subscribe(context.self, classOf[FileReceived])
  }

  log.info("Storage service started")

  def receive = LoggingReceive {

    case FileReceived(path, source) =>
      val dataset = loadDataset(path, true)
      if (dataset != null)
        if (checkSopClass(dataset)) {
          try {
            val (image, overwrite) = storeDataset(dataset, source)
            if (!overwrite)
              log.debug(s"Stored file ${path.toString} as ${dataset.getString(Tag.SOPInstanceUID)}")
            context.system.eventStream.publish(ImageAdded(image, source))
          } catch {
            case e: IllegalArgumentException =>
              SbxLog.error("Storage", e.getMessage)
          }
        } else {
          SbxLog.info("Storage", s"Received file ${path.toString} with unsupported SOP Class UID ${dataset.getString(Tag.SOPClassUID)}, skipping")
        }
      else
        log.debug("Storage", s"File $path is not a DICOM file, skipping")

    case DatasetReceived(dataset, source) =>
      try {
        val (image, overwrite) = storeDataset(dataset, source)
        if (!overwrite)
          log.debug("Storage", "Stored dataset: " + dataset.getString(Tag.SOPInstanceUID))
        context.system.eventStream.publish(ImageAdded(image, source))
      } catch {
        case e: IllegalArgumentException =>
          SbxLog.error("Storage", e.getMessage)
      }

    case AddDataset(dataset, source) =>
      catchAndReport {
        if (dataset == null)
          throw new IllegalArgumentException("Invalid dataset")
        val (image, overwrite) = storeDatasetTryCatchThrow(dataset, source)
        sender ! ImageAdded(image, source)
        context.system.eventStream.publish(ImageAdded(image, source))
      }

    case DeleteImage(imageId) =>
      catchAndReport {
        db.withSession { implicit session =>
          dao.imageById(imageId).foreach { image =>
            try {
              deleteFromStorage(image.id)
            } catch {
              case e: NoSuchFileException =>
                log.debug("Storage", s"DICOM file for image with id $imageId could not be found, no need to delete.")
                null
            }
            propertiesDao.deleteFully(image)
          }
          sender ! ImageDeleted(imageId)
        }
      }

    case msg: PropertiesRequest => catchAndReport {
      msg match {

        case AddSeriesTypeToSeries(seriesType, series) =>
          db.withSession { implicit session =>
            val seriesSeriesType = propertiesDao.insertSeriesSeriesType(SeriesSeriesType(series.id, seriesType.id))
            sender ! SeriesTypeAddedToSeries(seriesSeriesType)
          }

        case RemoveSeriesTypesFromSeries(series) =>
          db.withSession { implicit session =>
            propertiesDao.removeSeriesTypesForSeriesId(series.id)
            sender ! SeriesTypesRemovedFromSeries(series)
          }

        case GetSeriesTags =>
          sender ! SeriesTags(getSeriesTags)

        case GetSourceForSeries(seriesId) =>
          db.withSession { implicit session =>
            sender ! propertiesDao.seriesSourceById(seriesId)
          }

        case GetSeriesTypesForSeries(seriesId) =>
          val seriesTypes = getSeriesTypesForSeries(seriesId)
          sender ! SeriesTypes(seriesTypes)

        case GetSeriesTagsForSeries(seriesId) =>
          val seriesTags = getSeriesTagsForSeries(seriesId)
          sender ! SeriesTags(seriesTags)

        case AddSeriesTagToSeries(seriesTag, seriesId) =>
          db.withSession { implicit session =>
            dao.seriesById(seriesId).getOrElse {
              throw new NotFoundException("Series not found")
            }
            val dbSeriesTag = propertiesDao.addAndInsertSeriesTagForSeriesId(seriesTag, seriesId)
            sender ! SeriesTagAddedToSeries(dbSeriesTag)
          }

        case RemoveSeriesTagFromSeries(seriesTagId, seriesId) =>
          db.withSession { implicit session =>
            propertiesDao.removeAndCleanupSeriesTagForSeriesId(seriesTagId, seriesId)
            sender ! SeriesTagRemovedFromSeries(seriesId)
          }
      }
    }

    case msg: ImageRequest => catchAndReport {
      msg match {

        case GetImagePath(imageId) =>
          sender ! imagePathForId(imageId)

        case GetDataset(imageId) =>
          sender ! readDataset(imageId, true)

        case GetImageAttributes(imageId) =>
          Future {
            readImageAttributes(imageId)
          }.pipeTo(sender)

        case GetImageInformation(imageId) =>
          Future {
            readImageInformation(imageId)
          }.pipeTo(sender)

        case GetImageFrame(imageId, frameNumber, windowMin, windowMax, imageHeight) =>
          Future {
            readImageFrame(imageId, frameNumber, windowMin, windowMax, imageHeight)
          }.pipeTo(sender)

      }
    }

    case msg: MetaDataQuery => catchAndReport {
      msg match {
        case GetPatients(startIndex, count, orderBy, orderAscending, filter, sourceIds, seriesTypeIds, seriesTagIds) =>
          db.withSession { implicit session =>
            sender ! Patients(propertiesDao.patients(startIndex, count, orderBy, orderAscending, filter, sourceIds, seriesTypeIds, seriesTagIds))
          }

        case GetStudies(startIndex, count, patientId, sourceRefs, seriesTypeIds, seriesTagIds) =>
          db.withSession { implicit session =>
            sender ! Studies(propertiesDao.studiesForPatient(startIndex, count, patientId, sourceRefs, seriesTypeIds, seriesTagIds))
          }

        case GetSeries(startIndex, count, studyId, sourceRefs, seriesTypeIds, seriesTagIds) =>
          db.withSession { implicit session =>
            sender ! SeriesCollection(propertiesDao.seriesForStudy(startIndex, count, studyId, sourceRefs, seriesTypeIds, seriesTagIds))
          }

        case GetImages(startIndex, count, seriesId) =>
          db.withSession { implicit session =>
            sender ! Images(dao.imagesForSeries(startIndex, count, seriesId))
          }

        case GetFlatSeries(startIndex, count, orderBy, orderAscending, filter, sourceRefs, seriesTypeIds, seriesTagIds) =>
          db.withSession { implicit session =>
            sender ! FlatSeriesCollection(propertiesDao.flatSeries(startIndex, count, orderBy, orderAscending, filter, sourceRefs, seriesTypeIds, seriesTagIds))
          }

        case GetPatient(patientId) =>
          db.withSession { implicit session =>
            sender ! dao.patientById(patientId)
          }

        case GetStudy(studyId) =>
          db.withSession { implicit session =>
            sender ! dao.studyById(studyId)
          }

        case GetSingleSeries(seriesId) =>
          db.withSession { implicit session =>
            sender ! dao.seriesById(seriesId)
          }

        case GetAllSeries =>
          db.withSession { implicit session =>
            sender ! SeriesCollection(dao.series)
          }

        case GetImage(imageId) =>
          db.withSession { implicit session =>
            sender ! dao.imageById(imageId)
          }

        case GetSingleFlatSeries(seriesId) =>
          db.withSession { implicit session =>
            sender ! dao.flatSeriesById(seriesId)
          }

        case QueryPatients(query) =>
          db.withSession { implicit session =>
            sender ! Patients(dao.queryPatients(query.startIndex, query.count, query.orderBy, query.orderAscending, query.queryProperties))
          }

        case QueryStudies(query) =>
          db.withSession { implicit session =>
            sender ! Studies(dao.queryStudies(query.startIndex, query.count, query.orderBy, query.orderAscending, query.queryProperties))
          }

        case QuerySeries(query) =>
          db.withSession { implicit session =>
            sender ! SeriesCollection(dao.querySeries(query.startIndex, query.count, query.orderBy, query.orderAscending, query.queryProperties))
          }

        case QueryImages(query) =>
          db.withSession { implicit session =>
            sender ! Images(dao.queryImages(query.startIndex, query.count, query.orderBy, query.orderAscending, query.queryProperties))
          }

      }
    }

  }

  def getSeriesTags =
    db.withSession { implicit session =>
      propertiesDao.listSeriesTags
    }

  def getSeriesTypesForSeries(seriesId: Long) =
    db.withSession { implicit session =>
      propertiesDao.seriesTypesForSeries(seriesId)
    }

  def getSeriesTagsForSeries(seriesId: Long) =
    db.withSession { implicit session =>
      propertiesDao.seriesTagsForSeries(seriesId)
    }

  def storeDatasetTryCatchThrow(dataset: Attributes, source: Source): (Image, Boolean) =
    try {
      storeDataset(dataset, source)
    } catch {
      case e: IllegalArgumentException =>
        SbxLog.error("Storage", e.getMessage)
        throw e
    }

  def storeDataset(dataset: Attributes, source: Source): (Image, Boolean) =
    db.withSession { implicit session =>

      val patient = datasetToPatient(dataset)
      val study = datasetToStudy(dataset)
      val series = datasetToSeries(dataset)
      val seriesSource = SeriesSource(-1, source)
      val image = datasetToImage(dataset)

      val dbPatient = dao.patientByNameAndID(patient)
        .getOrElse(dao.insert(patient))
      val dbStudy = dao.studyByUidAndPatient(study, dbPatient)
        .getOrElse(dao.insert(study.copy(patientId = dbPatient.id)))
      val dbSeries = dao.seriesByUidAndStudy(series, dbStudy)
        .getOrElse(dao.insert(series.copy(studyId = dbStudy.id)))
      val dbSeriesSource = propertiesDao.seriesSourceById(dbSeries.id)
        .getOrElse(propertiesDao.insertSeriesSource(seriesSource.copy(id = dbSeries.id)))
      val dbImage = dao.imageByUidAndSeries(image, dbSeries)
        .getOrElse(dao.insert(image.copy(seriesId = dbSeries.id)))

      if (dbSeriesSource.source.sourceType != source.sourceType || dbSeriesSource.source.sourceId != source.sourceId)
        SbxLog.warn("Storage", s"Existing series source does not match source of added image (${dbSeriesSource.source} vs $source). Source of added image will be lost.")

      val storedPath = filePath(dbImage)
      val overwrite = Files.exists(storedPath)

      try {
        saveDataset(dataset, storedPath)
      } catch {
        case e: Exception =>
          SbxLog.error("Storage", "Dataset file could not be stored: " + e.getMessage)
      }

      (dbImage, overwrite)
    }

  def filePath(image: Image) = storage.resolve(fileName(image))
  def fileName(image: Image) = image.id.toString

  def imagePathForId(imageId: Long): Option[ImagePath] =
    db.withSession { implicit session =>
      dao.imageById(imageId)
        .map(filePath(_))
        .filter(Files.exists(_))
        .filter(Files.isReadable(_))
        .map(ImagePath(_))
    }

  def deleteFromStorage(imageIds: Seq[Long]): Unit = imageIds foreach (deleteFromStorage(_))
  def deleteFromStorage(imageId: Long): Unit =
    imagePathForId(imageId).foreach(imagePath => {
      Files.delete(imagePath.imagePath)
      log.debug("Deleted file " + imagePath.imagePath)
    })

  def readDataset(imageId: Long, withPixelData: Boolean): Option[Attributes] =
    imagePathForId(imageId).map(imagePath =>
      loadDataset(imagePath.imagePath, withPixelData))

  def readImageAttributes(imageId: Long): Option[List[ImageAttribute]] =
    imagePathForId(imageId).map(imagePath => {
      val dataset = loadDataset(imagePath.imagePath, false)
      DicomUtil.readImageAttributes(dataset)
    })

  def readImageInformation(imageId: Long): Option[ImageInformation] =
    imagePathForId(imageId).map(imagePath => {
      val dataset = loadDataset(imagePath.imagePath, false)
      val instanceNumber = dataset.getInt(Tag.InstanceNumber, 1)
      val imageIndex = dataset.getInt(Tag.ImageIndex, 1)
      val frameIndex = if (instanceNumber > imageIndex) instanceNumber else imageIndex
      ImageInformation(
        dataset.getInt(Tag.NumberOfFrames, 1),
        frameIndex,
        dataset.getInt(Tag.SmallestImagePixelValue, 0),
        dataset.getInt(Tag.LargestImagePixelValue, 0))
    })

  def readImageFrame(imageId: Long, frameNumber: Int, windowMin: Int, windowMax: Int, imageHeight: Int): Option[Array[Byte]] =
    imagePathForId(imageId).map(imagePath => {
      val file = imagePath.imagePath.toFile
      val iis = ImageIO.createImageInputStream(file)
      try {
        val imageReader = ImageIO.getImageReadersByFormatName("DICOM").next
        imageReader.setInput(iis)
        val param = imageReader.getDefaultReadParam().asInstanceOf[DicomImageReadParam]
        if (windowMin < windowMax) {
          param.setWindowCenter((windowMax - windowMin) / 2)
          param.setWindowWidth(windowMax - windowMin)
        }
        val bi = try {
          scaleImage(imageReader.read(frameNumber - 1, param), imageHeight)
        } catch {
          case e: Exception => throw new IllegalArgumentException(e.getMessage)
        }
        val baos = new ByteArrayOutputStream
        ImageIO.write(bi, "png", baos)
        baos.close()
        baos.toByteArray
      } finally {
        iis.close()
      }
    })

  def scaleImage(image: BufferedImage, imageHeight: Int): BufferedImage = {
    val ratio = imageHeight / image.getHeight.asInstanceOf[Double]
    if (ratio != 0.0 && ratio != 1.0) {
      val imageWidth = (image.getWidth * ratio).asInstanceOf[Int]
      val resized = new BufferedImage(imageWidth, imageHeight, image.getType)
      val g = resized.createGraphics()
      g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
      g.drawImage(image, 0, 0, imageWidth, imageHeight, 0, 0, image.getWidth, image.getHeight, null);
      g.dispose()
      resized
    } else
      image
  }

}

object StorageServiceActor {
  def props(dbProps: DbProps, storage: Path): Props = Props(new StorageServiceActor(dbProps, storage))
}
