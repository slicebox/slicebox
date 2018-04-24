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

package se.nimsa.sbx.scu

import java.util.concurrent.Executors

import akka.NotUsed
import akka.actor.ActorSystem
import akka.stream.Materializer
import akka.stream.scaladsl.{Sink, Source, StreamConverters}
import akka.util.ByteString
import com.typesafe.scalalogging.LazyLogging
import org.dcm4che3.data.Attributes
import org.dcm4che3.imageio.codec.Decompressor
import org.dcm4che3.io.DicomInputStream
import org.dcm4che3.net._
import org.dcm4che3.net.pdu.{AAssociateRQ, PresentationContext}
import org.dcm4che3.util.TagUtils
import se.nimsa.dicom.streams.CollectFlow
import se.nimsa.dicom.DicomParts.{DicomPart, ElementsPart}
import se.nimsa.dicom.{Tag, TagPath, UID}
import se.nimsa.sbx.log.SbxLog
import se.nimsa.sbx.scu.ScuProtocol.ScuData
import se.nimsa.sbx.util.FutureUtil

import scala.concurrent.{ExecutionContext, Future, Promise}

trait DicomDataProvider {
  def getDicomData(imageId: Long, stopTag: Option[Int]): Source[DicomPart, NotUsed]
  def getDicomBytes(imageId: Long): Source[ByteString, NotUsed]
}

case class DicomDataInfo(iuid: String, cuid: String, ts: String, imageId: Long)

class Scu(ae: ApplicationEntity, scuData: ScuData)
         (implicit system: ActorSystem, mat: Materializer, ec: ExecutionContext) extends LazyLogging {
  val remote = new Connection()
  val rq = new AAssociateRQ()

  val priority: Int = 0
  var as: Association = _

  rq.addPresentationContext(new PresentationContext(1, UID.VerificationSOPClass, UID.ImplicitVRLittleEndian))
  rq.setCalledAET(scuData.aeTitle)

  remote.setHostname(scuData.host)
  remote.setPort(scuData.port)

  trait RSPHandlerFactory {
    def createDimseRSPHandler(imageId: Long, promise: Promise[Long]): DimseRSPHandler
  }

  val rspHandlerFactory: RSPHandlerFactory =
    (imageId: Long, promise: Promise[Long]) => new DimseRSPHandler(as.nextMessageID()) {
      override def onDimseRSP(as: Association, cmd: Attributes, data: Attributes): Unit = {
        super.onDimseRSP(as, cmd, data)
        Scu.this.onCStoreRSP(cmd, imageId, promise)
      }
    }

  def addDicomData(imageId: Long, ep: ElementsPart): Option[DicomDataInfo] = {
    val elements = ep.elements

    val tsMaybe = elements.find(_.header.tag == Tag.TransferSyntaxUID).map(a => a.value.utf8String.trim)
    val cuidMaybe = elements.find(_.header.tag == Tag.MediaStorageSOPClassUID).map(a => a.value.utf8String.trim)
    val iuidMaybe = elements.find(_.header.tag == Tag.MediaStorageSOPInstanceUID).map(a => a.value.utf8String.trim)

    val dicomDataInfoMaybe = for {
      ts <- tsMaybe
      cuid <- cuidMaybe
      iuid <- iuidMaybe
    } yield {

      if (!rq.containsPresentationContextFor(cuid, ts)) {
        if (!rq.containsPresentationContextFor(cuid)) {
          if (!ts.equals(UID.ExplicitVRLittleEndian))
            rq.addPresentationContext(new PresentationContext(rq.getNumberOfPresentationContexts * 2 + 1, cuid, UID.ExplicitVRLittleEndian))
          if (!ts.equals(UID.ImplicitVRLittleEndian))
            rq.addPresentationContext(new PresentationContext(rq.getNumberOfPresentationContexts * 2 + 1, cuid, UID.ImplicitVRLittleEndian))
        }
        rq.addPresentationContext(new PresentationContext(rq.getNumberOfPresentationContexts * 2 + 1, cuid, ts))
      }

      DicomDataInfo(iuid, cuid, ts, imageId)
    }

    dicomDataInfoMaybe.orElse {
      SbxLog.error("SCU", s"Image cannot be sent due to missing metainformation: transfer syntax = ${tsMaybe.getOrElse("")}, SOP class UID = ${cuidMaybe.getOrElse("")}, SOP instance UID = ${iuidMaybe.getOrElse("")}")
      None
    }
  }

  def sendFiles(dicomDataInfos: Seq[DicomDataInfo], dicomDataProvider: DicomDataProvider): Future[Seq[Long]] = {

    val futureSentFiles = FutureUtil.traverseSequentially(dicomDataInfos) { dicomDataInfo =>
      if (as.isReadyForDataTransfer) {
        val source = dicomDataProvider.getDicomBytes(dicomDataInfo.imageId)
        send(source, dicomDataInfo.cuid, dicomDataInfo.iuid, dicomDataInfo.ts, dicomDataInfo.imageId)
          .map(Some.apply)
      } else
        Future.successful(None)
    }.map(_.flatten)

    futureSentFiles.andThen {
      case _ => as.waitForOutstandingRSP()
    }
  }

  def send(source: Source[ByteString, NotUsed], cuid: String, iuid: String, filets: String, imageId: Long): Future[Long] = {

    val dis = new DicomInputStream(source.runWith(StreamConverters.asInputStream()))
    val attributes = dis.readDataset(-1, -1)

    val promise = Promise[Long]()
    try {
      val ts = selectTransferSyntax(cuid, filets)
      if (!ts.equals(filets)) {
        Decompressor.decompress(attributes, filets)
      }
      as.cstore(cuid, iuid, priority, new DataWriterAdapter(attributes), ts, rspHandlerFactory.createDimseRSPHandler(imageId, promise))
    } catch {
      case e: Exception =>
        promise.failure(e)
    }
    promise.future
  }

  def selectTransferSyntax(cuid: String, filets: String): String = {
    val tss = as.getTransferSyntaxesFor(cuid)
    if (tss.contains(filets))
      return filets

    if (tss.contains(UID.ExplicitVRLittleEndian))
      return UID.ExplicitVRLittleEndian

    UID.ImplicitVRLittleEndian
  }

  def close(): Unit = {
    if (as != null) {
      if (as.isReadyForDataTransfer)
        as.release()
      as.waitForSocketClose()
    }
  }

  def open(): Unit = {
    as = ae.connect(remote, rq)
  }

  def onCStoreRSP(cmd: Attributes, imageId: Long, promise: Promise[Long]): Unit = {
    val status = cmd.getInt(Tag.Status, -1)
    status match {
      case Status.Success =>
        promise.success(imageId)
      case Status.CoercionOfDataElements | Status.ElementsDiscarded | Status.DataSetDoesNotMatchSOPClassWarning =>
        SbxLog.warn("SCU", s"SCU send file issues:\nSCU status: ${TagUtils.shortToHexString(status)}\nCommand: ${cmd.toString}\nImage ID: $imageId")
        promise.success(imageId)
      case _ =>
        SbxLog.error("SCU", s"SCU send file error:\nSCU status ${TagUtils.shortToHexString(status)}\nCommand: ${cmd.toString}\nImage ID: $imageId}")
        promise.failure(new RuntimeException(s"SCU send failed for image with ID $imageId"))
    }
  }
}

