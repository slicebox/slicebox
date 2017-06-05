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
import se.nimsa.sbx.dicom.Contexts
import se.nimsa.sbx.dicom.Contexts.Context
import se.nimsa.sbx.dicom.DicomHierarchy.Image
import se.nimsa.sbx.dicom.DicomPropertyValue.{PatientID, PatientName}
import se.nimsa.sbx.dicom.streams.DicomStreams._
import se.nimsa.sbx.metadata.MetaDataProtocol._
import se.nimsa.sbx.storage.StorageProtocol.{DeleteDicomData, DicomDataDeleted, DicomDataMoved, MoveDicomData}
import se.nimsa.sbx.storage.StorageService
import se.nimsa.sbx.util.SbxExtensions._

import scala.concurrent.{ExecutionContext, Future}
import scala.reflect.ClassTag

/**
  * Stream operations for loading DICOM data from storage
  */
trait DicomStreamLoadOps {

  def callAnonymizationService[R: ClassTag](message: Any): Future[R]
  def callMetaDataService[R: ClassTag](message: Any): Future[R]

  protected def anonymizationInsert(implicit ec: ExecutionContext) = (anonymizationKey: AnonymizationKey) =>
    callAnonymizationService[AnonymizationKeyAdded](AddAnonymizationKey(anonymizationKey))
      .map(_.anonymizationKey)

  protected def anonymizationQuery(implicit ec: ExecutionContext) = (patientName: PatientName, patientID: PatientID) =>
    callAnonymizationService[AnonymizationKeys](GetAnonymizationKeysForPatient(patientName.value, patientID.value))
      .map(_.anonymizationKeys)

  /**
    * Creates a streaming source of anonymized and harmonized DICOM data
    *
    * @param imageId   ID of image to load
    * @param tagValues forced values of attributes as pairs of tag number and string value encoded in UTF-8 (ASCII) format
    * @param storage   the storage backend (file, runtime, S3 etc)
    * @return a `Source` of anonymized DICOM byte chunks
    */
  def anonymizedDicomData(imageId: Long, tagValues: Seq[TagValue], storage: StorageService)
                         (implicit system: ActorSystem, mat: ActorMaterializer, ec: ExecutionContext, timeout: Timeout): Future[Option[StreamSource[ByteString, NotUsed]]] =
    callMetaDataService[Option[Image]](GetImage(imageId)).map { imageMaybe =>
      imageMaybe.map { image =>
        anonymizedDicomDataSource(storage.fileSource(image), anonymizationQuery, anonymizationInsert, tagValues)
      }
    }
}

/**
  * Stream operations for loading and saving DICOM data from and to storage
  */
trait DicomStreamOps extends DicomStreamLoadOps {

  def callStorageService[R: ClassTag](message: Any): Future[R]

  protected def reverseAnonymizationQuery(implicit ec: ExecutionContext) = (patientName: PatientName, patientID: PatientID) =>
    callAnonymizationService[AnonymizationKeys](GetReverseAnonymizationKeysForPatient(patientName.value, patientID.value))
      .map(_.anonymizationKeys)

  /**
    * Store DICOM data from a source of byte chunks and update meta data.
    *
    * @param bytesSource          DICOM byte data source
    * @param source               the origin of the data (import, scp etc)
    * @param storage              the storage backend (file, runtime, S3 etc)
    * @param contexts             the allowed combinations of SOP Class UID and Transfer Syntax
    * @param reverseAnonymization switch to determined whether reverse anonymization should be carried out or not
    * @return the meta data info stored in the database
    */
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

  /**
    * Retrieve data from the system, anonymize it - regardless of already anonymous or not, delete the old data, and
    * write the new data back to the system.
    *
    * @param imageId   ID of image to anonymize
    * @param tagValues forced values of attributes as pairs of tag number and string value encoded in UTF-8 (ASCII) format
    * @param storage   the storage backend (file, runtime, S3 etc)
    * @return the anonymized metadata stored in the system
    */
  def anonymizeData(imageId: Long, tagValues: Seq[TagValue], storage: StorageService)
                   (implicit system: ActorSystem, mat: ActorMaterializer, ec: ExecutionContext, timeout: Timeout): Future[Option[MetaDataAdded]] =
    callMetaDataService[Option[Image]](GetImage(imageId)).flatMap { imageMaybe =>
      imageMaybe.map { image =>
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
        callMetaDataService[Option[SeriesSource]](GetSourceForSeries(image.seriesId)).map { seriesSourceMaybe =>
          seriesSourceMaybe.map { seriesSource =>
            storeDicomData(anonymizedSource, seriesSource.source, storage, Contexts.extendedContexts, reverseAnonymization = false)
          }
        }
      }.unwrap
    }.unwrap

}
