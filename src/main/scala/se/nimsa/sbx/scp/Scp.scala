/*
 * Copyright 2014 Lars Edenbrandt
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

import akka.actor.ActorRef
import akka.pattern.ask
import akka.util.{ByteString, Timeout}
import com.sun.xml.internal.messaging.saaj.util.ByteOutputStream
import com.typesafe.scalalogging.LazyLogging
import org.dcm4che3.data.Attributes
import org.dcm4che3.io.DicomOutputStream
import org.dcm4che3.net._
import org.dcm4che3.net.pdu.PresentationContext
import org.dcm4che3.net.service.{BasicCEchoSCP, BasicCStoreSCP, DicomServiceRegistry}
import se.nimsa.sbx.dicom.DicomUtil.toCheVR
import se.nimsa.dicom.{Tag, UID, VR}
import se.nimsa.sbx.dicom.Contexts
import se.nimsa.sbx.scp.ScpProtocol.DicomDataReceivedByScp

import scala.concurrent.Await

class Scp(val name: String,
          val aeTitle: String,
          val port: Int,
          executor: Executor,
          scheduledExecutor: ScheduledExecutorService,
          notifyActor: ActorRef)(implicit timeout: Timeout) extends LazyLogging {

  private val cstoreSCP = new BasicCStoreSCP("*") {

    override protected def store(as: Association, pc: PresentationContext, rq: Attributes, data: PDVInputStream, rsp: Attributes): Unit = {
      rsp.setInt(Tag.Status, VR.US, 0)

      val cuid = rq.getString(Tag.AffectedSOPClassUID)
      val iuid = rq.getString(Tag.AffectedSOPInstanceUID)
      val tsuid = pc.getTransferSyntax

      val fmi = as.createFileMetaInformation(iuid, cuid, tsuid)

      val baos = new ByteOutputStream()
      val dos = new DicomOutputStream(baos, UID.ExplicitVRLittleEndian)
      dos.writeFileMetaInformation(fmi)
      data.copyTo(dos)
      dos.close()

      val bytes = ByteString(baos.getBytes.take(baos.size))

      /*
       * This is the interface between a synchronous callback and a async actor system. To avoid sending too many large
       * messages to the notification actor, risking heap overflow, we block and wait here, ensuring one-at-a-time
       * processing of dicom data.
       */
      Await.ready(notifyActor.ask(DicomDataReceivedByScp(bytes)), timeout.duration)
    }

  }

  private val conn = new Connection(name, null, port)

  private val ae = new ApplicationEntity(aeTitle)
  ae.setAssociationAcceptor(true)
  ae.addConnection(conn)
  Contexts.imageDataContexts.foreach(context =>
    ae.addTransferCapability(new TransferCapability(null, context.sopClassUid, TransferCapability.Role.SCP, context.transferSyntaxUids: _*)))

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
