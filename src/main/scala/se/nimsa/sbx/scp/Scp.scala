/*
 * Copyright 2017 Lars Edenbrandt
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
import akka.stream.scaladsl.{StreamConverters, Source => StreamSource}
import akka.util.{ByteString, Timeout}
import com.typesafe.scalalogging.LazyLogging
import org.dcm4che3.data.{Attributes, Tag, VR}
import org.dcm4che3.net._
import org.dcm4che3.net.pdu.PresentationContext
import org.dcm4che3.net.service.{BasicCEchoSCP, BasicCStoreSCP, DicomServiceRegistry}
import se.nimsa.dcm4che.streams.DicomParsing
import se.nimsa.dcm4che.streams.DicomParts.{DicomAttribute, DicomHeader, DicomValueChunk}
import se.nimsa.sbx.dicom.{Contexts, DicomUtil}
import se.nimsa.sbx.scp.ScpProtocol.DicomDataReceivedByScp

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

      val versionName = as.getRemoteImplVersionName
      val tsuidBytes = DicomUtil.padToEvenLength(ByteString(pc.getTransferSyntax), VR.UI)
      val cuidBytes = ByteString(rq.getBytes(Tag.AffectedSOPClassUID))
      val iuidBytes = ByteString(rq.getBytes(Tag.AffectedSOPInstanceUID))
      val riuidBytes = DicomUtil.padToEvenLength(ByteString(as.getRemoteImplClassUID), VR.UI)
      val raetBytes = DicomUtil.padToEvenLength(ByteString(as.getRemoteAET), VR.SH)

      // val bigEndian = tsuid == UID.ExplicitVRBigEndianRetired

      val fmiVersion = DicomAttribute(DicomHeader(Tag.FileMetaInformationVersion, VR.OB, 2, isFmi = true, bigEndian = false, explicitVR = true), Seq(DicomValueChunk(bigEndian = false, ByteString(1, 0), last = true)))
      val fmiSopClass = DicomAttribute(DicomHeader(Tag.MediaStorageSOPClassUID, VR.UI, cuidBytes.length, isFmi = true, bigEndian = false, explicitVR = true), Seq(DicomValueChunk(bigEndian = true, cuidBytes, last = true)))
      val fmiSopInstance = DicomAttribute(DicomHeader(Tag.MediaStorageSOPInstanceUID, VR.UI, iuidBytes.length, isFmi = true, bigEndian = false, explicitVR = true), Seq(DicomValueChunk(bigEndian = true, iuidBytes, last = true)))
      val fmiTransferSyntax = DicomAttribute(DicomHeader(Tag.TransferSyntaxUID, VR.UI, tsuidBytes.length, isFmi = true, bigEndian = false, explicitVR = true), Seq(DicomValueChunk(bigEndian = true, tsuidBytes, last = true)))
      val fmiImplementationUID = DicomAttribute(DicomHeader(Tag.ImplementationClassUID, VR.UI, riuidBytes.length, isFmi = true, bigEndian = false, explicitVR = true), Seq(DicomValueChunk(bigEndian = true, riuidBytes, last = true)))
      val fmiVersionName = if (versionName != null) {
        val versionNameBytes = DicomUtil.padToEvenLength(ByteString(versionName), VR.SH)
        DicomAttribute(DicomHeader(Tag.ImplementationVersionName, VR.SH, versionNameBytes.length, isFmi = true, bigEndian = false, explicitVR = true), Seq(DicomValueChunk(bigEndian = true, versionNameBytes, last = true)))
      } else
        DicomAttribute(DicomHeader(Tag.ImplementationVersionName, VR.SH, 0, isFmi = true, bigEndian = false, explicitVR = true, ByteString.empty), Seq(DicomValueChunk(bigEndian = true, ByteString.empty, last = true)))
      val fmiAeTitle = DicomAttribute(DicomHeader(Tag.SourceApplicationEntityTitle, VR.SH, raetBytes.length, isFmi = true, bigEndian = false, explicitVR = true), Seq(DicomValueChunk(bigEndian = true, raetBytes, last = true)))

      val fmiBytes = fmiVersion.bytes ++ fmiSopClass.bytes ++ fmiSopInstance.bytes ++ fmiTransferSyntax.bytes ++ fmiImplementationUID.bytes ++ fmiVersionName.bytes ++ fmiAeTitle.bytes
      val fmiLength = DicomAttribute(DicomHeader(Tag.FileMetaInformationGroupLength, VR.UL, 4, isFmi = true, bigEndian = false, explicitVR = true), Seq(DicomValueChunk(bigEndian = true, DicomParsing.intToBytesLE(fmiBytes.length), last = true)))
      val preambleBytes = ByteString.fromArray(new Array[Byte](128)) ++ ByteString('D', 'I', 'C', 'M')

      val source = StreamSource.single(preambleBytes ++ fmiLength.bytes ++ fmiBytes).concat(StreamConverters.fromInputStream(() => data))

      /*
       * This is the interface between a synchronous callback and a async actor system. To avoid sending too many large
       * messages to the notification actor, risking heap overflow, we block and wait here, ensuring one-at-a-time
       * processing of dicom data.
       */
      Await.ready(notifyActor.ask(DicomDataReceivedByScp(source)), timeout.duration)
    }

  }

  private val conn = new Connection(name, null, port)

  private val ae = new ApplicationEntity(aeTitle)
  ae.setAssociationAcceptor(true)
  ae.addConnection(conn)
  Contexts.imageDataContexts.foreach(context =>
    ae.addTransferCapability(new TransferCapability(null, context.sopClassUid, TransferCapability.Role.SCP, context.transferSyntaxeUids: _*)))

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
