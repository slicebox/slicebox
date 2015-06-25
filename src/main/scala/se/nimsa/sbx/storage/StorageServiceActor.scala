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
import se.nimsa.sbx.dicom.DicomHierarchy._
import se.nimsa.sbx.dicom.DicomPropertyValue._
import se.nimsa.sbx.dicom.DicomUtil._
import akka.dispatch.ExecutionContexts
import java.util.concurrent.Executors
import se.nimsa.sbx.log.SbxLog
import scala.slick.jdbc.JdbcBackend.Session

class StorageServiceActor(dbProps: DbProps, storage: Path) extends Actor with ExceptionCatching {

  import context.system

  val log = Logging(context.system, this)

  val db = dbProps.db
  val dao = new MetaDataDAO(dbProps.driver)
  val propertiesDao = new PropertiesDAO(dbProps.driver)

  setupDb()

  implicit val ec = ExecutionContexts.fromExecutor(Executors.newWorkStealingPool())

  override def preStart {
    context.system.eventStream.subscribe(context.self, classOf[DatasetReceived])
    context.system.eventStream.subscribe(context.self, classOf[FileReceived])
  }

  log.info("Storage service started")

  def receive = LoggingReceive {

    case FileReceived(path, sourceType, sourceId) =>
      val dataset = loadDataset(path, true)
      if (dataset != null)
        if (checkSopClass(dataset)) {
          try {
            val (image, overwrite) = storeDataset(dataset, sourceType, sourceId)
            if (!overwrite)
              SbxLog.info("Storage", s"Stored file ${path.toString} as ${dataset.getString(Tag.SOPInstanceUID)}")
          } catch {
            case e: IllegalArgumentException =>
              SbxLog.error("Storage", e.getMessage)
          }
        } else {
          SbxLog.info("Storage", s"Received file ${path.toString} with unsupported SOP Class UID ${dataset.getString(Tag.SOPClassUID)}, skipping")
        }
      else
        SbxLog.info("Storage", s"File $path is not a DICOM file, skipping")

    case DatasetReceived(dataset, sourceType, sourceId) =>
      try {
        val (image, overwrite) = storeDataset(dataset, sourceType, sourceId)
        if (!overwrite)
          SbxLog.info("Storage", "Stored dataset: " + dataset.getString(Tag.SOPInstanceUID))
      } catch {
        case e: IllegalArgumentException =>
          SbxLog.error("Storage", e.getMessage)
      }

    case AddDataset(dataset, sourceType, sourceId) =>
      catchAndReport {
        if (dataset == null)
          throw new IllegalArgumentException("Invalid dataset")
        val (image, overwrite) = storeDatasetTryCatchThrow(dataset, sourceType, sourceId)
        sender ! ImageAdded(image)
      }

    case DeleteImage(imageId) =>
      catchAndReport {
        db.withSession { implicit session =>
          dao.imageById(imageId).foreach { image =>
            val imageFile = propertiesDao.imageFileForImage(image.id)
              .foreach(deleteFromStorage(_))
            dao.deleteFully(image)
          }
          sender ! ImageDeleted(imageId)
        }
      }

    case msg: ImageRequest => catchAndReport {
      msg match {

        case GetDataset(imageId) =>
          db.withSession { implicit session =>
            sender ! propertiesDao.imageFileForImage(imageId)
              .flatMap(imageFile =>
                Option(readDataset(imageFile.fileName.value, true)))
          }

        case GetImageAttributes(imageId) =>
          db.withSession { implicit session =>
            propertiesDao.imageFileForImage(imageId) match {
              case Some(imageFile) =>
                Future {
                  Some(readImageAttributes(imageFile.fileName.value))
                }.pipeTo(sender)
              case None =>
                sender ! None
            }
          }

        case GetImageInformation(imageId) =>
          db.withSession { implicit session =>
            propertiesDao.imageFileForImage(imageId) match {
              case Some(imageFile) =>
                Future {
                  Some(readImageInformation(imageFile.fileName.value))
                }.pipeTo(sender)
              case None =>
                sender ! None
            }
          }

        case GetImageFrame(imageId, frameNumber, windowMin, windowMax, imageHeight) =>
          db.withSession { implicit session =>
            propertiesDao.imageFileForImage(imageId) match {
              case Some(imageFile) =>
                Future {
                  Some(readImageFrame(imageFile.fileName.value, frameNumber, windowMin, windowMax, imageHeight))
                }.pipeTo(sender)
              case None =>
                sender ! None
            }
          }

      }
    }

    case msg: MetaDataQuery => catchAndReport {
      msg match {
        case GetPatients(startIndex, count, orderBy, orderAscending, filter, sourceType, sourceId) =>
          db.withSession { implicit session =>
            sender ! Patients(propertiesDao.patients(startIndex, count, orderBy, orderAscending, filter, sourceType, sourceId))
          }

        case GetStudies(startIndex, count, patientId, sourceType, sourceId) =>
          db.withSession { implicit session =>
            sender ! Studies(propertiesDao.studiesForPatient(startIndex, count, patientId, sourceType, sourceId))
          }

        case GetSeries(startIndex, count, studyId, sourceType, sourceId) =>
          db.withSession { implicit session =>
            sender ! SeriesCollection(propertiesDao.seriesForStudy(startIndex, count, studyId, sourceType, sourceId))
          }

        case GetImages(startIndex, count, seriesId) =>
          db.withSession { implicit session =>
            sender ! Images(dao.imagesForSeries(startIndex, count, seriesId))
          }

        case GetImageFile(imageId) =>
          db.withSession { implicit session =>
            sender ! propertiesDao.imageFileForImage(imageId)
          }

        case GetFlatSeries(startIndex, count, orderBy, orderAscending, filter, sourceType, sourceId) =>
          db.withSession { implicit session =>
            sender ! FlatSeriesCollection(propertiesDao.flatSeries(startIndex, count, orderBy, orderAscending, filter, sourceType, sourceId))
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

        case GetSeriesSource(seriesId) =>
          db.withSession { implicit session =>
            sender ! propertiesDao.seriesSourceById(seriesId)
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

  def storeDatasetTryCatchThrow(dataset: Attributes, sourceType: SourceType, sourceId: Long): (Image, Boolean) =
    try {
      storeDataset(dataset, sourceType, sourceId)
    } catch {
      case e: IllegalArgumentException =>
        SbxLog.error("Storage", e.getMessage)
        throw e
    }

  def storeDataset(dataset: Attributes, sourceType: SourceType, sourceId: Long): (Image, Boolean) = {
    val name = fileName(dataset)
    val storedPath = storage.resolve(name)

    val overwrite = Files.exists(storedPath)

    val seriesSource = SeriesSource(-1, sourceType, sourceId)

    db.withSession { implicit session =>

      val patient = datasetToPatient(dataset)
      val study = datasetToStudy(dataset)
      val equipment = datasetToEquipment(dataset)
      val frameOfReference = datasetToFrameOfReference(dataset)
      val series = datasetToSeries(dataset)
      val image = datasetToImage(dataset)
      val imageFile = ImageFile(-1, FileName(name), sourceType, sourceId)

      val dbPatientMaybe = dao.patientByNameAndID(patient)
      val dbStudyMaybe = dao.studyByUid(study)
      val dbEquipmentMaybe = dao.equipmentByManufacturerAndStationName(equipment)
      val dbFrameOfReferenceMaybe = dao.frameOfReferenceByUid(frameOfReference)
      val dbSeriesMaybe = dao.seriesByUid(series)
      val dbImageMaybe = dao.imageByUid(image)
      val dbImageFileMaybe = propertiesDao.imageFileByFileName(imageFile)
      val dbSeriesSourceMaybe = dbSeriesMaybe.flatMap(dbSeries => propertiesDao.seriesSourceById(dbSeries.id))

      // relationships are one to many from top to bottom. There must therefore not be a new instance followed by an existing
      val hierarchyIsWellDefined = !(
        dbPatientMaybe.isEmpty && dbStudyMaybe.isDefined ||
        dbStudyMaybe.isEmpty && dbSeriesMaybe.isDefined ||
        dbEquipmentMaybe.isEmpty && dbSeriesMaybe.isDefined ||
        dbFrameOfReferenceMaybe.isEmpty && dbSeriesMaybe.isDefined ||
        dbSeriesSourceMaybe.isEmpty && dbSeriesMaybe.isDefined ||
        dbSeriesMaybe.isEmpty && dbImageMaybe.isDefined ||
        dbImageMaybe.isEmpty && dbImageFileMaybe.isDefined)

      if (hierarchyIsWellDefined) {

        val dbPatient = dbPatientMaybe.getOrElse(dao.insert(patient))
        val dbEquipment = dbEquipmentMaybe.getOrElse(dao.insert(equipment))
        val dbFrameOfReference = dbFrameOfReferenceMaybe.getOrElse(dao.insert(frameOfReference))
        val dbStudy = dbStudyMaybe.getOrElse(dao.insert(study.copy(patientId = dbPatient.id)))
        val dbSeries = dbSeriesMaybe.getOrElse(dao.insert(series.copy(
          studyId = dbStudy.id,
          equipmentId = dbEquipment.id,
          frameOfReferenceId = dbFrameOfReference.id)))
        val dbSeriesSource = dbSeriesSourceMaybe.getOrElse(propertiesDao.insertSeriesSource(seriesSource.copy(id = dbSeries.id)))
        val dbImage = dbImageMaybe.getOrElse(dao.insert(image.copy(seriesId = dbSeries.id)))
        dbImageFileMaybe.getOrElse(propertiesDao.insertImageFile(imageFile.copy(id = dbImage.id)))

        try {
          saveDataset(dataset, storedPath)
        } catch {
          case e: Exception =>
            SbxLog.error("Storage", "Dataset file could not be stored: " + e.getMessage)
        }

        (dbImage, overwrite)

      } else

        throw new IllegalArgumentException(s"Dataset already stored, but with different values for one or more DICOM attributes. Skipping.")

    }
  }

  def fileName(dataset: Attributes): String = dataset.getString(Tag.SOPInstanceUID)

  def deleteFromStorage(imageFiles: Seq[ImageFile]): Unit = imageFiles foreach (deleteFromStorage(_))
  def deleteFromStorage(imageFile: ImageFile): Unit = deleteFromStorage(storage.resolve(imageFile.fileName.value))
  def deleteFromStorage(filePath: Path): Unit = {
    Files.delete(filePath)
    log.debug("Deleted file " + filePath)
  }

  def readDataset(fileName: String, withPixelData: Boolean): Attributes = loadDataset(storage.resolve(fileName), withPixelData)

  def readImageAttributes(fileName: String): List[ImageAttribute] = {
    val filePath = storage.resolve(fileName)
    val dataset = loadDataset(filePath, false)
    readImageAttributes(dataset, 0, "")
  }

  def readImageAttributes(dataset: Attributes, depth: Int, path: String): List[ImageAttribute] = {
    val attributesBuffer = ListBuffer.empty[ImageAttribute]
    if (dataset != null) {
      dataset.accept(new Visitor() {
        override def visit(attrs: Attributes, tag: Int, vr: VR, value: AnyRef): Boolean = {
          val length = lengthOf(attrs.getBytes(tag))
          val group = TagUtils.toHexString(TagUtils.groupNumber(tag)).substring(4)
          val element = TagUtils.toHexString(TagUtils.elementNumber(tag)).substring(4)
          val name = Keyword.valueOf(tag)
          val vrName = vr.name
          val (content, multiplicity) = vr match {
            case VR.OW | VR.OF | VR.OB =>
              (s"< Binary data ($length bytes) >", 1)
            case _ =>
              val rawStrings = getStrings(attrs, tag)
              val truncatedStrings = if (rawStrings.length > 32) rawStrings.slice(0, 32) :+ " ..." else rawStrings
              val strings = truncatedStrings.map(s => if (s.length() > 1024) s.substring(0, 1024) + " ..." else s)
              (toSingleString(strings), strings.length)
          }
          attributesBuffer += ImageAttribute(group, element, vrName, length, multiplicity, depth, path, name, content)
          if (vr == VR.SQ) {
            val nextPath = if (path.isEmpty()) name else path + '/' + name
            attributesBuffer ++= readImageAttributes(attrs.getNestedDataset(tag), depth + 1, nextPath)
          }
          true
        }
      }, false)
    }
    attributesBuffer.toList
  }

  def lengthOf(bytes: Array[Byte]) =
    if (bytes == null)
      0
    else
      bytes.length

  def getStrings(attrs: Attributes, tag: Int) = {
    val s = attrs.getStrings(tag)
    if (s == null || s.isEmpty) Array("") else s
  }

  def toSingleString(strings: Array[String]) =
    if (strings.length == 1)
      strings(0)
    else
      "[" + strings.tail.foldLeft(strings.head)((single, s) => single + "," + s) + "]"

  def readImageInformation(fileName: String): ImageInformation = {
    val path = storage.resolve(fileName)
    val dataset = loadDataset(path, false)
    val instanceNumber = dataset.getInt(Tag.InstanceNumber, 1)
    val imageIndex = dataset.getInt(Tag.ImageIndex, 1)
    val frameIndex = if (instanceNumber > imageIndex) instanceNumber else imageIndex
    ImageInformation(
      dataset.getInt(Tag.NumberOfFrames, 1),
      frameIndex,
      dataset.getInt(Tag.SmallestImagePixelValue, 0),
      dataset.getInt(Tag.LargestImagePixelValue, 0))
  }

  def readImageFrame(fileName: String, frameNumber: Int, windowMin: Int, windowMax: Int, imageHeight: Int): Array[Byte] = {
    val file = storage.resolve(fileName).toFile
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
  }

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

  def setupDb() =
    db.withSession { implicit session =>
      dao.create
      propertiesDao.create
    }

}

object StorageServiceActor {
  def props(dbProps: DbProps, storage: Path): Props = Props(new StorageServiceActor(dbProps, storage))
}
