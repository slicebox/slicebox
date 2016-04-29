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

package se.nimsa.sbx.scu

import java.util.Properties
import java.util.concurrent.Executors

import com.typesafe.scalalogging.LazyLogging
import org.dcm4che3.data.{Attributes, Tag, UID}
import org.dcm4che3.imageio.codec.Decompressor
import org.dcm4che3.net._
import org.dcm4che3.net.pdu.{AAssociateRQ, CommonExtendedNegotiation, PresentationContext}
import org.dcm4che3.util.{StringUtils, TagUtils}
import se.nimsa.sbx.dicom.DicomUtil
import se.nimsa.sbx.scu.ScuProtocol.ScuData
import se.nimsa.sbx.util.FutureUtil.traverseSequentially
import se.nimsa.sbx.util.SbxExtensions._

import scala.collection.JavaConversions._
import scala.concurrent.{ExecutionContext, Future, Promise}

trait DatasetProvider {
  def getDataset(imageId: Long, withPixelData: Boolean): Future[Option[Attributes]]
}

class RelatedGeneralSOPClasses {

  import scala.collection.mutable

  val commonExtNegs = mutable.Map.empty[String, CommonExtendedNegotiation]

  def init(props: Properties): Unit =
    for (cuid <- props.stringPropertyNames())
      commonExtNegs.put(cuid, new CommonExtendedNegotiation(cuid, UID.StorageServiceClass, StringUtils.split(props.getProperty(cuid), ','): _*))

  def getCommonExtendedNegotiation(cuid: String): CommonExtendedNegotiation = {
    val commonExtNeg = commonExtNegs(cuid)
    if (commonExtNeg != null)
      commonExtNeg
    else
      new CommonExtendedNegotiation(cuid, UID.StorageServiceClass)
  }
}

case class DatasetInfo(iuid: String, cuid: String, ts: String, imageId: Long)

class Scu(ae: ApplicationEntity, scuData: ScuData)(implicit ec: ExecutionContext) extends LazyLogging {
  val remote = new Connection()
  val rq = new AAssociateRQ()

  val relSOPClasses = new RelatedGeneralSOPClasses()

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

  def addDataset(imageId: Long, dataset: Attributes): Option[DatasetInfo] = {
    val fmi = dataset.createFileMetaInformation(DicomUtil.defaultTransferSyntax)

    val cuid = fmi.getString(Tag.MediaStorageSOPClassUID)
    val iuid = fmi.getString(Tag.MediaStorageSOPInstanceUID)
    val ts = fmi.getString(Tag.TransferSyntaxUID)
    if (cuid == null || iuid == null)
      return None

    val datasetInfo = DatasetInfo(iuid, cuid, ts, imageId)

    if (!rq.containsPresentationContextFor(cuid, ts)) {
      if (!rq.containsPresentationContextFor(cuid)) {
        if (!ts.equals(UID.ExplicitVRLittleEndian))
          rq.addPresentationContext(new PresentationContext(rq.getNumberOfPresentationContexts * 2 + 1, cuid, UID.ExplicitVRLittleEndian))
        if (!ts.equals(UID.ImplicitVRLittleEndian))
          rq.addPresentationContext(new PresentationContext(rq.getNumberOfPresentationContexts * 2 + 1, cuid, UID.ImplicitVRLittleEndian))
      }
      rq.addPresentationContext(new PresentationContext(rq.getNumberOfPresentationContexts * 2 + 1, cuid, ts))
    }

    Some(datasetInfo)
  }

  def sendFiles(datasetInfos: Seq[DatasetInfo], datasetProvider: DatasetProvider): Future[Seq[Option[Long]]] = {

    val futureSentFiles = traverseSequentially(datasetInfos) { datasetInfo =>
      datasetProvider.getDataset(datasetInfo.imageId, withPixelData = true).map { datasetMaybe =>
        datasetMaybe.filter(_ => as.isReadyForDataTransfer).map { dataset =>
          send(dataset, datasetInfo.cuid, datasetInfo.iuid, datasetInfo.ts, datasetInfo.imageId)
        }
      }.unwrap
    }

    futureSentFiles.andThen {
      case _ => as.waitForOutstandingRSP()
    }
  }

  def send(dataset: Attributes, cuid: String, iuid: String, filets: String, imageId: Long): Future[Long] = {
    val ts = selectTransferSyntax(cuid, filets)
    Decompressor.decompress(dataset, filets)
    val promise = Promise[Long]()
    as.cstore(cuid, iuid, priority, new DataWriterAdapter(dataset), ts, rspHandlerFactory.createDimseRSPHandler(imageId, promise))
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

  def sendFiles(scuData: ScuData, datasetProvider: DatasetProvider, imageIds: Seq[Long])(implicit ec: ExecutionContext): Future[Seq[Option[Long]]] = {
    val device = new Device("slicebox-scu")
    val connection = new Connection()
    connection.setMaxOpsInvoked(0)
    connection.setMaxOpsPerformed(0)
    device.addConnection(connection)
    val ae = new ApplicationEntity("SLICEBOX-SCU")
    device.addApplicationEntity(ae)
    ae.addConnection(connection)

    val scu = new Scu(ae, scuData)

    val futureDatasetInfos = traverseSequentially(imageIds) { imageId =>
      datasetProvider.getDataset(imageId, withPixelData = false).map { datasetMaybe =>
        datasetMaybe.flatMap { dataset =>
          scu.addDataset(imageId, dataset)
        }
      }
    }.map(_.flatten)

    futureDatasetInfos.flatMap { datasetInfos =>
      val executorService = Executors.newSingleThreadExecutor()
      val scheduledExecutorService = Executors.newSingleThreadScheduledExecutor()
      device.setExecutor(executorService)
      device.setScheduledExecutor(scheduledExecutorService)

      scu.open()
      scu.sendFiles(datasetInfos, datasetProvider).andThen {
        case _ =>
          scu.close()
          executorService.shutdown()
          scheduledExecutorService.shutdown()
      }
    }

  }

}
