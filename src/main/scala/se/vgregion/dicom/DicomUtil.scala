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

object DicomUtil {

  val defaultTransferSyntax = UID.ExplicitVRLittleEndian

  def saveDataset(dataset: Attributes, filePath: Path): Boolean = {
    val dos = new DicomOutputStream(Files.newOutputStream(filePath), defaultTransferSyntax)
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

  def loadDataset(path: Path, withPixelData: Boolean): Attributes = {
    val dis = new DicomInputStream(new BufferedInputStream(Files.newInputStream(path)))
    try {
      val dataset =
        if (withPixelData)
          dis.readDataset(-1, -1)
        else {
          dis.setIncludeBulkData(IncludeBulkData.NO)
          dis.readDataset(-1, Tag.PixelData);
        }
      dataset
    } finally {
      SafeClose.close(dis)
    }
  }

  def datasetToImage(dataset: Attributes): Image =
    Image(Series(Study(Patient(

      PatientName(valueOrEmpty(dataset, DicomProperty.PatientName.dicomTag)),
      PatientID(valueOrEmpty(dataset, DicomProperty.PatientID.dicomTag)),
      PatientBirthDate(valueOrEmpty(dataset, DicomProperty.PatientBirthDate.dicomTag)),
      PatientSex(valueOrEmpty(dataset, DicomProperty.PatientSex.dicomTag))),

      StudyInstanceUID(valueOrEmpty(dataset, DicomProperty.StudyInstanceUID.dicomTag)),
      StudyDescription(valueOrEmpty(dataset, DicomProperty.StudyDescription.dicomTag)),
      StudyDate(valueOrEmpty(dataset, DicomProperty.StudyDate.dicomTag)),
      StudyID(valueOrEmpty(dataset, DicomProperty.StudyID.dicomTag)),
      AccessionNumber(valueOrEmpty(dataset, DicomProperty.AccessionNumber.dicomTag))),

      Equipment(
        Manufacturer(valueOrEmpty(dataset, DicomProperty.Manufacturer.dicomTag)),
        StationName(valueOrEmpty(dataset, DicomProperty.StationName.dicomTag))),
      FrameOfReference(
        FrameOfReferenceUID(valueOrEmpty(dataset, DicomProperty.FrameOfReferenceUID.dicomTag))),
      SeriesInstanceUID(valueOrEmpty(dataset, DicomProperty.SeriesInstanceUID.dicomTag)),
      SeriesDescription(valueOrEmpty(dataset, DicomProperty.SeriesDescription.dicomTag)),
      SeriesDate(valueOrEmpty(dataset, DicomProperty.SeriesDate.dicomTag)),
      Modality(valueOrEmpty(dataset, DicomProperty.Modality.dicomTag)),
      ProtocolName(valueOrEmpty(dataset, DicomProperty.ProtocolName.dicomTag)),
      BodyPartExamined(valueOrEmpty(dataset, DicomProperty.BodyPartExamined.dicomTag))),

      SOPInstanceUID(valueOrEmpty(dataset, DicomProperty.SOPInstanceUID.dicomTag)),
      ImageType(readSequence(dataset.getStrings(DicomProperty.ImageType.dicomTag))))

  private def valueOrEmpty(dataset: Attributes, tag: Int) = Option(dataset.getString(tag)).getOrElse("")

  def readSequence(sequence: Array[String]): String =
    if (sequence == null || sequence.length == 0)
      ""
    else
      sequence.tail.foldLeft(sequence.head)((result, part) => result + "/" + part)

  def checkSopClass(dataset: Attributes) =
    SopClasses.sopClasses
      .filter(_.included)
      .map(_.sopClassUID)
      .contains(dataset.getString(Tag.SOPClassUID))

}