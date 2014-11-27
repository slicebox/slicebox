package se.vgregion.dicom

import org.dcm4che3.data.Attributes
import org.dcm4che3.io.DicomInputStream
import org.dcm4che3.io.DicomInputStream.IncludeBulkData
import org.dcm4che3.data.Tag
import org.dcm4che3.util.SafeClose
import java.nio.file.Path
import se.vgregion.dicom.MetaDataProtocol._

object DicomUtil {

  def readDataSetWithoutPixelData(path: Path): Option[Attributes] = {
    var in: DicomInputStream = null
    try {
      in = new DicomInputStream(path.toFile)
      in.setIncludeBulkData(IncludeBulkData.NO)
      val ds = in.readDataset(-1, Tag.PixelData)
      Option(ds)
    } catch {
      case e: Exception =>
        None
    } finally {
      SafeClose.close(in);
    }
  }

  def readImage(path: Path): Option[Image] =
    readDataSetWithoutPixelData(path).map(attributes => Image(Series(Study(Patient(
    attributes.getString(Tag.PatientName),
    attributes.getString(Tag.PatientID)),
    attributes.getString(Tag.StudyDate),
    attributes.getString(Tag.StudyInstanceUID)),
    attributes.getString(Tag.SeriesDate),
    attributes.getString(Tag.SeriesInstanceUID)),
    attributes.getString(Tag.SOPInstanceUID),
    path.toAbsolutePath().toString()))
  
}