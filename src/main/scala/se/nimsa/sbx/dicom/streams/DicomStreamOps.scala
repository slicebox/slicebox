package se.nimsa.sbx.dicom.streams

import akka.NotUsed
import akka.actor.ActorSystem
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.{Source => StreamSource}
import akka.util.{ByteString, Timeout}
import org.dcm4che3.data.{Attributes, Tag}
import org.dcm4che3.io.DicomStreamException
import se.nimsa.dcm4che.streams.DicomFlows
import se.nimsa.dcm4che.streams.DicomFlows.TagModification
import se.nimsa.sbx.anonymization.AnonymizationProtocol._
import se.nimsa.sbx.app.GeneralProtocol.Source
import se.nimsa.sbx.dicom.Contexts.Context
import se.nimsa.sbx.dicom.DicomHierarchy.Image
import se.nimsa.sbx.dicom.DicomPropertyValue.{PatientID, PatientName}
import se.nimsa.sbx.metadata.MetaDataProtocol.{AddMetaData, DeleteMetaData, MetaDataAdded, MetaDataDeleted}
import se.nimsa.sbx.storage.StorageProtocol.{DeleteDicomData, DicomDataDeleted, DicomDataMoved, MoveDicomData}
import se.nimsa.sbx.storage.StorageService

import scala.concurrent.{ExecutionContext, Future}
import scala.reflect.ClassTag
import DicomStreams._
import se.nimsa.sbx.dicom.Contexts

trait DicomStreamLoadOps {

  def callAnonymizationService[R: ClassTag](message: Any): Future[R]

  protected def anonymizationInsert(implicit ec: ExecutionContext) = (anonymizationKey: AnonymizationKey) =>
    callAnonymizationService[AnonymizationKeyAdded](AddAnonymizationKey(anonymizationKey))
      .map(_.anonymizationKey)

  protected def anonymizationQuery(implicit ec: ExecutionContext) = (patientName: PatientName, patientID: PatientID) =>
    callAnonymizationService[AnonymizationKeys](GetAnonymizationKeysForPatient(patientName.value, patientID.value))
      .map(_.anonymizationKeys)

  def anonymizedDicomData(image: Image, tagValues: Seq[TagValue], storage: StorageService)
                         (implicit system: ActorSystem, mat: ActorMaterializer, ec: ExecutionContext, timeout: Timeout): StreamSource[ByteString, NotUsed] =
    anonymizedDicomDataSource(storage.fileSource(image), anonymizationQuery, anonymizationInsert, tagValues)
}

trait DicomStreamOps extends DicomStreamLoadOps {

  def callStorageService[R: ClassTag](message: Any): Future[R]
  def callMetaDataService[R: ClassTag](message: Any): Future[R]

  protected def reverseAnonymizationQuery(implicit ec: ExecutionContext) = (patientName: PatientName, patientID: PatientID) =>
    callAnonymizationService[AnonymizationKeys](GetReverseAnonymizationKeysForPatient(patientName.value, patientID.value))
      .map(_.anonymizationKeys)

  def storeDicomData(bytesSource: StreamSource[ByteString, _], source: Source, storage: StorageService, contexts: Seq[Context], reverseAnonymization: Boolean = true)
                    (implicit system: ActorSystem, mat: ActorMaterializer, ec: ExecutionContext, timeout: Timeout): Future[MetaDataAdded] = {
    val tempPath = createTempPath()
    val sink = dicomDataSink(storage.fileSink(tempPath), reverseAnonymizationQuery, contexts, reverseAnonymization)
    bytesSource.runWith(sink).flatMap {
      case (_, maybeDataset) =>
        val attributes: Attributes = maybeDataset.getOrElse(throw new DicomStreamException("DICOM data has no dataset"))
        callMetaDataService[MetaDataAdded](AddMetaData(attributes, source)).flatMap { metaData =>
          callStorageService[DicomDataMoved](MoveDicomData(tempPath, s"${metaData.image.id}"))
            .map(_ => metaData)
        }
    }
  }

  def anonymizeData(image: Image, source: Source, storage: StorageService, tagValues: Seq[TagValue])
                   (implicit system: ActorSystem, mat: ActorMaterializer, ec: ExecutionContext, timeout: Timeout): Future[MetaDataAdded] = {
    val forcedSource = dicomDataSource(storage.fileSource(image))
      .via(DicomFlows.modifyFlow(TagModification(Tag.PatientIdentityRemoved, _ => ByteString("NO"), insert = false)))
      .via(DicomFlows.blacklistFilter { // FIXME: use seq variant, remember to include bulkdatafilter in dicom tags flow also
        case Tag.DeidentificationMethod => true
        case _ => false
      })
      .map(_.bytes)
    val anonymizedSource = anonymizedDicomDataSource(forcedSource, anonymizationQuery, anonymizationInsert, tagValues)
      .mapAsync(5)(bytes =>
        callMetaDataService[MetaDataDeleted](DeleteMetaData(image)).flatMap(_ =>
          callStorageService[DicomDataDeleted](DeleteDicomData(image))
        ).map(_ => bytes)
      )
    storeDicomData(anonymizedSource, source, storage, Contexts.extendedContexts, reverseAnonymization = false)
  }

}
