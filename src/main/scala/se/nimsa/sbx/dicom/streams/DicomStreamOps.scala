package se.nimsa.sbx.dicom.streams

import akka.actor.Cancellable
import akka.stream.scaladsl.{Broadcast, Flow, GraphDSL, Merge, Sink, Source => StreamSource}
import akka.stream.{FlowShape, Materializer, SinkShape}
import akka.util.ByteString
import akka.{Done, NotUsed}
import org.dcm4che3.data._
import org.dcm4che3.io.DicomStreamException
import se.nimsa.dcm4che.streams.DicomFlows._
import se.nimsa.dcm4che.streams.DicomModifyFlow._
import se.nimsa.dcm4che.streams.DicomParseFlow._
import se.nimsa.dcm4che.streams.DicomParts._
import se.nimsa.dcm4che.streams.TagPath.TagPathSequence
import se.nimsa.dcm4che.streams._
import se.nimsa.sbx.anonymization.AnonymizationProtocol._
import se.nimsa.sbx.app.GeneralProtocol.{Source, SourceType}
import se.nimsa.sbx.dicom.Contexts.Context
import se.nimsa.sbx.dicom.DicomHierarchy.Image
import se.nimsa.sbx.dicom.DicomPropertyValue.{PatientID, PatientName}
import se.nimsa.sbx.dicom.{Contexts, DicomUtil, ImageAttribute}
import se.nimsa.sbx.metadata.MetaDataProtocol._
import se.nimsa.sbx.storage.StorageService
import se.nimsa.sbx.util.SbxExtensions._

import scala.concurrent.duration.{DurationInt, FiniteDuration}
import scala.concurrent.{ExecutionContext, Future}
import scala.reflect.ClassTag

/**
  * Stream operations for loading and saving DICOM data from and to storage
  */
trait DicomStreamOps {

  import DicomStreamOps._

  def callAnonymizationService[R: ClassTag](message: Any): Future[R]
  def callMetaDataService[R: ClassTag](message: Any): Future[R]
  def scheduleTask(delay: FiniteDuration)(task: => Unit): Cancellable

  protected def getOrCreateAnonKey(tagValues: Seq[TagValue]): DicomInfoPart => Future[AnonymizationKey] =
    (info: DicomInfoPart) => callAnonymizationService[AnonymizationKey](GetOrCreateAnonymizationKey(
      info.patientName, info.patientID, info.patientSex, info.patientBirthDate, info.patientAge, info.studyInstanceUID,
      info.studyDescription, info.studyID, info.accessionNumber, info.seriesInstanceUID, info.seriesDescription,
      info.protocolName, info.frameOfReferenceUID, tagValues))

  /**
    * Creates a streaming source of anonymized and harmonized DICOM data
    *
    * @param imageId   ID of image to load
    * @param tagValues forced values of attributes as pairs of tag number and string value encoded in UTF-8 (ASCII) format
    * @param storage   the storage backend (file, runtime, S3 etc)
    * @return a `Source` of anonymized DICOM byte chunks
    */
  def anonymizedDicomData(imageId: Long, tagValues: Seq[TagValue], storage: StorageService)
                         (implicit materializer: Materializer, ec: ExecutionContext): StreamSource[ByteString, NotUsed] = {
    val source = storage.fileSource(imageId)
      .via(parseFlow)
    anonymizedDicomDataSource(source, getOrCreateAnonKey(tagValues), tagValues)
  }

  protected def reverseAnonymizationKeysForPatient(implicit ec: ExecutionContext): (PatientName, PatientID) => Future[Seq[AnonymizationKey]] =
    (patientName: PatientName, patientID: PatientID) => callAnonymizationService[AnonymizationKeys](GetReverseAnonymizationKeysForPatient(patientName.value, patientID.value))
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
                    (implicit materializer: Materializer, ec: ExecutionContext): Future[MetaDataAdded] = {
    val tempPath = createTempPath()
    val sink = dicomDataSink(storage.fileSink(tempPath), reverseAnonymizationKeysForPatient, contexts, reverseAnonymization)
    bytesSource.runWith(sink).flatMap {
      case (_, maybeDataset) => storeDicomData(maybeDataset, source, tempPath, storage)
    }.recover {
      case t: Throwable =>
        scheduleTask(30.seconds) {
          storage.deleteByName(Seq(tempPath)) // delete temp file once file system has released handle
        }
        throw t
    }
  }

