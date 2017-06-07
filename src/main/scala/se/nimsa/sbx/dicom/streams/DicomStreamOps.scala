package se.nimsa.sbx.dicom.streams

import akka.NotUsed
import akka.actor.{ActorSystem, Cancellable}
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.{Source => StreamSource}
import akka.util.{ByteString, Timeout}
import org.dcm4che3.data.Attributes
import org.dcm4che3.io.DicomStreamException
import se.nimsa.dcm4che.streams.DicomFlows.TagModification
import se.nimsa.sbx.anonymization.AnonymizationProtocol._
import se.nimsa.sbx.app.GeneralProtocol.Source
import se.nimsa.sbx.dicom.Contexts.Context
import se.nimsa.sbx.dicom.DicomHierarchy.Image
import se.nimsa.sbx.dicom.DicomPropertyValue.{PatientID, PatientName}
import se.nimsa.sbx.metadata.MetaDataProtocol.{AddMetaData, MetaDataAdded}
import se.nimsa.sbx.storage.StorageProtocol.{DicomDataMoved, MoveDicomData}
import se.nimsa.sbx.storage.StorageService

import scala.concurrent.duration.{DurationInt, FiniteDuration}
import scala.concurrent.{ExecutionContext, Future}
import scala.reflect.ClassTag

trait DicomStreamLoadOps {

  def callAnonymizationService[R: ClassTag](message: Any): Future[R]

  private def anonymizationInsert(implicit ec: ExecutionContext) = (anonymizationKey: AnonymizationKey) =>
    callAnonymizationService[AnonymizationKeyAdded](AddAnonymizationKey(anonymizationKey))
      .map(_.anonymizationKey)

  private def anonymizationQuery(implicit ec: ExecutionContext) = (patientName: PatientName, patientID: PatientID) =>
    callAnonymizationService[AnonymizationKeys](GetAnonymizationKeysForPatient(patientName.value, patientID.value))
      .map(_.anonymizationKeys)

  def anonymizedData(image: Image, tagMods: Seq[TagModification], storage: StorageService)
                    (implicit system: ActorSystem, mat: ActorMaterializer, ec: ExecutionContext, timeout: Timeout): StreamSource[ByteString, NotUsed] =
    DicomStreams.anonymizedDicomDataSource(storage.fileSource(image), anonymizationQuery, anonymizationInsert, tagMods)
}

trait DicomStreamOps extends DicomStreamLoadOps {

  def callStorageService[R: ClassTag](message: Any): Future[R]
  def callMetaDataService[R: ClassTag](message: Any): Future[R]
  def scheduleTask(delay: FiniteDuration)(task: => Unit): Cancellable

  private def reverseAnonymizationQuery(implicit ec: ExecutionContext) = (patientName: PatientName, patientID: PatientID) =>
    callAnonymizationService[AnonymizationKeys](GetReverseAnonymizationKeysForPatient(patientName.value, patientID.value))
      .map(_.anonymizationKeys)

  def storeData(bytesSource: StreamSource[ByteString, _], source: Source, storage: StorageService, contexts: Seq[Context])
               (implicit system: ActorSystem, mat: ActorMaterializer, ec: ExecutionContext, timeout: Timeout): Future[MetaDataAdded] = {
    val tempPath = DicomStreams.createTempPath()
    val sink = DicomStreams.dicomDataSink(storage.fileSink(tempPath), reverseAnonymizationQuery, contexts)
    bytesSource.runWith(sink).flatMap {
      case (_, maybeDataset) =>
        val attributes: Attributes = maybeDataset.getOrElse(throw new DicomStreamException("DICOM data has no dataset"))
        callMetaDataService[MetaDataAdded](AddMetaData(attributes, source)).flatMap { metaData =>
          callStorageService[DicomDataMoved](MoveDicomData(tempPath, s"${metaData.image.id}"))
            .map(_ => metaData)
        }
    }.recover {
      case t: Throwable =>
        scheduleTask(30.seconds) {
          storage.deleteFromStorage(tempPath) // delete temp file once file system has released handle
        }
        throw t
    }
  }

}
