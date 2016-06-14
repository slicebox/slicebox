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

import java.util.concurrent.{Executor, ScheduledExecutorService}

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
import akka.pattern.ask
import se.nimsa.sbx.dicom.{Contexts, DicomData}
import ScpProtocol.DicomDataReceivedByScp
import akka.util.Timeout

import scala.concurrent.Await

class Scp(val name: String,
          val aeTitle: String,
          val port: Int,
          executor: Executor,
          scheduledExecutor: ScheduledExecutorService,
          notifyActor: ActorRef,
          implicit val timeout: Timeout) extends LazyLogging {

  private val cstoreSCP = new BasicCStoreSCP("*") {

    override protected def store(as: Association, pc: PresentationContext, rq: Attributes, data: PDVInputStream, rsp: Attributes): Unit = {
      rsp.setInt(Tag.Status, VR.US, 0)

      val cuid = rq.getString(Tag.AffectedSOPClassUID)
      val iuid = rq.getString(Tag.AffectedSOPInstanceUID)
      val tsuid = pc.getTransferSyntax

      val metaInformation = as.createFileMetaInformation(iuid, cuid, tsuid)
      val attributes = data.readDataset(tsuid)

      val dicomData = DicomData(attributes, metaInformation)

      /*
       * This is the interface between a synchronous callback and a async actor system. To avoid sending too many large
       * messages to the notification actor, risking heap overflow, we block and wait here, ensuring one-at-a-time
       * processing of dicom data.
       */
      Await.ready(notifyActor.ask(DicomDataReceivedByScp(dicomData)), timeout.duration)
    }

  }

  private val conn = new Connection(name, null, port)

  private val ae = new ApplicationEntity(aeTitle)
  ae.setAssociationAcceptor(true)
  ae.addConnection(conn)
  Contexts.imageDataContexts.foreach(context =>
    ae.addTransferCapability(new TransferCapability(null, context.sopClass.uid, TransferCapability.Role.SCP, context.transferSyntaxes.map(_.uid): _*)))

  private val device = new Device("storescp")
  device.setExecutor(executor)
  device.setScheduledExecutor(scheduledExecutor)
  device.setDimseRQHandler(createServiceRegistry())
  device.addConnection(conn)
  device.addApplicationEntity(ae)
  device.bindConnections()

  private def createServiceRegistry(): DicomServiceRegistry = {
    val serviceRegistry = new DicomServiceRegistry()
    serviceRegistry.addDicomService(new BasicCEchoSCP())
    serviceRegistry.addDicomService(cstoreSCP)
    serviceRegistry
  }

  def shutdown(): Unit = {
    device.unbindConnections()
  }
}
