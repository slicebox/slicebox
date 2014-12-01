package se.vgregion.dicom

import java.io.File
import java.io.IOException
import org.dcm4che3.data.{ Attributes => Dcm4CheAttributes }
import org.dcm4che3.data.Tag
import org.dcm4che3.data.VR
import org.dcm4che3.io.DicomInputStream
import org.dcm4che3.io.DicomInputStream.IncludeBulkData
import org.dcm4che3.io.DicomOutputStream
import org.dcm4che3.net.ApplicationEntity
import org.dcm4che3.net.Association
import org.dcm4che3.net.Connection
import org.dcm4che3.net.Device
import org.dcm4che3.net.PDVInputStream
import org.dcm4che3.net.Status
import org.dcm4che3.net.TransferCapability
import org.dcm4che3.net.pdu.PresentationContext
import org.dcm4che3.net.service.BasicCEchoSCP
import org.dcm4che3.net.service.BasicCStoreSCP
import org.dcm4che3.net.service.DicomServiceException
import org.dcm4che3.net.service.DicomServiceRegistry
import org.dcm4che3.util.SafeClose
import com.typesafe.scalalogging.LazyLogging

class Scp(val name: String, val aeTitle: String, val port: Int, val storageDir: File) extends LazyLogging {

  val device = new Device("storescp")  
  private val ae = new ApplicationEntity(aeTitle)
  private val conn = new Connection(name, null, port)
  
  private val cstoreSCP = new BasicCStoreSCP("*") {

    private val PART_EXT = ".part"
  
    override protected def store(as: Association, pc: PresentationContext, rq: Dcm4CheAttributes, data: PDVInputStream, rsp: Dcm4CheAttributes): Unit = {
      rsp.setInt(Tag.Status, VR.US, 0)
      if (storageDir == null)
        return

      val cuid = rq.getString(Tag.AffectedSOPClassUID)
      val iuid = rq.getString(Tag.AffectedSOPInstanceUID)
      val tsuid = pc.getTransferSyntax()
      val file = new File(storageDir, iuid + PART_EXT)
      try {
        storeTo(as, as.createFileMetaInformation(iuid, cuid, tsuid), data, file)
        renameTo(as, file, new File(storageDir, iuid))
      } catch {
        case e: Throwable =>
          deleteFile(as, file)
          throw new DicomServiceException(Status.ProcessingFailure, e)
      }
    }

  }

  device.setDimseRQHandler(createServiceRegistry());
  device.addConnection(conn);
  device.addApplicationEntity(ae);

  ae.setAssociationAcceptor(true);
  ae.addConnection(conn);
  ae.addTransferCapability(new TransferCapability(null, "*", TransferCapability.Role.SCP, "*"))

  private def storeTo(as: Association, fmi: Dcm4CheAttributes, data: PDVInputStream, file: File) = {
    logger.info("{}: M-WRITE {}", as, file)
    val out = new DicomOutputStream(file)
    try {
      out.writeFileMetaInformation(fmi)
      data.copyTo(out)
    } finally {
      SafeClose.close(out)
    }
  }

  private def renameTo(as: Association, from: File, dest: File): Unit = {
    logger.info("{}: M-RENAME {}", Array[Object](as, from, dest))
    dest.getParentFile().mkdirs()
    if (!from.renameTo(dest))
      throw new IOException("Failed to rename " + from + " to " + dest)
  }

  private def parse(file: File): Dcm4CheAttributes = {
    val in = new DicomInputStream(file)
    try {
      in.setIncludeBulkData(IncludeBulkData.NO)
      in.readDataset(-1, Tag.PixelData)
    } finally {
      SafeClose.close(in)
    }
  }

  private def deleteFile(as: Association, file: File): Unit =
    if (file.delete())
      logger.info("{}: M-DELETE {}", as, file);
    else
      logger.warn("{}: M-DELETE {} failed!", as, file);

  private def createServiceRegistry(): DicomServiceRegistry = {
    val serviceRegistry = new DicomServiceRegistry()
    serviceRegistry.addDicomService(new BasicCEchoSCP())
    serviceRegistry.addDicomService(cstoreSCP)
    serviceRegistry
  }

}