package se.vgregion.dicom

import java.io.BufferedInputStream
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.util.UUID
import org.dcm4che3.data.Attributes
import org.dcm4che3.data.Tag
import org.dcm4che3.data.UID
import org.dcm4che3.io.DicomInputStream
import org.dcm4che3.io.DicomInputStream.IncludeBulkData
import org.dcm4che3.io.DicomOutputStream
import org.dcm4che3.util.SafeClose
import akka.actor.Actor
import akka.actor.Props
import akka.actor.actorRef2Scala
import akka.event.Logging
import akka.event.LoggingReceive
import se.vgregion.dicom.DicomDispatchProtocol._

class DicomFileStorageActor(storage: Path) extends Actor {
  val log = Logging(context.system, this)

  def receive = LoggingReceive {

    case Initialize =>
      Files.createDirectories(storage)
      sender ! Initialized

    case StoreFile(path) =>
      loadDicom(path, true).foreach {
        case (metaInformation, dataset) =>
          val name = fileName(dataset)
          val storedPath = storage.resolve(name)
          try {
            writeToStorage(metaInformation, anonymizeDicom(dataset), storedPath)
            sender ! FileStored(storedPath, metaInformation, dataset)
          } catch {
            case e: Throwable => sender ! FileNotStored(e.getMessage)
          }
      }

    case StoreDataset(metaInformation, dataset) =>
      val name = fileName(dataset)
      val storedPath = storage.resolve(name)
      try {
        writeToStorage(metaInformation, anonymizeDicom(dataset), storedPath)
      } catch {
        case e: Throwable => sender ! FileNotStored(e.getMessage)
      }
      sender ! FileStored(storedPath, metaInformation, dataset)

    case DeleteFile(filePath) =>
      try {
        deleteFromStorage(filePath)
      } catch {
        case e: Throwable => sender ! FileNotDeleted(e.getMessage)
      }
      sender ! FileDeleted(filePath)
  }

  def listDatasets(root: Path): List[Attributes] = {
    var datasets = List.empty[Attributes]
    Files.walkFileTree(root, new SimpleFileVisitor[Path] {
      override def visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult = {
        super.visitFile(file, attrs)
        loadDicom(file, false).foreach {
          case (metaInformation, dataset) =>
            datasets = datasets :+ dataset
        }
        FileVisitResult.CONTINUE
      }
    })
    datasets
  }

  def loadDicom(path: Path, withPixelData: Boolean): Option[(Attributes, Attributes)] = {
    try {
      val dis = new DicomInputStream(new BufferedInputStream(Files.newInputStream(path)))
      val metaInformation = dis.readFileMetaInformation();
      val dataset = if (withPixelData)
        dis.readDataset(-1, -1)
      else {
        dis.setIncludeBulkData(IncludeBulkData.NO)
        dis.readDataset(-1, Tag.PixelData);
      }
      return Some((metaInformation, dataset))
    } catch {
      case _: Throwable => None
    }
  }

  def cloneDataset(dataset: Attributes): Attributes = new Attributes(dataset)

  def anonymizeDicom(dataset: Attributes): Attributes = {
    // TODO    
    return cloneDataset(dataset)
  }

  def fileName(dataset: Attributes): String = UUID.randomUUID().toString()

  def writeToStorage(metaInformation: Attributes, dataset: Attributes, filePath: Path): Unit = {
    val out = new DicomOutputStream(Files.newOutputStream(filePath), UID.ExplicitVRLittleEndian)
    try {
      out.writeDataset(metaInformation, dataset)
    } finally {
      SafeClose.close(out)
    }
  }

  def deleteFromStorage(filePath: Path): Unit = Files.delete(filePath)

}

object DicomFileStorageActor {
  def props(storage: Path): Props = Props(new DicomFileStorageActor(storage))
}
