package se.vgregion.dicom.scp

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
import java.nio.file.Path
import akka.actor.ActorRef
import se.vgregion.dicom.DicomProtocol._

class Scp(val name: String, val aeTitle: String, val port: Int, dicomActor: ActorRef) extends LazyLogging {

  val device = new Device("storescp")  
  private val ae = new ApplicationEntity(aeTitle)
  private val conn = new Connection(name, null, port)
  
  private val cstoreSCP = new BasicCStoreSCP("*") {

    private val PART_EXT = ".part"
  
    override protected def store(as: Association, pc: PresentationContext, rq: Dcm4CheAttributes, data: PDVInputStream, rsp: Dcm4CheAttributes): Unit = {
      rsp.setInt(Tag.Status, VR.US, 0)

      val cuid = rq.getString(Tag.AffectedSOPClassUID)
      val iuid = rq.getString(Tag.AffectedSOPInstanceUID)
      val tsuid = pc.getTransferSyntax()
      val metaInformation = as.createFileMetaInformation(iuid, cuid, tsuid)
      val dataset = data.readDataset(tsuid)
      dicomActor ! AddDicom(metaInformation, dataset)
    }

  }

  device.setDimseRQHandler(createServiceRegistry());
  device.addConnection(conn);
  device.addApplicationEntity(ae);

  ae.setAssociationAcceptor(true);
  ae.addConnection(conn);
  ae.addTransferCapability(new TransferCapability(null, "*", TransferCapability.Role.SCP, "*"))

  private def createServiceRegistry(): DicomServiceRegistry = {
    val serviceRegistry = new DicomServiceRegistry()
    serviceRegistry.addDicomService(new BasicCEchoSCP())
    serviceRegistry.addDicomService(cstoreSCP)
    serviceRegistry
  }

}