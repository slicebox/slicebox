/*
 * Copyright 2015 Karl Sjöstrand
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

package se.nimsa.sbx.dicom.scu

import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.security.GeneralSecurityException
import java.text.MessageFormat
import java.util.Properties
import java.util.ResourceBundle
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import javax.xml.parsers.ParserConfigurationException
import org.dcm4che3.data.Tag
import org.dcm4che3.data.UID
import org.dcm4che3.data.Attributes
import org.dcm4che3.imageio.codec.Decompressor
import org.dcm4che3.io.DicomInputStream
import org.dcm4che3.io.SAXReader
import org.dcm4che3.io.DicomInputStream.IncludeBulkData
import org.dcm4che3.net.ApplicationEntity
import org.dcm4che3.net.Association
import org.dcm4che3.net.Connection
import org.dcm4che3.net.DataWriterAdapter
import org.dcm4che3.net.Device
import org.dcm4che3.net.DimseRSPHandler
import org.dcm4che3.net.IncompatibleConnectionException
import org.dcm4che3.net.InputStreamDataWriter
import org.dcm4che3.net.Status
import org.dcm4che3.net.pdu.AAssociateRQ
import org.dcm4che3.net.pdu.PresentationContext
import org.dcm4che3.util.SafeClose
import org.dcm4che3.util.StringUtils
import org.dcm4che3.util.TagUtils
import org.xml.sax.SAXException
import org.dcm4che3.net.pdu.CommonExtendedNegotiation
import scala.collection.JavaConversions._
import java.nio.file.Path
import se.nimsa.sbx.dicom.DicomProtocol.ScuData

class RelatedGeneralSOPClasses {

  val commonExtNegs = scala.collection.mutable.Map.empty[String, CommonExtendedNegotiation]

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

case class FileInfo(iuid: String, cuid: String, ts: String, endFmi: Long, file: File)

class Scu(ae: ApplicationEntity, scuData: ScuData) {
  val remote = new Connection()
  val rq = new AAssociateRQ()

  val relSOPClasses = new RelatedGeneralSOPClasses()

  val relExtNeg: Boolean = false
  val priority: Int = 0
  var as: Association = null

  rq.addPresentationContext(new PresentationContext(1, UID.VerificationSOPClass, UID.ImplicitVRLittleEndian))
  rq.setCalledAET(scuData.aeTitle);

  remote.setHostname(scuData.host)
  remote.setPort(scuData.port)

  trait RSPHandlerFactory {
    def createDimseRSPHandler(f: File): DimseRSPHandler
  }

  val rspHandlerFactory = new RSPHandlerFactory() {

    override def createDimseRSPHandler(f: File): DimseRSPHandler =
      new DimseRSPHandler(as.nextMessageID()) {

        override def onDimseRSP(as: Association, cmd: Attributes, data: Attributes): Unit = {
          super.onDimseRSP(as, cmd, data)
          Scu.this.onCStoreRSP(cmd, f)
        }
      }
  }

  def addFile(f: File): Option[FileInfo] = {
    var in: DicomInputStream = null
    try {
      in = new DicomInputStream(f)
      in.setIncludeBulkData(IncludeBulkData.NO)
      var fmi = in.readFileMetaInformation()
      val dsPos = in.getPosition()
      val ds = in.readDataset(-1, Tag.PixelData)
      if (fmi == null || !fmi.containsValue(Tag.TransferSyntaxUID)
        || !fmi.containsValue(Tag.MediaStorageSOPClassUID)
        || !fmi.containsValue(Tag.MediaStorageSOPInstanceUID))
        fmi = ds.createFileMetaInformation(in.getTransferSyntax())

      val cuid = fmi.getString(Tag.MediaStorageSOPClassUID)
      val iuid = fmi.getString(Tag.MediaStorageSOPInstanceUID)
      val ts = fmi.getString(Tag.TransferSyntaxUID)
      if (cuid == null || iuid == null)
        return None

      val fileInfo = FileInfo(iuid, cuid, ts, dsPos, f)

      if (rq.containsPresentationContextFor(cuid, ts))
        return Some(fileInfo)

      if (!rq.containsPresentationContextFor(cuid)) {
        if (relExtNeg)
          rq.addCommonExtendedNegotiation(relSOPClasses.getCommonExtendedNegotiation(cuid))
        if (!ts.equals(UID.ExplicitVRLittleEndian))
          rq.addPresentationContext(new PresentationContext(rq.getNumberOfPresentationContexts() * 2 + 1, cuid, UID.ExplicitVRLittleEndian))
        if (!ts.equals(UID.ImplicitVRLittleEndian))
          rq.addPresentationContext(new PresentationContext(rq.getNumberOfPresentationContexts() * 2 + 1, cuid, UID.ImplicitVRLittleEndian))
      }
      rq.addPresentationContext(new PresentationContext(rq
        .getNumberOfPresentationContexts() * 2 + 1, cuid, ts))
      Some(fileInfo)
    } catch {
      case e: Exception => None
    } finally {
      SafeClose.close(in);
    }

  }

  def sendFiles(fileInfos: List[FileInfo]) = {
    fileInfos.foreach(fileInfo =>
      if (as.isReadyForDataTransfer)
        send(fileInfo.file, fileInfo.endFmi, fileInfo.cuid, fileInfo.iuid, fileInfo.ts))
    as.waitForOutstandingRSP()
  }

  def send(f: File, fmiEndPos: Long, cuid: String, iuid: String, filets: String): Unit = {
    val ts = selectTransferSyntax(cuid, filets)

    val in = new FileInputStream(f)
    try {
      in.skip(fmiEndPos) // skip meta information
      val data = new InputStreamDataWriter(in)
      as.cstore(cuid, iuid, priority, data, ts,
        rspHandlerFactory.createDimseRSPHandler(f))
    } finally {
      SafeClose.close(in)
    }
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
      if (as.isReadyForDataTransfer())
        as.release()
      as.waitForSocketClose()
    }
  }

  def open(): Unit = {
    as = ae.connect(remote, rq)
  }

  def onCStoreRSP(cmd: Attributes, f: File): Unit = {
    val status = cmd.getInt(Tag.Status, -1)
    val hej = status match {
      case Status.Success                =>
      case Status.CoercionOfDataElements =>
      case Status.ElementsDiscarded      =>
      case Status.DataSetDoesNotMatchSOPClassWarning =>
        System.err.println(MessageFormat.format("warning",
          TagUtils.shortToHexString(status), f))
        System.err.println(cmd)
      case _ =>
        System.err.println(MessageFormat.format("error",
          TagUtils.shortToHexString(status), f))
        System.err.println(cmd)
    }
  }
}

object Scu {

  def sendFiles(scuData: ScuData, files: List[Path]): Unit = {
    val device = new Device("slicebox-scu")
    val connection = new Connection()
    device.addConnection(connection)
    val ae = new ApplicationEntity("SLICEBOX-SCU")
    device.addApplicationEntity(ae)
    ae.addConnection(connection)

    val scu = new Scu(ae, scuData)

    val fileInfos = files.map(_.toFile).map(scu.addFile(_)).flatten

    if (fileInfos.isEmpty)
      return

    val executorService = Executors.newSingleThreadExecutor()
    val scheduledExecutorService = Executors.newSingleThreadScheduledExecutor()
    device.setExecutor(executorService)
    device.setScheduledExecutor(scheduledExecutorService)

    try {
      scu.open
      scu.sendFiles(fileInfos)
    } finally {
      scu.close
      executorService.shutdown
      scheduledExecutorService.shutdown
    }
  }

}