object Scu {

  def sendFiles(scuData: ScuData, dicomDataProvider: DicomDataProvider, imageIds: Seq[Long])
               (implicit system: ActorSystem, materializer: Materializer, ec: ExecutionContext): Future[Seq[Long]] = {
    val device = new Device("slicebox-scu")
    val connection = new Connection()
    connection.setMaxOpsInvoked(0)
    connection.setMaxOpsPerformed(0)
    device.addConnection(connection)
    val ae = new ApplicationEntity("SLICEBOX-SCU")
    device.addApplicationEntity(ae)
    ae.addConnection(connection)

    val scu = new Scu(ae, scuData)

    val tags: Set[TagPath] = Set(Tag.MediaStorageSOPClassUID, Tag.MediaStorageSOPInstanceUID, Tag.TransferSyntaxUID).map(TagPath.fromTag)

    val futureDicomDataInfos = FutureUtil.traverseSequentially(imageIds) { imageId =>
      val source = dicomDataProvider.getDicomData(imageId, Some(Tag.TransferSyntaxUID + 1))
      source.via(CollectFlow.collectFlow(tags, "scu"))
        .runWith(Sink.head)
        .map {
          case ep: ElementsPart if ep.label == "scu" =>
            scu.addDicomData(imageId, ep)
          case _ =>
            None
        }
    }.map(_.flatten)

    futureDicomDataInfos.flatMap { dicomDataInfos =>
      val executorService = Executors.newSingleThreadExecutor()
      val scheduledExecutorService = Executors.newSingleThreadScheduledExecutor()
      device.setExecutor(executorService)
      device.setScheduledExecutor(scheduledExecutorService)

      scu.open()
      scu.sendFiles(dicomDataInfos, dicomDataProvider).andThen {
        case _ =>
          scu.close()
          executorService.shutdown()
          scheduledExecutorService.shutdown()
      }
    }

  }

}
