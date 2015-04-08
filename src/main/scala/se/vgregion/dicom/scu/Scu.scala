package se.vgregion.dicom.scu

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
import se.vgregion.dicom.DicomProtocol.ScuData

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

class Scu(ae: ApplicationEntity) {
  val remote = new Connection()
  val rq = new AAssociateRQ()
  rq.addPresentationContext(new PresentationContext(1, UID.VerificationSOPClass, UID.ImplicitVRLittleEndian))

  trait RSPHandlerFactory {
    def createDimseRSPHandler(f: File): DimseRSPHandler
  }

  val relSOPClasses = new RelatedGeneralSOPClasses()

  var relExtNeg: Boolean = false
  var priority: Int = 0
  var as: Association = null

  var totalSize: Long = 0
  var filesSent: Int = 0

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
      case Status.Success =>
        filesSent += 1
        totalSize += f.length()
      case Status.CoercionOfDataElements =>
      case Status.ElementsDiscarded      =>
      case Status.DataSetDoesNotMatchSOPClassWarning =>
        filesSent += 1
        totalSize += f.length()
        System.err.println(MessageFormat.format("warning",
          TagUtils.shortToHexString(status), f))
        System.err.println(cmd)
      case _ =>
        print('E')
        System.err.println(MessageFormat.format("error",
          TagUtils.shortToHexString(status), f))
        System.err.println(cmd)
    }
  }
}

object Scu {

  def sendFiles(scuData: ScuData, files: List[Path]): Unit = {
    val device = new Device("storescu")
    val conn = new Connection()
    device.addConnection(conn)
    val ae = new ApplicationEntity("STORESCU")
    device.addApplicationEntity(ae)
    ae.addConnection(conn)
    val main = new Scu(ae)

    val fileInfos = files.map(_.toFile).map(main.addFile(_)).flatten

    if (fileInfos.isEmpty)
      return

    val executorService = Executors.newSingleThreadExecutor()
    val scheduledExecutorService = Executors.newSingleThreadScheduledExecutor()
    device.setExecutor(executorService)
    device.setScheduledExecutor(scheduledExecutorService)
    try {
      main.open()
      main.sendFiles(fileInfos)
    } finally {
      main.close()
      executorService.shutdown()
      scheduledExecutorService.shutdown()
    }
  }

}