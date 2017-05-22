package se.nimsa.sbx.dicom.streams

import akka.{Done, NotUsed}
import akka.actor.{ActorRef, ActorSystem}
import akka.pattern.ask
import akka.stream.{ActorMaterializer, FlowShape, SinkShape}
import akka.stream.scaladsl.{Broadcast, Flow, GraphDSL, Merge, Sink, Source}
import akka.util.{ByteString, Timeout}
import org.dcm4che3.data.{Attributes, Tag}
import se.nimsa.dcm4che.streams.DicomFlows._
import se.nimsa.dcm4che.streams.DicomPartFlow.partFlow
import se.nimsa.dcm4che.streams.DicomParts.{DicomAttributes, DicomPart}
import se.nimsa.dcm4che.streams.{DicomAttributesSink, DicomFlows, DicomParsing}
import se.nimsa.sbx.anonymization.AnonymizationProtocol.{AnonymizationKeys, GetReverseAnonymizationKeys}
import se.nimsa.sbx.dicom.Contexts
import se.nimsa.sbx.storage.StorageService

import scala.concurrent.{ExecutionContext, Future}

object DicomStreams {

  val tagsToStoreInDB = {
    val patientTags = Seq(Tag.PatientName, Tag.PatientID, Tag.PatientSex, Tag.PatientBirthDate)
    val studyTags = Seq(Tag.StudyInstanceUID, Tag.StudyDescription, Tag.StudyID, Tag.StudyDate, Tag.AccessionNumber, Tag.PatientAge)
    val seriesTags = Seq(Tag.SeriesInstanceUID, Tag.SeriesDescription, Tag.SeriesDate, Tag.Modality, Tag.ProtocolName, Tag.BodyPartExamined, Tag.Manufacturer, Tag.StationName, Tag.FrameOfReferenceUID)
    val imageTags = Seq(Tag.SOPInstanceUID, Tag.ImageType, Tag.InstanceNumber)
    val other = Seq(Tag.SpecificCharacterSet) //needed to decode strings

    patientTags ++ studyTags ++ seriesTags ++ imageTags ++ other
  }

  val metaTags2Collect = Set(Tag.TransferSyntaxUID, Tag.SpecificCharacterSet, Tag.PatientName, Tag.PatientID, Tag.PatientIdentityRemoved, Tag.StudyInstanceUID, Tag.SeriesInstanceUID)

  def maybeMapAttributes(dicomPart: DicomPart)
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
              dsMaybe.flatMap(ds => Option(ds.getString(Tag.SeriesInstanceUID))),
              None)
        }
      case part: DicomPart => Future.successful(part)
    }
  }

  def maybeDeflateFlow: Flow[DicomPart, DicomPart, NotUsed] = Flow.fromGraph(GraphDSL.create() { implicit builder =>
    import GraphDSL.Implicits._

    var shouldDeflate = false // determines which path is taken in the graph, with or without deflating

    // check transfer syntax in the meta part
    val shouldDeflateFlow = builder.add {
      Flow[DicomPart].map {
        case p: DicomMetaPart =>
          shouldDeflate = p.transferSyntaxUid.isDefined && DicomParsing.isDeflated(p.transferSyntaxUid.get)
          p
        case p => p
      }
    }

    // split the flow
    val bcast = builder.add(Broadcast[DicomPart](2))

    // define gates for each path, only one path is used
    val deflateYes = Flow[DicomPart].filter(_ => shouldDeflate)
    val deflateNo = Flow[DicomPart].filterNot(_ => shouldDeflate)

    // the deflate stage
    val deflate = DicomFlows.deflateDatasetFlow()

    // merge the two paths
    val merge = builder.add(Merge[DicomPart](2))

    shouldDeflateFlow ~> bcast ~> deflateYes ~> deflate ~> merge
    bcast ~> deflateNo ~> merge

    FlowShape(shouldDeflateFlow.in, merge.out)
  })

  def maybeAnonymizationLookup(anonymizationService: ActorRef, dicomPart: DicomPart)(implicit ec: ExecutionContext, timeout: Timeout): Future[DicomPart] = {
    dicomPart match {
      case meta: DicomMetaPart =>
        if (meta.isAnonymized && meta.patientName.isDefined && meta.patientId.isDefined) {
          anonymizationService.ask(GetReverseAnonymizationKeys(meta.patientName.get, meta.patientId.get)).mapTo[AnonymizationKeys].map { keys: AnonymizationKeys =>
            if (meta.studyInstanceUID.isEmpty) {
              throw new RuntimeException("StudyInstanceUID not found in DicomMetaPart")
            }
            if (meta.seriesInstanceUID.isEmpty) {
              throw new RuntimeException("SeriesInstanceUID not found in DicomMetaPart")
            }
            val filtered = keys.anonymizationKeys.filter(key => (key.anonStudyInstanceUID == meta.studyInstanceUID.get) && (key.anonSeriesInstanceUID == meta.seriesInstanceUID.get))
            DicomMetaPart(meta.transferSyntaxUid, meta.specificCharacterSet, meta.patientId, meta.patientName, meta.identityRemoved, meta.studyInstanceUID, meta.seriesInstanceUID, filtered.headOption)
          }
        } else {
          Future.successful(meta)
        }

      case part: DicomPart =>
        Future.successful(part)
    }
  }

  def createTempPath() = s"tmp-${java.util.UUID.randomUUID().toString}"

  def uploadSink(path: String, storage: StorageService, anonymizationService: ActorRef)
                (implicit ec: ExecutionContext, system: ActorSystem, materializer: ActorMaterializer, timeout: Timeout): Sink[ByteString, Future[(Done, (Option[Attributes], Option[Attributes]))]] = {

    val dicomFileSink = storage.fileSink(path)
    val dbAttributesSink = DicomAttributesSink.attributesSink

    val validationContexts = Contexts.asNamePairs(Contexts.imageDataContexts).map { pair =>
      ValidationContext(pair._1, pair._2)
    }

    Sink.fromGraph(GraphDSL.create(dicomFileSink, dbAttributesSink)(_ zip _) { implicit builder =>
      (dicomFileSink, dbAttributesSink) =>
        import GraphDSL.Implicits._

        val flow = builder.add {
          validateFlowWithContext(validationContexts).
            via(partFlow).
            via(groupLengthDiscardFilter).
            via(collectAttributesFlow(metaTags2Collect)).
            mapAsync(5)(maybeMapAttributes).
            mapAsync(5)(maybeAnonymizationLookup(anonymizationService, _)).
            via(ReverseAnonymizationFlow.reverseAnonFlow)
        }

        val bcast = builder.add(Broadcast[DicomPart](2))

        flow ~> bcast.in
        bcast.out(0) ~> maybeDeflateFlow.map(_.bytes) ~> dicomFileSink
        bcast.out(1) ~> whitelistFilter(tagsToStoreInDB) ~> attributeFlow ~> dbAttributesSink

        SinkShape(flow.in)
    })


  }
}
