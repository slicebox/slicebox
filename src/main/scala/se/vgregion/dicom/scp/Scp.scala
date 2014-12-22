package se.vgregion.dicom.scp

import org.dcm4che3.data.Attributes
import org.dcm4che3.data.Tag
import org.dcm4che3.data.VR
import org.dcm4che3.net.ApplicationEntity
import org.dcm4che3.net.Association
import org.dcm4che3.net.Connection
import org.dcm4che3.net.Device
import org.dcm4che3.net.PDVInputStream
import org.dcm4che3.net.TransferCapability
import org.dcm4che3.net.pdu.PresentationContext
import org.dcm4che3.net.service.BasicCEchoSCP
import org.dcm4che3.net.service.BasicCStoreSCP
import org.dcm4che3.net.service.DicomServiceRegistry

import com.typesafe.scalalogging.LazyLogging

import akka.actor.ActorRef
import akka.actor.actorRef2Scala
import se.vgregion.dicom.DicomDispatchProtocol.DatasetReceivedByScp

class Scp(val name: String, val aeTitle: String, val port: Int, notifyActor: ActorRef) extends LazyLogging {

  val device = new Device("storescp")  
  private val ae = new ApplicationEntity(aeTitle)
  private val conn = new Connection(name, null, port)
  
  private val cstoreSCP = new BasicCStoreSCP("*") {

    private val PART_EXT = ".part"
  
    override protected def store(as: Association, pc: PresentationContext, rq: Attributes, data: PDVInputStream, rsp: Attributes): Unit = {
      rsp.setInt(Tag.Status, VR.US, 0)

      val cuid = rq.getString(Tag.AffectedSOPClassUID)
      val iuid = rq.getString(Tag.AffectedSOPInstanceUID)
      val tsuid = pc.getTransferSyntax()
      val metaInformation = as.createFileMetaInformation(iuid, cuid, tsuid)
      val dataset = data.readDataset(tsuid)
      notifyActor ! DatasetReceivedByScp(metaInformation, dataset)
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