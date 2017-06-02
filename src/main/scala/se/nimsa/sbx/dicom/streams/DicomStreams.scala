package se.nimsa.sbx.dicom.streams

import akka.actor.ActorSystem
import akka.stream.scaladsl.{Broadcast, Compression, Flow, GraphDSL, Keep, Merge, Sink, Source}
import akka.stream.{ActorMaterializer, FlowShape, SinkShape}
import akka.util.{ByteString, Timeout}
import akka.{Done, NotUsed}
import org.dcm4che3.data.{Attributes, Tag, UID, VR}
import se.nimsa.dcm4che.streams.DicomFlows._
import se.nimsa.dcm4che.streams.DicomPartFlow.partFlow
import se.nimsa.dcm4che.streams.DicomParts.{DicomAttributes, DicomPart}
import se.nimsa.dcm4che.streams.{DicomAttributesSink, DicomFlows, DicomParsing, DicomPartFlow}
import se.nimsa.sbx.anonymization.AnonymizationProtocol.AnonymizationKey
import se.nimsa.sbx.anonymization.AnonymizationUtil.isEqual
import se.nimsa.sbx.dicom.{Contexts, DicomUtil}
import se.nimsa.sbx.dicom.DicomPropertyValue.{PatientID, PatientName}
import se.nimsa.sbx.dicom.DicomUtil.{attributesToPatient, attributesToSeries, attributesToStudy}

import scala.concurrent.{ExecutionContext, Future}

object DicomStreams {

  val encodingTags = Set(Tag.TransferSyntaxUID, Tag.SpecificCharacterSet)

  val tagsToStoreInDB = {
    val patientTags = Seq(Tag.PatientName, Tag.PatientID, Tag.PatientSex, Tag.PatientBirthDate)
    val studyTags = Seq(Tag.StudyInstanceUID, Tag.StudyDescription, Tag.StudyID, Tag.StudyDate, Tag.AccessionNumber, Tag.PatientAge)
    val seriesTags = Seq(Tag.SeriesInstanceUID, Tag.SeriesDescription, Tag.SeriesDate, Tag.Modality, Tag.ProtocolName, Tag.BodyPartExamined, Tag.Manufacturer, Tag.StationName, Tag.FrameOfReferenceUID)
    val imageTags = Seq(Tag.SOPInstanceUID, Tag.ImageType, Tag.InstanceNumber)

    encodingTags ++ patientTags ++ studyTags ++ seriesTags ++ imageTags
  }

  val metaTags2Collect = encodingTags ++ Set(Tag.PatientName, Tag.PatientID, Tag.PatientIdentityRemoved, Tag.StudyInstanceUID, Tag.SeriesInstanceUID)

  val anonymizationKeyTags = encodingTags ++ Set(Tag.PatientName, Tag.PatientID, Tag.PatientBirthDate,
    Tag.StudyInstanceUID, Tag.StudyDescription, Tag.StudyID, Tag.AccessionNumber,
    Tag.SeriesInstanceUID, Tag.SeriesDescription, Tag.ProtocolName, Tag.FrameOfReferenceUID)

  def maybeDeflateFlow: Flow[DicomPart, DicomPart, NotUsed] = conditionalFlow(
    {
      case p: DicomMetaPart => p.transferSyntaxUid.isDefined && DicomParsing.isDeflated(p.transferSyntaxUid.get)
    }, DicomFlows.deflateDatasetFlow, Flow.fromFunction(identity))

  def createTempPath() = s"tmp-${java.util.UUID.randomUUID().toString}"

  def attributesToMetaPart(dicomPart: DicomPart)
                          (implicit ec: ExecutionContext, system: ActorSystem, materializer: ActorMaterializer): Future[DicomPart] = {
    dicomPart match {
      case da: DicomAttributes =>
        Source.fromIterator(() => da.attributes.iterator).runWith(DicomAttributesSink.attributesSink).map {
          case (fmiMaybe, dsMaybe) =>
            DicomMetaPart(
              fmiMaybe.flatMap(fmi => Option(fmi.getString(Tag.TransferSyntaxUID))),
              dsMaybe.flatMap(ds => Option(ds.getSpecificCharacterSet)),
              dsMaybe.flatMap(ds => Option(ds.getString(Tag.PatientID))),
              dsMaybe.flatMap(ds => Option(ds.getString(Tag.PatientName))),
              dsMaybe.flatMap(ds => Option(ds.getString(Tag.PatientIdentityRemoved))),
              dsMaybe.flatMap(ds => Option(ds.getString(Tag.StudyInstanceUID))),
              dsMaybe.flatMap(ds => Option(ds.getString(Tag.SeriesInstanceUID))))
        }
      case part: DicomPart => Future.successful(part)
    }
  }

