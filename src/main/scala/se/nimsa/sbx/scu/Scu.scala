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

package se.nimsa.sbx.scu

import java.util.concurrent.Executors

import com.typesafe.scalalogging.LazyLogging
import org.dcm4che3.data.{Attributes, Tag, UID}
import org.dcm4che3.imageio.codec.Decompressor
import org.dcm4che3.net._
import org.dcm4che3.net.pdu.{AAssociateRQ, PresentationContext}
import org.dcm4che3.util.TagUtils
import se.nimsa.sbx.dicom.DicomData
import se.nimsa.sbx.scu.ScuProtocol.ScuData
import se.nimsa.sbx.util.FutureUtil.traverseSequentially
import se.nimsa.sbx.util.SbxExtensions._

import scala.concurrent.{ExecutionContext, Future, Promise}

trait DicomDataProvider {
  def getDicomData(imageId: Long, withPixelData: Boolean): Future[Option[DicomData]]
}

case class DicomDataInfo(iuid: String, cuid: String, ts: String, imageId: Long)

class Scu(ae: ApplicationEntity, scuData: ScuData)(implicit ec: ExecutionContext) extends LazyLogging {
  val remote = new Connection()
  val rq = new AAssociateRQ()

  val priority: Int = 0
  var as: Association = null

  rq.addPresentationContext(new PresentationContext(1, UID.VerificationSOPClass, UID.ImplicitVRLittleEndian))
  rq.setCalledAET(scuData.aeTitle)

  remote.setHostname(scuData.host)
  remote.setPort(scuData.port)

  trait RSPHandlerFactory {
    def createDimseRSPHandler(imageId: Long, promise: Promise[Long]): DimseRSPHandler
  }

  val rspHandlerFactory = new RSPHandlerFactory() {

    override def createDimseRSPHandler(imageId: Long, promise: Promise[Long]): DimseRSPHandler =
      new DimseRSPHandler(as.nextMessageID()) {
        override def onDimseRSP(as: Association, cmd: Attributes, data: Attributes): Unit = {
          super.onDimseRSP(as, cmd, data)
          Scu.this.onCStoreRSP(cmd, imageId, promise)
        }
      }
  }

  def addDicomData(imageId: Long, dicomData: DicomData): Option[DicomDataInfo] = {
    val fmi = dicomData.metaInformation

    val ts = fmi.getString(Tag.TransferSyntaxUID)
    val cuid = fmi.getString(Tag.MediaStorageSOPClassUID)
    val iuid = fmi.getString(Tag.MediaStorageSOPInstanceUID)
    if (ts == null || cuid == null || iuid == null)
      return None

    val dicomDataInfo = DicomDataInfo(iuid, cuid, ts, imageId)

    if (!rq.containsPresentationContextFor(cuid, ts)) {
      if (!rq.containsPresentationContextFor(cuid)) {
        if (!ts.equals(UID.ExplicitVRLittleEndian))
          rq.addPresentationContext(new PresentationContext(rq.getNumberOfPresentationContexts * 2 + 1, cuid, UID.ExplicitVRLittleEndian))
        if (!ts.equals(UID.ImplicitVRLittleEndian))
          rq.addPresentationContext(new PresentationContext(rq.getNumberOfPresentationContexts * 2 + 1, cuid, UID.ImplicitVRLittleEndian))
      }
      rq.addPresentationContext(new PresentationContext(rq.getNumberOfPresentationContexts * 2 + 1, cuid, ts))
    }

    Some(dicomDataInfo)
  }

  def sendFiles(dicomDataInfos: Seq[DicomDataInfo], dicomDataProvider: DicomDataProvider): Future[Seq[Option[Long]]] = {

    val futureSentFiles = traverseSequentially(dicomDataInfos) { dicomDataInfo =>
      dicomDataProvider.getDicomData(dicomDataInfo.imageId, withPixelData = true).map { dicomDataMaybe =>
        dicomDataMaybe.filter(_ => as.isReadyForDataTransfer).map { dicomData =>
          send(dicomData, dicomDataInfo.cuid, dicomDataInfo.iuid, dicomDataInfo.ts, dicomDataInfo.imageId)
        }
      }.unwrap
    }

    futureSentFiles.andThen {
      case _ => as.waitForOutstandingRSP()
    }
  }

  def send(dicomData: DicomData, cuid: String, iuid: String, filets: String, imageId: Long): Future[Long] = {
    val ts = selectTransferSyntax(cuid, filets)
    if (!ts.equals(filets)) {
      Decompressor.decompress(dicomData.attributes, filets)
    }
    val promise = Promise[Long]()
    try
      as.cstore(cuid, iuid, priority, new DataWriterAdapter(dicomData.attributes), ts, rspHandlerFactory.createDimseRSPHandler(imageId, promise))
    catch {
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
        logger.warn(s"SCU send file issues:\nSCU status: ${TagUtils.shortToHexString(status)}\nCommand: ${cmd.toString}\nImage ID: $imageId")
        promise.success(imageId)
      case _ =>
        logger.error(s"SCU send file error:\nSCU status ${TagUtils.shortToHexString(status)}\nCommand: ${cmd.toString}\nImage ID: $imageId}")
        promise.failure(new RuntimeException(s"SCU send failed for image with ID $imageId"))
    }
  }
}

object Scu {

  def sendFiles(scuData: ScuData, dicomDataProvider: DicomDataProvider, imageIds: Seq[Long])(implicit ec: ExecutionContext): Future[Seq[Option[Long]]] = {
    val device = new Device("slicebox-scu")
    val connection = new Connection()
    connection.setMaxOpsInvoked(0)
    connection.setMaxOpsPerformed(0)
    device.addConnection(connection)
    val ae = new ApplicationEntity("SLICEBOX-SCU")
    device.addApplicationEntity(ae)
    ae.addConnection(connection)

    val scu = new Scu(ae, scuData)

    val futureDicomDataInfos = traverseSequentially(imageIds) { imageId =>
      dicomDataProvider.getDicomData(imageId, withPixelData = false).map { dicomDataMaybe =>
        dicomDataMaybe.flatMap { dicomData =>
          scu.addDicomData(imageId, dicomData)
        }
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
