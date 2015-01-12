package se.vgregion.dicom

import org.dcm4che3.data.Attributes
import akka.actor.Actor
import akka.actor.Props
import akka.actor.actorRef2Scala
import akka.event.Logging
import akka.event.LoggingReceive
import se.vgregion.app._
import DicomDispatchProtocol._
import DicomHierarchy._
import DicomMetaDataProtocol._
import DicomPropertyValue._
import akka.actor.ActorRef

class DicomMetaDataActor(dbProps: DbProps) extends Actor {
  val log = Logging(context.system, this)

  val metaDataDbActor = context.actorOf(DicomMetaDataDbActor.props(dbProps), "MetaDataDb")

  def receive = LoggingReceive {

    case Initialize =>
      metaDataDbActor forward Initialize

    case AddDataset(metaInformation, dataset, fileName, owner) =>
      val image = datasetToImage(dataset)
      val imageFile = ImageFile(image, FileName(fileName), Owner(owner))
      metaDataDbActor ! AddImageFile(imageFile)
      context.become(waitingForDbActor(sender))

    case msg: MetaDataRequest =>
      metaDataDbActor forward msg

    case msg: GetAllImageFiles =>
      metaDataDbActor forward msg
      
  }

  def waitingForDbActor(client: ActorRef) = LoggingReceive {
    case ImageFileAdded(imageFile) =>
      client ! DatasetAdded(imageFile)
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

object DicomMetaDataActor {
  def props(dbProps: DbProps): Props = Props(new DicomMetaDataActor(dbProps))
}