  def queryProtectedAnonymizationKeys(query: (PatientName, PatientID) => Future[Seq[AnonymizationKey]])
                            (implicit ec: ExecutionContext, timeout: Timeout): DicomPart => Future[List[DicomPart]] = {
    case meta: DicomMetaPart =>
      val maybeFutureKey = for {
        patientName <- meta.patientName if meta.isAnonymized
        patientId <- meta.patientId
      } yield {
        query(PatientName(patientName), PatientID(patientId)).map { patientKeys =>
          val studyKeys = meta.studyInstanceUID.map(studyUID => patientKeys.filter(_.anonStudyInstanceUID == studyUID)).getOrElse(Seq.empty)
          val seriesKeys = meta.seriesInstanceUID.map(seriesUID => studyKeys.filter(_.anonSeriesInstanceUID == seriesUID)).getOrElse(Seq.empty)
          meta :: AnonymizationKeysPart(patientKeys, patientKeys.headOption, studyKeys.headOption, seriesKeys.headOption) :: Nil
        }
      }
      maybeFutureKey.getOrElse(Future.successful(meta :: AnonymizationKeysPart(Seq.empty, None, None, None) :: Nil))
    case part: DicomPart =>
      Future.successful(part :: Nil)
  }

  def queryAnonymousAnonymizationKeys(query: (PatientName, PatientID) => Future[Seq[AnonymizationKey]])
                            (implicit ec: ExecutionContext, timeout: Timeout): DicomPart => Future[List[DicomPart]] = {
    case meta: DicomMetaPart =>
      val maybeFutureKey = for {
        patientName <- meta.patientName
        patientId <- meta.patientId
      } yield {
        query(PatientName(patientName), PatientID(patientId)).map { patientKeys =>
          val studyKeys = meta.studyInstanceUID.map(studyUID => patientKeys.filter(_.studyInstanceUID == studyUID)).getOrElse(Seq.empty)
          val seriesKeys = meta.seriesInstanceUID.map(seriesUID => studyKeys.filter(_.seriesInstanceUID == seriesUID)).getOrElse(Seq.empty)
          meta :: AnonymizationKeysPart(patientKeys, patientKeys.headOption, studyKeys.headOption, seriesKeys.headOption) :: Nil
        }
      }
      maybeFutureKey.getOrElse(Future.successful(meta :: AnonymizationKeysPart(Seq.empty, None, None, None) :: Nil))
    case part: DicomPart =>
      Future.successful(part :: Nil)
  }

  def dicomDataSink(storageSink: Sink[ByteString, Future[Done]], reverseAnonymizationQuery: (PatientName, PatientID) => Future[Seq[AnonymizationKey]])
                   (implicit ec: ExecutionContext, system: ActorSystem, materializer: ActorMaterializer, timeout: Timeout): Sink[ByteString, Future[(Option[Attributes], Option[Attributes])]] = {

    val dbAttributesSink = DicomAttributesSink.attributesSink

    val validationContexts = Contexts.asNamePairs(Contexts.imageDataContexts).map { pair =>
      ValidationContext(pair._1, pair._2)
    }

    Sink.fromGraph(GraphDSL.create(storageSink, dbAttributesSink)(Keep.right) { implicit builder =>
      (dicomFileSink, dbAttributesSink) =>
        import GraphDSL.Implicits._

        val flow = builder.add {
          validateFlowWithContext(validationContexts)
            .via(partFlow)
            .via(groupLengthDiscardFilter)
            .via(collectAttributesFlow(metaTags2Collect))
            .mapAsync(5)(attributesToMetaPart)
            .mapAsync(5)(queryProtectedAnonymizationKeys(reverseAnonymizationQuery))
            .mapConcat(identity) // flatten stream of lists
            .via(ReverseAnonymizationFlow.maybeReverseAnonFlow)
        }

        val bcast = builder.add(Broadcast[DicomPart](2))

        flow ~> bcast.in
        bcast.out(0) ~> maybeDeflateFlow.map(_.bytes) ~> dicomFileSink
        bcast.out(1) ~> whitelistFilter(tagsToStoreInDB) ~> attributeFlow ~> dbAttributesSink

        SinkShape(flow.in)
    })
  }

