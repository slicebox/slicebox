package se.vgregion.dicom

import org.dcm4che3.io.DicomInputStream
import org.dcm4che3.io.DicomInputStream.IncludeBulkData
import org.dcm4che3.data.Tag
import org.dcm4che3.util.SafeClose
import java.nio.file.Path
import se.vgregion.dicom.MetaDataProtocol._
import se.vgregion.dicom.Attributes._
import org.dcm4che3.data.Sequence
import scala.collection.JavaConverters._

object DicomUtil {

  def readDataSetWithoutPixelData(path: Path): Option[org.dcm4che3.data.Attributes] = {
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

  def readImage(path: Path): Option[ImageFile] =
    readDataSetWithoutPixelData(path).map(attributes => ImageFile(Image(Series(Study(Patient(

      PatientName(Option(attributes.getString(PatientName.tag)).getOrElse("")),
      PatientID(Option(attributes.getString(PatientID.tag)).getOrElse("")),
      PatientBirthDate(Option(attributes.getString(PatientBirthDate.tag)).getOrElse("")),
      PatientSex(Option(attributes.getString(PatientSex.tag)).getOrElse(""))),

      StudyInstanceUID(Option(attributes.getString(StudyInstanceUID.tag)).getOrElse("")),
      StudyDescription(Option(attributes.getString(StudyDescription.tag)).getOrElse("")),
      StudyDate(Option(attributes.getString(StudyDate.tag)).getOrElse("")),
      StudyID(Option(attributes.getString(StudyID.tag)).getOrElse("")),
      AccessionNumber(Option(attributes.getString(AccessionNumber.tag)).getOrElse(""))),

      Equipment(
        Manufacturer(Option(attributes.getString(Manufacturer.tag)).getOrElse("")),
        StationName(Option(attributes.getString(StationName.tag)).getOrElse(""))),
      FrameOfReference(
        FrameOfReferenceUID(Option(attributes.getString(FrameOfReferenceUID.tag)).getOrElse(""))),
      SeriesInstanceUID(Option(attributes.getString(SeriesInstanceUID.tag)).getOrElse("")),
      SeriesDescription(Option(attributes.getString(SeriesDescription.tag)).getOrElse("")),
      SeriesDate(Option(attributes.getString(SeriesDate.tag)).getOrElse("")),
      Modality(Option(attributes.getString(Modality.tag)).getOrElse("")),
      ProtocolName(Option(attributes.getString(ProtocolName.tag)).getOrElse("")),
      BodyPartExamined(Option(attributes.getString(BodyPartExamined.tag)).getOrElse(""))),

      SOPInstanceUID(Option(attributes.getString(SOPInstanceUID.tag)).getOrElse("")),
      ImageType(readSequence(attributes.getStrings(ImageType.tag)))),

      FileName(path.toAbsolutePath().toString())))

  def readSequence(sequence: Array[String]): String =
    if (sequence == null || sequence.length == 0)
      ""
    else
      sequence.tail.foldLeft(sequence.head)((result, part) => result + "/" + part)

}