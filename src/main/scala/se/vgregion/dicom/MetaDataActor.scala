package se.vgregion.dicom

import akka.actor.Actor
import akka.actor.ActorRef
import akka.event.Logging
import akka.event.LoggingReceive
import MetaDataProtocol._
import se.vgregion.dicom.MetaDataProtocol._
import se.vgregion.db.DbProtocol._
import DicomPropertyValue._
import akka.actor.Props
import org.dcm4che3.data.Attributes

class MetaDataActor(dbActor: ActorRef) extends Actor {
  val log = Logging(context.system, this)

  def receive = LoggingReceive {
    case AddDataset(dataset, fileName) =>
      val image = datasetToImage(dataset)
      val imageFile = ImageFile(image, FileName(fileName))
      dbActor ! InsertImageFile(imageFile)
    case DeleteDataset(dataset) =>
      val image = datasetToImage(dataset)
      dbActor ! RemoveImage(image)
    case GetImageFiles =>
      dbActor forward GetImageFileEntries
    case GetPatients =>
      dbActor forward GetPatientEntries
    case GetStudies(patient) =>
      dbActor forward GetStudyEntries(patient)
    case GetSeries(study) =>
      dbActor forward GetSeriesEntries(study)
    case GetImages(series) =>
      dbActor forward GetImageEntries(series)
    case GetImageFiles(image) =>
      dbActor forward GetImageFileEntries(image)
  }

  def datasetToImage(dataset: Attributes): Image =
    Image(Series(Study(Patient(

      PatientName(Option(dataset.getString(DicomProperty.PatientName.dicomTag)).getOrElse("")),
      PatientID(Option(dataset.getString(DicomProperty.PatientID.dicomTag)).getOrElse("")),
      PatientBirthDate(Option(dataset.getString(DicomProperty.PatientBirthDate.dicomTag)).getOrElse("")),
      PatientSex(Option(dataset.getString(DicomProperty.PatientSex.dicomTag)).getOrElse(""))),

      StudyInstanceUID(Option(dataset.getString(DicomProperty.StudyInstanceUID.dicomTag)).getOrElse("")),
      StudyDescription(Option(dataset.getString(DicomProperty.StudyDescription.dicomTag)).getOrElse("")),
      StudyDate(Option(dataset.getString(DicomProperty.StudyDate.dicomTag)).getOrElse("")),
      StudyID(Option(dataset.getString(DicomProperty.StudyID.dicomTag)).getOrElse("")),
      AccessionNumber(Option(dataset.getString(DicomProperty.AccessionNumber.dicomTag)).getOrElse(""))),

      Equipment(
        Manufacturer(Option(dataset.getString(DicomProperty.Manufacturer.dicomTag)).getOrElse("")),
        StationName(Option(dataset.getString(DicomProperty.StationName.dicomTag)).getOrElse(""))),
      FrameOfReference(
        FrameOfReferenceUID(Option(dataset.getString(DicomProperty.FrameOfReferenceUID.dicomTag)).getOrElse(""))),
      SeriesInstanceUID(Option(dataset.getString(DicomProperty.SeriesInstanceUID.dicomTag)).getOrElse("")),
      SeriesDescription(Option(dataset.getString(DicomProperty.SeriesDescription.dicomTag)).getOrElse("")),
      SeriesDate(Option(dataset.getString(DicomProperty.SeriesDate.dicomTag)).getOrElse("")),
      Modality(Option(dataset.getString(DicomProperty.Modality.dicomTag)).getOrElse("")),
      ProtocolName(Option(dataset.getString(DicomProperty.ProtocolName.dicomTag)).getOrElse("")),
      BodyPartExamined(Option(dataset.getString(DicomProperty.BodyPartExamined.dicomTag)).getOrElse(""))),

      SOPInstanceUID(Option(dataset.getString(DicomProperty.SOPInstanceUID.dicomTag)).getOrElse("")),
      ImageType(readSequence(dataset.getStrings(DicomProperty.ImageType.dicomTag))))

  def readSequence(sequence: Array[String]): String =
    if (sequence == null || sequence.length == 0)
      ""
    else
      sequence.tail.foldLeft(sequence.head)((result, part) => result + "/" + part)

}

object MetaDataActor {
  def props(dbActor: ActorRef): Props = Props(new MetaDataActor(dbActor))
}