  def attributesToAnonKeyProtectedInfo(dicomPart: DicomPart)
                                      (implicit ec: ExecutionContext, system: ActorSystem, materializer: ActorMaterializer): Future[DicomPart] = {
    dicomPart match {
      case da: DicomAttributes =>
        Source.fromIterator(() => da.attributes.iterator).runWith(DicomAttributesSink.attributesSink).map {
          case (_, dsMaybe) =>
            val attributes = dsMaybe.getOrElse(new Attributes())
            val patient = attributesToPatient(attributes)
            val study = attributesToStudy(attributes)
            val series = attributesToSeries(attributes)
            AnonymizationKeyPart(
              AnonymizationKey(-1, -1,
                patient.patientName.value, "", patient.patientID.value, "", patient.patientBirthDate.value,
                study.studyInstanceUID.value, "", study.studyDescription.value, study.studyID.value, study.accessionNumber.value,
                series.seriesInstanceUID.value, "", series.seriesDescription.value, series.protocolName.value, series.frameOfReferenceUID.value, ""))
        }
      case part: DicomPart => Future.successful(part)
    }
  }

  def attributesToAnonKeyAnonymousInfo(dicomPart: DicomPart)
                                      (implicit ec: ExecutionContext, system: ActorSystem, materializer: ActorMaterializer): Future[DicomPart] = {
    dicomPart match {
      case da: DicomAttributes =>
        Source.fromIterator(() => da.attributes.iterator).runWith(DicomAttributesSink.attributesSink).map {
          case (_, dsMaybe) =>
            val attributes = dsMaybe.getOrElse(new Attributes())
            val patient = attributesToPatient(attributes)
            val study = attributesToStudy(attributes)
            val series = attributesToSeries(attributes)
            AnonymizationKeyPart(
              AnonymizationKey(-1, -1,
                "", patient.patientName.value, "", patient.patientID.value, "",
                "", study.studyInstanceUID.value, "", "", "",
                "", series.seriesInstanceUID.value, "", "", "", series.frameOfReferenceUID.value))
        }
      case part: DicomPart => Future.successful(part)
    }
  }

  def collectAnonymizationKeyProtectedInfo(implicit ec: ExecutionContext, system: ActorSystem, materializer: ActorMaterializer, timeout: Timeout) =
    Flow[DicomPart]
      .via(collectAttributesFlow(anonymizationKeyTags))
      .mapAsync(5)(attributesToAnonKeyProtectedInfo)

  def collectAnonymizationKeyAnonymousInfo(implicit ec: ExecutionContext, system: ActorSystem, materializer: ActorMaterializer, timeout: Timeout) =
    Flow[DicomPart]
      .via(collectAttributesFlow(anonymizationKeyTags))
      .mapAsync(5)(attributesToAnonKeyAnonymousInfo)

