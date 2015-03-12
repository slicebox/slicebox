package se.vgregion.dicom

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import scala.collection.mutable.ListBuffer
import scala.concurrent.Future
import akka.actor.Actor
import akka.actor.Props
import akka.actor.actorRef2Scala
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
import se.vgregion.app.DbProps
import se.vgregion.dicom.DicomProtocol.DatasetReceived
import se.vgregion.util.ExceptionCatching
import java.awt.image.BufferedImage
import java.awt.Color
import java.awt.RenderingHints
import javax.imageio.ImageIO
import java.io.ByteArrayOutputStream
import java.io.File
import DicomProtocol._
import DicomHierarchy._
import DicomPropertyValue._
import DicomUtil._
import akka.dispatch.ExecutionContexts
import java.util.concurrent.Executors

class DicomStorageActor(dbProps: DbProps, storage: Path) extends Actor with ExceptionCatching {
  val log = Logging(context.system, this)

  val db = dbProps.db
  val dao = new DicomMetaDataDAO(dbProps.driver)

  setupDb()

  implicit val ec = ExecutionContexts.fromExecutor(Executors.newWorkStealingPool())

  override def preStart {
    context.system.eventStream.subscribe(context.self, classOf[DatasetReceived])
    context.system.eventStream.subscribe(context.self, classOf[FileReceived])
  }

  def receive = LoggingReceive {

    case FileReceived(path) =>
      val dataset = loadDataset(path, true)
      if (dataset != null)
        if (checkSopClass(dataset)) {
          val image = storeDataset(dataset)
          log.info("Stored dataset: " + dataset.getString(Tag.SOPInstanceUID))
        } else
          log.info(s"Received file with unsupported SOP Class UID ${dataset.getString(Tag.SOPClassUID)}, skipping")
      else
        log.info(s"File $path is not a DICOM file")

    case DatasetReceived(dataset) =>
      val image = storeDataset(dataset)
      log.debug("Stored dataset: " + dataset.getString(Tag.SOPInstanceUID))

    case AddDataset(dataset) =>
      catchAndReport {
        if (dataset == null)
          throw new IllegalArgumentException("Invalid dataset")
        val image = storeDataset(dataset)
        sender ! ImageAdded(image)
      }

    case msg: MetaDataUpdate => catchAndReport {
      msg match {

        case DeleteImage(imageId) =>
          db.withSession { implicit session =>
            val imageFiles = dao.imageFileForImage(imageId).toList
            dao.deleteImage(imageId)
            deleteFromStorage(imageFiles)
            sender ! ImageFilesDeleted(imageFiles)
          }

        case DeleteSeries(seriesId) =>
          db.withSession { implicit session =>
            val imageFiles = dao.imageFilesForSeries(Seq(seriesId))
            dao.deleteSeries(seriesId)
            deleteFromStorage(imageFiles)
            sender ! ImageFilesDeleted(imageFiles)
          }

        case DeleteStudy(studyId) =>
          db.withSession { implicit session =>
            val imageFiles = dao.imageFilesForStudy(studyId)
            dao.deleteStudy(studyId)
            deleteFromStorage(imageFiles)
            sender ! ImageFilesDeleted(imageFiles)
          }

        case DeletePatient(patientId) =>
          db.withSession { implicit session =>
            val imageFiles = dao.imageFilesForPatient(patientId)
            dao.deletePatient(patientId)
            deleteFromStorage(imageFiles)
            sender ! ImageFilesDeleted(imageFiles)
          }

      }
    }

    case msg: ImageRequest => catchAndReport {
      msg match {

        case GetImageAttributes(imageId) =>
          db.withSession { implicit session =>
            dao.imageFileForImage(imageId) match {
              case Some(imageFile) =>
                readImageAttributes(imageFile.fileName.value).pipeTo(sender)
              case None =>
                throw new IllegalArgumentException(s"No file found for image $imageId")
            }
          }

        case GetImageInformation(imageId) =>
          db.withSession { implicit session =>
            dao.imageFileForImage(imageId) match {
              case Some(imageFile) =>
                sender ! readImageInformation(imageFile.fileName.value)
              case None =>
                throw new IllegalArgumentException(s"No file found for image $imageId")
            }
          }

        case GetImageFrame(imageId, frameNumber, windowMin, windowMax, imageHeight) =>
          db.withSession { implicit session =>
            dao.imageFileForImage(imageId) match {
              case Some(imageFile) =>
                Future {
                  readImageFrame(imageFile.fileName.value, frameNumber, windowMin, windowMax, imageHeight)
                }.pipeTo(sender)
              case None =>
                throw new IllegalArgumentException(s"No file found for image $imageId")
            }
          }

      }
    }

    case msg: MetaDataQuery => catchAndReport {
      msg match {
        case GetPatients(startIndex, count, orderBy, orderAscending, filter) =>
          db.withSession { implicit session =>
            sender ! Patients(dao.patients(startIndex, count, orderBy, orderAscending, filter))
          }

        case GetStudies(startIndex, count, patientId) =>
          db.withSession { implicit session =>
            sender ! Studies(dao.studiesForPatient(startIndex, count, patientId))
          }

        case GetSeries(startIndex, count, studyId) =>
          db.withSession { implicit session =>
            sender ! SeriesCollection(dao.seriesForStudy(startIndex, count, studyId))
          }

        case GetImages(seriesId) =>
          db.withSession { implicit session =>
            sender ! Images(dao.imagesForSeries(seriesId))
          }

        case GetImageFile(imageId) =>
          db.withSession { implicit session =>
            dao.imageFileForImage(imageId) match {
              case Some(imageFile) =>
                sender ! imageFile
              case None =>
                throw new IllegalArgumentException(s"No file found for image $imageId")
            }
          }

        case GetImageFilesForSeries(seriesIds) =>
          db.withSession { implicit session =>
            sender ! ImageFiles(dao.imageFilesForSeries(seriesIds))
          }

        case GetImageFilesForStudies(studyIds) =>
          db.withSession { implicit session =>
            sender ! ImageFiles(dao.imageFilesForStudies(studyIds))
          }

        case GetImageFilesForPatients(patientIds) =>
          db.withSession { implicit session =>
            sender ! ImageFiles(dao.imageFilesForPatients(patientIds))
          }

        case GetFlatSeries(startIndex, count, orderBy, orderAscending, filter) =>
          db.withSession { implicit session =>
            sender ! FlatSeriesCollection(dao.flatSeries(startIndex, count, orderBy, orderAscending, filter))
          }

      }
    }

  }

