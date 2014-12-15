package se.vgregion.dicom

import akka.actor.Actor
import akka.actor.ActorRef
import akka.event.Logging
import akka.event.LoggingReceive
import se.vgregion.dicom.MetaDataProtocol._
import java.nio.file.Path
import org.dcm4che3.io.DicomInputStream
import java.io.BufferedInputStream
import java.nio.file.Files
import org.dcm4che3.data.{ Attributes => Dcm4CheAttributes }
import org.dcm4che3.io.DicomOutputStream
import org.dcm4che3.util.SafeClose
import org.dcm4che3.data.UID
import java.io.ByteArrayOutputStream
import java.io.ByteArrayInputStream
import org.dcm4che3.data.Tag
import DicomProtocol._
import MetaDataProtocol._
import java.util.UUID
import org.dcm4che3.data.BulkData
import org.dcm4che3.io.BulkDataDescriptor
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.FileVisitResult
import org.dcm4che3.io.DicomInputStream.IncludeBulkData
import akka.actor.Props

class DicomActor(metaDataActor: ActorRef, storage: Path) extends Actor {
  val log = Logging(context.system, this)

  def receive = LoggingReceive {
    case AddDicomFile(path) =>
      loadDicom(path, true).foreach {
        case (metaInformation, dataset) =>
          val name = fileName(dataset)
          metaDataActor ! AddDataset(dataset, name, "DIRECTORY")
          writeToStorage(metaInformation, anonymizeDicom(dataset), storage.resolve(name))
      }
    case AddDicom(metaInformation, dataset) =>
      val name = fileName(dataset)
      metaDataActor ! AddDataset(dataset, name, "SCP")
      writeToStorage(metaInformation, anonymizeDicom(dataset), storage.resolve(name))
    case SynchronizeWithStorage =>
      val storageDatasets = listDatasets(storage)
      
    // TODO
  }

  def listDatasets(root: Path): List[Dcm4CheAttributes] = {
    var datasets = List.empty[Dcm4CheAttributes]
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

  def loadDicom(path: Path, withPixelData: Boolean): Option[(Dcm4CheAttributes, Dcm4CheAttributes)] = {
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

  def cloneDataset(dataset: Dcm4CheAttributes): Dcm4CheAttributes = new Dcm4CheAttributes(dataset)

  def anonymizeDicom(dataset: Dcm4CheAttributes): Dcm4CheAttributes = {
    // TODO    
    return cloneDataset(dataset)
  }

  def fileName(dataset: Dcm4CheAttributes): String = UUID.randomUUID().toString()

  def writeToStorage(metaInformation: Dcm4CheAttributes, dataset: Dcm4CheAttributes, filePath: Path): Unit = {
    val out = new DicomOutputStream(Files.newOutputStream(filePath), UID.ExplicitVRLittleEndian)
    try {
      out.writeDataset(metaInformation, dataset)
    } finally {
      SafeClose.close(out)
    }
  }

  def deleteFromStorage(filePath: Path): Unit = Files.delete(filePath)

}

object DicomActor {
  def props(metaDataActor: ActorRef, storage: Path): Props = Props(new DicomActor(metaDataActor, storage))
}
