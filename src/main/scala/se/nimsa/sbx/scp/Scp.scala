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

package se.nimsa.sbx.scp

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
import se.nimsa.sbx.dicom.SopClasses
import ScpProtocol.DatasetReceivedByScp

class Scp(val name: String, val aeTitle: String, val port: Int, notifyActor: ActorRef) extends LazyLogging {

  val device = new Device("storescp")
  private val ae = new ApplicationEntity(aeTitle)
  private val conn = new Connection(name, null, port)

  private val cstoreSCP = new BasicCStoreSCP("*") {

    override protected def store(as: Association, pc: PresentationContext, rq: Attributes, data: PDVInputStream, rsp: Attributes): Unit = {
      rsp.setInt(Tag.Status, VR.US, 0)

      val cuid = rq.getString(Tag.AffectedSOPClassUID)
      val iuid = rq.getString(Tag.AffectedSOPInstanceUID)
      val tsuid = pc.getTransferSyntax()
      val dataset = data.readDataset(tsuid)
      notifyActor ! DatasetReceivedByScp(dataset)
    }

  }

  device.setDimseRQHandler(createServiceRegistry());
  device.addConnection(conn);
  device.addApplicationEntity(ae);

  ae.setAssociationAcceptor(true);
  ae.addConnection(conn);
  
  SopClasses.sopClasses.filter(_.included).foreach(sopClass =>
    ae.addTransferCapability(new TransferCapability(sopClass.sopClassUID, "*", TransferCapability.Role.SCP, "*")))

  private def createServiceRegistry(): DicomServiceRegistry = {
    val serviceRegistry = new DicomServiceRegistry()
    serviceRegistry.addDicomService(new BasicCEchoSCP())
    serviceRegistry.addDicomService(cstoreSCP)
    serviceRegistry
  }

}