  def storeDataset(dataset: Attributes): Image = {
    val name = fileName(dataset)
    val storedPath = storage.resolve(name)

    db.withSession { implicit session =>
      val patient = datasetToPatient(dataset)
      val dbPatient = dao.patientByNameAndID(patient)
        .getOrElse(dao.insert(patient))

      val study = datasetToStudy(dataset)
      val dbStudy = dao.studyByUid(study)
        .getOrElse(dao.insert(study.copy(patientId = dbPatient.id)))

      val equipment = datasetToEquipment(dataset)
      val dbEquipment = dao.equipmentByManufacturerAndStationName(equipment)
        .getOrElse(dao.insert(equipment))

      val frameOfReference = datasetToFrameOfReference(dataset)
      val dbFrameOfReference = dao.frameOfReferenceByUid(frameOfReference)
        .getOrElse(dao.insert(frameOfReference))

      val series = datasetToSeries(dataset)
      val dbSeries = dao.seriesByUid(series)
        .getOrElse(dao.insert(series.copy(
          studyId = dbStudy.id,
          equipmentId = dbEquipment.id,
          frameOfReferenceId = dbFrameOfReference.id)))

      val image = datasetToImage(dataset)
      val dbImage = dao.imageByUid(image)
        .getOrElse(dao.insert(image.copy(seriesId = dbSeries.id)))

      val imageFile = ImageFile(dbImage.id, FileName(name))
      val dbImageFile = dao.imageFileByFileName(imageFile)
        .getOrElse(dao.insert(imageFile))

      saveDataset(dataset, storedPath)

      dbImage
    }
  }

  def fileName(dataset: Attributes): String = dataset.getString(Tag.SOPInstanceUID)

  def deleteFromStorage(imageFiles: Seq[ImageFile]): Unit = imageFiles foreach (deleteFromStorage(_))
  def deleteFromStorage(imageFile: ImageFile): Unit = deleteFromStorage(storage.resolve(imageFile.fileName.value))
  def deleteFromStorage(filePath: Path): Unit = {
    Files.delete(filePath)
    log.info("Deleted file " + filePath)
  }

  def readImageAttributes(fileName: String): Future[ImageAttributes] = {
    val filePath = storage.resolve(fileName)
    val dataset = loadDataset(filePath, false)
    Future {
      ImageAttributes(readImageAttributes(dataset, 0, ""))
    }
  }

  def readImageAttributes(dataset: Attributes, depth: Int, path: String): List[ImageAttribute] = {
    val attributesBuffer = ListBuffer.empty[ImageAttribute]
    if (dataset != null) {
      dataset.accept(new Visitor() {
        override def visit(attrs: Attributes, tag: Int, vr: VR, value: AnyRef): Boolean = {
          val strings = attrs.getStrings(tag)
          if (strings != null && !strings.isEmpty) {
            val multiplicity = multiplicityOf(strings)
            if (multiplicity <= 256) {
              val length = lengthOf(attrs.getBytes(tag))
              val group = TagUtils.toHexString(TagUtils.groupNumber(tag)).substring(4)
              val element = TagUtils.toHexString(TagUtils.elementNumber(tag)).substring(4)
              val name = Keyword.valueOf(tag)
              val vrName = vr.name
              val content = toSingleString(strings)
              attributesBuffer += ImageAttribute(group, element, vrName, length, multiplicity, depth, path, name, content)
              if (vr == VR.SQ) {
                val nextPath = if (path.isEmpty()) name else path + '/' + name
                attributesBuffer ++= readImageAttributes(attrs.getNestedDataset(tag), depth + 1, nextPath)
              }
            }
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

  def multiplicityOf(strings: Array[String]) =
    if (strings == null)
      0
    else
      strings.length

  def toSingleString(strings: Array[String]) =
    if (strings == null || strings.length == 0)
      ""
    else if (strings.length == 1)
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

  def readImageFrame(fileName: String, frameNumber: Int, windowMin: Int, windowMax: Int, imageHeight: Int): ImageFrame = {
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
      ImageFrame(baos.toByteArray)
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
    }

}

object DicomStorageActor {
  def props(dbProps: DbProps, storage: Path): Props = Props(new DicomStorageActor(dbProps, storage))
}