  def collectAnonymizationKeyInfo: Flow[DicomPart, DicomPart, NotUsed] =
    Flow[DicomPart]
      .statefulMapConcat {
        () =>
          var maybeKeys: Option[AnonymizationKeysPart] = None
          var maybeProtectedKey: Option[AnonymizationKeyPart] = None
          var maybeAnonymousKey: Option[AnonymizationKeyPart] = None
          def maybeEmit() = maybeKeys
            .flatMap(keys => maybeProtectedKey
              .flatMap(protectedKey => maybeAnonymousKey
                .map { anonymousKey =>
                  val fullKey = protectedKey.key.copy(
                    anonPatientName = anonymousKey.key.anonPatientName,
                    anonPatientID = anonymousKey.key.anonPatientID,
                    anonStudyInstanceUID = anonymousKey.key.anonStudyInstanceUID,
                    anonSeriesInstanceUID = anonymousKey.key.anonSeriesInstanceUID,
                    anonFrameOfReferenceUID = anonymousKey.key.anonFrameOfReferenceUID)
                  AnonymizationKeyInfoPart(keys.allKeys, fullKey) :: Nil
                })).getOrElse(Nil)

        {
          case keys: AnonymizationKeysPart =>
            maybeKeys = Some(keys)
            maybeEmit()
          case key: AnonymizationKeyPart =>
            // anon key will be first in stream, followed by protected key
            if (maybeAnonymousKey.isDefined) maybeProtectedKey = Some(key) else maybeAnonymousKey = Some(key)
            maybeEmit()
          case p =>
            p :: Nil
        }
      }

  def maybeInsertAnonymizationKey(anonymizationInsert: AnonymizationKey => Future[AnonymizationKey])
                                 (implicit ec: ExecutionContext, timeout: Timeout): DicomPart => Future[DicomPart] = {
    case info: AnonymizationKeyInfoPart =>
      val dbAnonymizationKey = info.existingKeys.find(isEqual(_, info.newKey))
        .map(Future.successful)
        .getOrElse(anonymizationInsert(info.newKey))
      dbAnonymizationKey.map(key => info.copy(newKey = key))
    case part: DicomPart =>
      Future.successful(part)
  }

  def dicomDataSource(storageSource: Source[ByteString, NotUsed]): Source[DicomPart, NotUsed] =
    storageSource
      .via(DicomPartFlow.partFlow)

  def anonymizedDicomDataSource(storageSource: Source[ByteString, NotUsed],
                                anonymizationQuery: (PatientName, PatientID) => Future[Seq[AnonymizationKey]],
                                anonymizationInsert: AnonymizationKey => Future[AnonymizationKey],
                                tagMods: Seq[TagModification])
                               (implicit ec: ExecutionContext, system: ActorSystem, materializer: ActorMaterializer, timeout: Timeout): Source[ByteString, NotUsed] =
    dicomDataSource(storageSource) // DicomPart...
      .via(collectAttributesFlow(metaTags2Collect)) // DicomAttributes :: DicomPart...
      .mapAsync(5)(attributesToMetaPart) // DicomMetaPart :: DicomPart...
      .mapAsync(5)(queryAnonymousAnonymizationKeys(anonymizationQuery))
      .mapConcat(identity) // DicomMetaPart :: AnonymizationKeysPart :: DicomPart...
      .via(collectAnonymizationKeyProtectedInfo) // AnonymizationKeyPart (protected) :: DicomMetaPart :: AnonymizationKeysPart :: DicomPart...
      .via(AnonymizationFlow.maybeAnonFlow)
      .via(HarmonizeAnonymizationFlow.harmonizeAnonFlow)
      .via(DicomFlows.modifyFlow(tagMods: _*))
      .via(collectAnonymizationKeyAnonymousInfo) // AnonymizationKeyPart (protected) :: AnonymizationKeyPart (anon) :: DicomMetaPart :: AnonymizationKeysPart :: DicomPart...
      .via(collectAnonymizationKeyInfo) // DicomMetaPart :: AnonymizationKeyInfoPart :: DicomPart...
      .mapAsync(5)(maybeInsertAnonymizationKey(anonymizationInsert))
      .map(_.bytes)
      .via(Compression.deflate)

  def inflatedSource(source: Source[ByteString, _]): Source[ByteString, _] = source
    .via(DicomPartFlow.partFlow)
    .via(DicomFlows.modifyFlow(
      TagModification(Tag.TransferSyntaxUID, valueBytes => {
        valueBytes.utf8String.trim match {
          case UID.DeflatedExplicitVRLittleEndian => DicomUtil.padToEvenLength(ByteString(UID.ExplicitVRLittleEndian.getBytes("US-ASCII")), VR.UI)
          case _ => valueBytes
        }
      }, insert = false)))
    .map(_.bytes)

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