  private def storeDicomData(maybeDataset: Option[Attributes], source: Source, tempPath: String, storage: StorageService)
                            (implicit ec: ExecutionContext): Future[MetaDataAdded] = {
    val attributes: Attributes = maybeDataset.getOrElse(throw new DicomStreamException("DICOM data has no dataset"))
    callMetaDataService[MetaDataAdded](AddMetaData(attributes, source)).map { metaDataAdded =>
      storage.move(tempPath, storage.imageName(metaDataAdded.image.id))
      metaDataAdded
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
                   (implicit materializer: Materializer, ec: ExecutionContext): Future[Option[MetaDataAdded]] =
    callMetaDataService[Option[Image]](GetImage(imageId)).flatMap { imageMaybe =>
      imageMaybe.map { image =>
        callMetaDataService[Option[SeriesSource]](GetSourceForSeries(image.seriesId)).map { seriesSourceMaybe =>
          seriesSourceMaybe.map { seriesSource =>
            val forcedSource = storage.fileSource(imageId)
              .via(parseFlow)
              .via(modifyFlow(TagModification.contains(TagPath.fromTag(Tag.PatientIdentityRemoved), _ => ByteString("NO"), insert = false)))
              .via(blacklistFilter(Set(TagPath.fromTag(Tag.DeidentificationMethod))))
            val anonymizedSource = anonymizedDicomDataSource(forcedSource, getOrCreateAnonKey(tagValues), tagValues)
            storeDicomData(anonymizedSource, seriesSource.source, storage, Contexts.extendedContexts, reverseAnonymization = false).flatMap { metaDataAdded =>
              callMetaDataService[MetaDataDeleted](DeleteMetaData(Seq(imageId))).map { _ =>
                storage.deleteFromStorage(Seq(imageId))
                metaDataAdded
              }
            }
          }
        }
      }.unwrap
    }.unwrap


  def modifyData(imageId: Long, tagModifications: Seq[TagModification], storage: StorageService)
                (implicit materializer: Materializer, ec: ExecutionContext): Future[(MetaDataDeleted, MetaDataAdded)] = {

    val futureSourceAndTags =
      callMetaDataService[Option[Image]](GetImage(imageId)).map { imageMaybe =>
        imageMaybe.map { image =>
          callMetaDataService[Option[SeriesSource]](GetSourceForSeries(image.seriesId)).flatMap { sourceMaybe =>
            callMetaDataService[SeriesTags](GetSeriesTagsForSeries(image.seriesId)).map { seriesTags =>
              (sourceMaybe.map(_.source), seriesTags.seriesTags)
            }
          }
        }
      }.unwrap.map(_.getOrElse((None, Seq.empty)))

    val tempPath = createTempPath()
    val sink = dicomDataSink(storage.fileSink(tempPath), reverseAnonymizationKeysForPatient, Contexts.extendedContexts, reverseAnonymization = false)

    val futureModifiedTempFile =
      storage.fileSource(imageId)
        .via(parseFlow)
        .via(groupLengthDiscardFilter)
        .via(toUndefinedLengthSequences)
        .via(modifyFlow(tagModifications: _*))
        .via(fmiGroupLengthFlow)
        .map(_.bytes)
        .runWith(sink)

    for {
      (sourceMaybe, tags) <- futureSourceAndTags
      source = sourceMaybe.getOrElse(Source(SourceType.UNKNOWN, SourceType.UNKNOWN.toString, -1))
      (_, maybeDataset) <- futureModifiedTempFile
      metaDataDeleted <- callMetaDataService[MetaDataDeleted](DeleteMetaData(Seq(imageId)))
      _ = storage.deleteFromStorage(Seq(imageId))
      metaDataAdded <- storeDicomData(maybeDataset, source, tempPath, storage)
      seriesId = metaDataAdded.series.id
      _ <- Future.sequence {
        tags.map { tag =>
          callMetaDataService[SeriesTagAddedToSeries](AddSeriesTagToSeries(tag, seriesId))
        }
      }
    } yield (metaDataDeleted, metaDataAdded)

  }

}

object DicomStreamOps {

  import AnonymizationFlow._
  import HarmonizeAnonymizationFlow._
  import ReverseAnonymizationFlow._

  val encodingTags = Set(Tag.TransferSyntaxUID, Tag.SpecificCharacterSet)

  val tagsToStoreInDB: Set[Int] = {
    val patientTags = Seq(Tag.PatientName, Tag.PatientID, Tag.PatientSex, Tag.PatientBirthDate)
    val studyTags = Seq(Tag.StudyInstanceUID, Tag.StudyDescription, Tag.StudyID, Tag.StudyDate, Tag.AccessionNumber, Tag.PatientAge)
    val seriesTags = Seq(Tag.SeriesInstanceUID, Tag.SeriesDescription, Tag.SeriesDate, Tag.Modality, Tag.ProtocolName, Tag.BodyPartExamined, Tag.Manufacturer, Tag.StationName, Tag.FrameOfReferenceUID)
    val imageTags = Seq(Tag.SOPInstanceUID, Tag.ImageType, Tag.InstanceNumber)

    encodingTags ++ patientTags ++ studyTags ++ seriesTags ++ imageTags
  }

  val basicInfoTags: Set[Int] = encodingTags ++ Set(Tag.PatientName, Tag.PatientID, Tag.PatientIdentityRemoved,
    Tag.StudyInstanceUID, Tag.SeriesInstanceUID)

  val extendedInfoTags: Set[Int] = basicInfoTags ++ Set(Tag.PatientSex, Tag.PatientBirthDate, Tag.PatientAge, Tag.StudyDescription, Tag.StudyID,
    Tag.AccessionNumber, Tag.SeriesDescription, Tag.ProtocolName, Tag.FrameOfReferenceUID)

  case class DicomInfoPart(transferSyntaxUid: Option[String],
                           specificCharacterSet: Option[SpecificCharacterSet],
                           patientID: Option[String],
                           patientName: Option[String],
                           patientSex: Option[String],
                           patientBirthDate: Option[String],
                           patientAge: Option[String],
                           identityRemoved: Option[String],
                           studyInstanceUID: Option[String],
                           studyDescription: Option[String],
                           studyID: Option[String],
                           accessionNumber: Option[String],
                           seriesInstanceUID: Option[String],
                           seriesDescription: Option[String],
                           protocolName: Option[String],
                           frameOfReferenceUID: Option[String]) extends DicomPart {
    def bytes: ByteString = ByteString.empty
    def bigEndian: Boolean = false
    def isAnonymized: Boolean = identityRemoved.exists(_.toUpperCase == "YES")
  }

  case class PartialAnonymizationKeyPart(keyMaybe: Option[AnonymizationKey], hasPatientInfo: Boolean, hasStudyInfo: Boolean, hasSeriesInfo: Boolean) extends DicomPart {
    def bytes: ByteString = ByteString.empty
    def bigEndian: Boolean = false
  }

  def maybeDeflateFlow: Flow[DicomPart, DicomPart, NotUsed] = conditionalFlow(
    {
      case p: DicomInfoPart => p.transferSyntaxUid.isDefined && DicomParsing.isDeflated(p.transferSyntaxUid.get)
    }, deflateDatasetFlow, Flow.fromFunction(identity), routeADefault = false)

  def createTempPath() = s"tmp-${java.util.UUID.randomUUID().toString}"

  def attributesToInfoPart(dicomPart: DicomPart)
                          (implicit ec: ExecutionContext, materializer: Materializer): Future[DicomPart] = {
    dicomPart match {
      case da: DicomAttributes =>
        StreamSource.fromIterator(() => da.attributes.iterator).runWith(DicomAttributesSink.attributesSink).map {
          case (fmiMaybe, dsMaybe) =>
            DicomInfoPart(
              fmiMaybe.flatMap(fmi => Option(fmi.getString(Tag.TransferSyntaxUID))),
              dsMaybe.flatMap(ds => Option(ds.getSpecificCharacterSet)),
              dsMaybe.flatMap(ds => Option(ds.getString(Tag.PatientID))),
              dsMaybe.flatMap(ds => Option(ds.getString(Tag.PatientName))),
              dsMaybe.flatMap(ds => Option(ds.getString(Tag.PatientSex))),
              dsMaybe.flatMap(ds => Option(ds.getString(Tag.PatientBirthDate))),
              dsMaybe.flatMap(ds => Option(ds.getString(Tag.PatientAge))),
              dsMaybe.flatMap(ds => Option(ds.getString(Tag.PatientIdentityRemoved))),
              dsMaybe.flatMap(ds => Option(ds.getString(Tag.StudyInstanceUID))),
              dsMaybe.flatMap(ds => Option(ds.getString(Tag.StudyDescription))),
              dsMaybe.flatMap(ds => Option(ds.getString(Tag.StudyID))),
              dsMaybe.flatMap(ds => Option(ds.getString(Tag.AccessionNumber))),
              dsMaybe.flatMap(ds => Option(ds.getString(Tag.SeriesInstanceUID))),
              dsMaybe.flatMap(ds => Option(ds.getString(Tag.SeriesDescription))),
              dsMaybe.flatMap(ds => Option(ds.getString(Tag.ProtocolName))),
              dsMaybe.flatMap(ds => Option(ds.getString(Tag.FrameOfReferenceUID))))
        }
      case part: DicomPart => Future.successful(part)
    }
  }

  def reverseAnonymizationKeyPartForPatient(query: (PatientName, PatientID) => Future[Seq[AnonymizationKey]])
                                        (implicit ec: ExecutionContext): DicomPart => Future[List[DicomPart]] = {
    case info: DicomInfoPart =>
      val maybeFutureParts = for {
        patientName <- info.patientName if info.isAnonymized
        patientID <- info.patientID
      } yield {
        query(PatientName(patientName), PatientID(patientID)).map { patientKeys =>
          val studyKeys = info.studyInstanceUID.map(studyUID => patientKeys.filter(_.anonStudyInstanceUID == studyUID)).getOrElse(Seq.empty)
          val seriesKeys = info.seriesInstanceUID.map(seriesUID => studyKeys.filter(_.anonSeriesInstanceUID == seriesUID)).getOrElse(Seq.empty)
          val maybeKey = seriesKeys.headOption.orElse(studyKeys.headOption).orElse(patientKeys.headOption)
          info :: PartialAnonymizationKeyPart(maybeKey, hasPatientInfo = patientKeys.nonEmpty, hasStudyInfo = studyKeys.nonEmpty, hasSeriesInfo = seriesKeys.nonEmpty) :: Nil
        }
      }
      maybeFutureParts.getOrElse(Future.successful(info :: PartialAnonymizationKeyPart(None, hasPatientInfo = false, hasStudyInfo = false, hasSeriesInfo = false) :: Nil))
    case part: DicomPart =>
      Future.successful(part :: Nil)
  }

  def dicomDataSink(storageSink: Sink[ByteString, Future[Done]], reverseAnonymizationKeysForPatient: (PatientName, PatientID) => Future[Seq[AnonymizationKey]], contexts: Seq[Context], reverseAnonymization: Boolean = true)
                   (implicit ec: ExecutionContext, materializer: Materializer): Sink[ByteString, Future[(Option[Attributes], Option[Attributes])]] = {

    val attributesSink = DicomAttributesSink.attributesSink

    val validationContexts = Contexts.asNamePairs(contexts).map(ValidationContext.tupled)

    def runBothKeepRight[A, B] = (futureLeft: Future[A], futureRight: Future[B]) => futureLeft.flatMap(_ => futureRight)

    Sink.fromGraph(GraphDSL.create(storageSink, attributesSink)(runBothKeepRight) { implicit builder =>
      (storageSink, attributesSink) =>
        import GraphDSL.Implicits._

        val baseFlow = validateFlowWithContext(validationContexts, drainIncoming = true)
          .via(parseFlow)
          .via(collectAttributesFlow(basicInfoTags))
          .mapAsync(1)(attributesToInfoPart) // needed for e.g. maybe deflate flow

        val flow = builder.add {
          if (reverseAnonymization)
            baseFlow
              .mapAsync(1)(reverseAnonymizationKeyPartForPatient(reverseAnonymizationKeysForPatient))
              .mapConcat(identity) // flatten stream of lists
              .via(maybeReverseAnonFlow)
          else
            baseFlow
        }

        val bcast = builder.add(Broadcast[DicomPart](2))

        flow ~> bcast.in
        bcast.out(0) ~> maybeDeflateFlow.map(_.bytes) ~> storageSink
        bcast.out(1) ~> whitelistFilter(tagsToStoreInDB) ~> attributeFlow ~> attributesSink

        SinkShape(flow.in)
    })
  }

  def getOrCreateAnonKeyPart(getOrCreateAnonKey: DicomInfoPart => Future[AnonymizationKey])
                            (implicit ec: ExecutionContext): DicomPart => Future[DicomPart] = {
    case info: DicomInfoPart if info.isAnonymized =>
      Future.successful(PartialAnonymizationKeyPart(None, hasPatientInfo = false, hasStudyInfo = false, hasSeriesInfo = false))
    case info: DicomInfoPart =>
      getOrCreateAnonKey(info).map(key => PartialAnonymizationKeyPart(Some(key), hasPatientInfo = true, hasStudyInfo = true, hasSeriesInfo = true))
    case part: DicomPart => Future.successful(part)
  }

  def toTagModifications(tagValues: Seq[TagValue]): Seq[TagModification] =
    tagValues.map(tv => TagModification.endsWith(TagPath.fromTag(tv.tag), _ => padToEvenLength(ByteString(tv.value), tv.tag), insert = true))

  def anonymizedDicomDataSource(storageSource: StreamSource[DicomPart, NotUsed],
                                getOrCreateAnonKey: DicomInfoPart => Future[AnonymizationKey],
                                tagValues: Seq[TagValue])
                               (implicit ec: ExecutionContext, materializer: Materializer): StreamSource[ByteString, NotUsed] =
    storageSource // DicomPart...
      .via(collectAttributesFlow(extendedInfoTags)) // DicomAttributes :: DicomPart...
      .mapAsync(1)(attributesToInfoPart) // DicomAnonPart :: DicomPart...
      .mapAsync(1)(getOrCreateAnonKeyPart(getOrCreateAnonKey)) // AnonymizationKeyPart :: DicomPart...
      .via(maybeAnonFlow) // DicomAnonPart needed here
      .via(maybeHarmonizeAnonFlow) // DicomAnonPart and AnonymizationKeysPart needed here
      .via(modifyFlow(toTagModifications(tagValues): _*))
      .via(fmiGroupLengthFlow) // update meta information group length
      .via(maybeDeflateFlow)
      .map(_.bytes)

  def inflatedSource(source: StreamSource[ByteString, _]): StreamSource[ByteString, _] = source
    .via(parseFlow)
    .via(modifyFlow(
      TagModification.contains(TagPath.fromTag(Tag.TransferSyntaxUID), valueBytes => {
        valueBytes.utf8String.trim match {
          case UID.DeflatedExplicitVRLittleEndian => padToEvenLength(ByteString(UID.ExplicitVRLittleEndian), VR.UI)
          case _ => valueBytes
        }
      }, insert = false)))
    .via(fmiGroupLengthFlow)
    .map(_.bytes)

  def imageAttributesSource[M](source: StreamSource[ByteString, M])
                              (implicit ec: ExecutionContext, materializer: Materializer): StreamSource[ImageAttribute, M] =
    source
      .via(new DicomParseFlow(stopTag = Some(Tag.PixelData)))
      .via(bulkDataFilter)
      .via(collectAttributesFlow(encodingTags))
      .mapAsync(1)(attributesToInfoPart)
      .via(attributeFlow)
      .statefulMapConcat {
        var info: Option[DicomInfoPart] = None
        var namePath = List.empty[String]
        var tagPath = List.empty[Int]
        var tagPathSequence: Option[TagPathSequence] = None

        () => {
          case mp: DicomInfoPart =>
            info = Some(mp)
            Nil
          case attribute: DicomAttribute =>
            val tag = attribute.header.tag
            val length = attribute.valueBytes.length
            val values = attribute.header.vr match {
              case VR.OW | VR.OF | VR.OB =>
                List(s"< Binary data ($length bytes) >")
              case _ =>
                val attrs = new Attributes(attribute.bigEndian, 9)
                info.flatMap(_.specificCharacterSet).foreach(cs => attrs.setSpecificCharacterSet(cs.toCodes: _*))
                attrs.setBytes(tag, attribute.header.vr, attribute.valueBytes.toArray)
                DicomUtil.getStrings(attrs, tag).toList
            }
            val multiplicity = values.length
            val depth = tagPath.size
            val tagPathTag = tagPathSequence.map(_.thenTag(attribute.header.tag)).getOrElse(TagPath.fromTag(attribute.header.tag))

            ImageAttribute(
              tag,
              groupNumber(tag),
              elementNumber(tag),
              DicomUtil.nameForTag(tag),
              attribute.header.vr.name,
              multiplicity,
              length,
              depth,
              tagPathTag,
              tagPath,
              namePath,
              values) :: Nil
          case sq: DicomSequence =>
            namePath = namePath :+ DicomUtil.nameForTag(sq.tag)
            tagPath = tagPath :+ sq.tag
            tagPathSequence = tagPathSequence.map(_.thenSequence(sq.tag)).orElse(Some(TagPath.fromSequence(sq.tag)))
            Nil
          case _: DicomSequenceDelimitation =>
            namePath = namePath.dropRight(1)
            tagPath = tagPath.dropRight(1)
            tagPathSequence = tagPathSequence.flatMap(_.previous)
            Nil
          case fragments: DicomFragments =>
            tagPathSequence = tagPathSequence.map(_.thenSequence(fragments.tag)).orElse(Some(TagPath.fromSequence(fragments.tag)))
            Nil
          case _: DicomFragmentsDelimitation =>
            tagPathSequence = tagPathSequence.flatMap(_.previous)
            Nil
          case item: DicomItem =>
            tagPathSequence = tagPathSequence.flatMap(s => s.previous.map(_.thenSequence(s.tag, item.index)).orElse(Some(TagPath.fromSequence(s.tag, item.index))))
            Nil
          case _ => Nil
        }
      }

  def conditionalFlow(goA: PartialFunction[DicomPart, Boolean], flowA: Flow[DicomPart, DicomPart, _], flowB: Flow[DicomPart, DicomPart, _], routeADefault: Boolean = true): Flow[DicomPart, DicomPart, NotUsed] =
    Flow.fromGraph(GraphDSL.create() { implicit builder =>
      import GraphDSL.Implicits._

      var routeA = routeADefault // determines which path is taken in the graph

      // if any part indicates that alternate route should be taken, remember this
      val gateFlow = builder.add {
        Flow[DicomPart].map { part =>
          if (goA.isDefinedAt(part)) routeA = goA(part)
          part
        }
      }

      // split the flow
      val bcast = builder.add(Broadcast[DicomPart](2))

      // define gates for each path, only one path is used
      val gateA = Flow[DicomPart].filter(_ => routeA)
      val gateB = Flow[DicomPart].filterNot(_ => routeA)

      // merge the two paths
      val merge = builder.add(Merge[DicomPart](2))

      // surround each flow by gates, remember that flows may produce items without input
      gateFlow ~> bcast ~> gateA ~> flowA ~> gateA ~> merge
      bcast ~> gateB ~> flowB ~> gateB ~> merge

      FlowShape(gateFlow.in, merge.out)
    })

}