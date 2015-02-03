package se.vgregion.dicom

import org.dcm4che3.data.Attributes
import se.vgregion.dicom.DicomHierarchy._
import se.vgregion.dicom.DicomPropertyValue._
import org.dcm4che3.data.Tag
import org.dcm4che3.util.SafeClose
import org.dcm4che3.io.DicomInputStream
import org.dcm4che3.io.DicomOutputStream
import java.nio.file.Files
import java.io.BufferedInputStream
import java.nio.file.Path
import org.dcm4che3.data.UID
import org.dcm4che3.io.DicomInputStream.IncludeBulkData
import java.io.InputStream
import java.io.ByteArrayInputStream
import java.io.OutputStream

object DicomUtil {

  val defaultTransferSyntax = UID.ExplicitVRLittleEndian

  def saveDataset(dataset: Attributes, filePath: Path): Boolean = 
    saveDataset(dataset, Files.newOutputStream(filePath))
    
  def saveDataset(dataset: Attributes, outputStream: OutputStream): Boolean = {
    val dos = new DicomOutputStream(outputStream, defaultTransferSyntax)
    val metaInformation = dataset.createFileMetaInformation(defaultTransferSyntax)
    try {
      dos.writeDataset(metaInformation, dataset)
      true
    } catch {
      case _: Exception => false
    } finally {
      SafeClose.close(dos)
    }
  }

  def loadDataset(path: Path, withPixelData: Boolean): Attributes =
    loadDataset(new BufferedInputStream(Files.newInputStream(path)), withPixelData)

  def loadDataset(byteArray: Array[Byte], withPixelData: Boolean): Attributes =
    loadDataset(new BufferedInputStream(new ByteArrayInputStream(byteArray)), withPixelData)

  def loadDataset(inputStream: InputStream, withPixelData: Boolean): Attributes = {
    var dis: DicomInputStream = null
    try {
      dis = new DicomInputStream(inputStream)

      val dataset =
        if (withPixelData)
          dis.readDataset(-1, -1)
        else {
          dis.setIncludeBulkData(IncludeBulkData.NO)
          dis.readDataset(-1, Tag.PixelData);
        }
      dataset
    } catch {
      case _: Exception => null
    } finally {
      SafeClose.close(dis)
    }
  }

  def datasetToPatient(dataset: Attributes): Patient =
    Patient(
      -1,
      PatientName(valueOrEmpty(dataset, DicomProperty.PatientName.dicomTag)),
      PatientID(valueOrEmpty(dataset, DicomProperty.PatientID.dicomTag)),
      PatientBirthDate(valueOrEmpty(dataset, DicomProperty.PatientBirthDate.dicomTag)),
      PatientSex(valueOrEmpty(dataset, DicomProperty.PatientSex.dicomTag)))

  def datasetToStudy(dataset: Attributes): Study =
    Study(
      -1,
      -1,
      StudyInstanceUID(valueOrEmpty(dataset, DicomProperty.StudyInstanceUID.dicomTag)),
      StudyDescription(valueOrEmpty(dataset, DicomProperty.StudyDescription.dicomTag)),
      StudyDate(valueOrEmpty(dataset, DicomProperty.StudyDate.dicomTag)),
      StudyID(valueOrEmpty(dataset, DicomProperty.StudyID.dicomTag)),
      AccessionNumber(valueOrEmpty(dataset, DicomProperty.AccessionNumber.dicomTag)))

  def datasetToEquipment(dataset: Attributes): Equipment =
    Equipment(
      -1,
      Manufacturer(valueOrEmpty(dataset, DicomProperty.Manufacturer.dicomTag)),
      StationName(valueOrEmpty(dataset, DicomProperty.StationName.dicomTag)))

  def datasetToFrameOfReference(dataset: Attributes): FrameOfReference =
    FrameOfReference(
      -1,
      FrameOfReferenceUID(valueOrEmpty(dataset, DicomProperty.FrameOfReferenceUID.dicomTag)))

  def datasetToSeries(dataset: Attributes): Series =
    Series(
      -1,
      -1,
      -1,
      -1,
      SeriesInstanceUID(valueOrEmpty(dataset, DicomProperty.SeriesInstanceUID.dicomTag)),
      SeriesDescription(valueOrEmpty(dataset, DicomProperty.SeriesDescription.dicomTag)),
      SeriesDate(valueOrEmpty(dataset, DicomProperty.SeriesDate.dicomTag)),
      Modality(valueOrEmpty(dataset, DicomProperty.Modality.dicomTag)),
      ProtocolName(valueOrEmpty(dataset, DicomProperty.ProtocolName.dicomTag)),
      BodyPartExamined(valueOrEmpty(dataset, DicomProperty.BodyPartExamined.dicomTag)))

  def datasetToImage(dataset: Attributes): Image =
    Image(
      -1,
      -1,
      SOPInstanceUID(valueOrEmpty(dataset, DicomProperty.SOPInstanceUID.dicomTag)),
      ImageType(readMultiple(dataset.getStrings(DicomProperty.ImageType.dicomTag))))

  private def valueOrEmpty(dataset: Attributes, tag: Int) = Option(dataset.getString(tag)).getOrElse("")

  def readMultiple(values: Array[String]): String =
    if (values == null || values.length == 0)
      ""
    else
      values.tail.foldLeft(values.head)((result, part) => result + "/" + part)

  def checkSopClass(dataset: Attributes) =
    SopClasses.sopClasses
      .filter(_.included)
      .map(_.sopClassUID)
      .contains(dataset.getString(Tag.SOPClassUID))

}