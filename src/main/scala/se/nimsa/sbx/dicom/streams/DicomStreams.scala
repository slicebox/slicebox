package se.nimsa.sbx.dicom.streams

import akka.actor.ActorSystem
import akka.stream.scaladsl.{Broadcast, Flow, GraphDSL, Merge, Sink, Source}
import akka.stream.{ActorMaterializer, FlowShape, SinkShape}
import akka.util.{ByteString, Timeout}
import akka.{Done, NotUsed}
import org.dcm4che3.data.{Attributes, Tag, UID}
import se.nimsa.dcm4che.streams.DicomFlows._
import se.nimsa.dcm4che.streams.DicomPartFlow.partFlow
import se.nimsa.dcm4che.streams.DicomParts.{DicomAttributes, DicomPart}
import se.nimsa.dcm4che.streams.{DicomAttributesSink, DicomFlows, DicomParsing, DicomPartFlow}
import se.nimsa.sbx.anonymization.AnonymizationProtocol.AnonymizationKey
import se.nimsa.sbx.dicom.Contexts
import se.nimsa.sbx.dicom.DicomPropertyValue.{PatientID, PatientName}

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

  def maybeDeflateFlow: Flow[DicomPart, DicomPart, NotUsed] = conditionalFlow(
    {
      case p: DicomMetaPart => p.transferSyntaxUid.isDefined && DicomParsing.isDeflated(p.transferSyntaxUid.get)
      case _ => false
    }, DicomFlows.deflateDatasetFlow)

  def maybeAnonymizationLookup(reverseAnonymizationQuery: (PatientName, PatientID) => Future[Seq[AnonymizationKey]], dicomPart: DicomPart)(implicit ec: ExecutionContext, timeout: Timeout): Future[List[DicomPart]] = {
    dicomPart match {
      case meta: DicomMetaPart =>
        val maybeFutureKey = for {
          patientName <- meta.patientName if meta.isAnonymized
          patientId <- meta.patientId
          studyInstanceUID <- meta.studyInstanceUID
          seriesInstanceUID <- meta.seriesInstanceUID
        } yield {
          reverseAnonymizationQuery(PatientName(patientName), PatientID(patientId)).map { keys =>
            val filtered = keys.find(key =>
              key.anonStudyInstanceUID == studyInstanceUID && key.anonSeriesInstanceUID == seriesInstanceUID)
            AnonymizationKeyPart(filtered)
          }
        }
        maybeFutureKey.map(_.map(meta :: _ :: Nil)).getOrElse(Future.successful(meta :: Nil))
      case part: DicomPart =>
        Future.successful(part :: Nil)
    }
  }

  def createTempPath() = s"tmp-${java.util.UUID.randomUUID().toString}"

  def storeDicomDataSink(storageSink: Sink[ByteString, Future[Done]], reverseAnonymizationQuery: (PatientName, PatientID) => Future[Seq[AnonymizationKey]])
                        (implicit ec: ExecutionContext, system: ActorSystem, materializer: ActorMaterializer, timeout: Timeout): Sink[ByteString, Future[(Done, (Option[Attributes], Option[Attributes]))]] = {

    val dbAttributesSink = DicomAttributesSink.attributesSink

    val validationContexts = Contexts.asNamePairs(Contexts.imageDataContexts).map { pair =>
      ValidationContext(pair._1, pair._2)
    }

    Sink.fromGraph(GraphDSL.create(storageSink, dbAttributesSink)(_ zip _) { implicit builder =>
      (dicomFileSink, dbAttributesSink) =>
        import GraphDSL.Implicits._

        val flow = builder.add {
          validateFlowWithContext(validationContexts)
            .via(partFlow)
            .via(groupLengthDiscardFilter)
            .via(collectAttributesFlow(metaTags2Collect))
            .mapAsync(5)(attributesToMetaPart)
            .mapAsync(5)(maybeAnonymizationLookup(reverseAnonymizationQuery, _))
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

  def inflatedSource(source: Source[ByteString, _]): Source[ByteString, _] = source
    .via(DicomPartFlow.partFlow)
    .via(DicomFlows.modifyFlow(
      TagModification(Tag.TransferSyntaxUID, valueBytes => {
        new String(valueBytes.toArray, "US-ASCII") match {
          case UID.DeflatedExplicitVRLittleEndian => ByteString(UID.ExplicitVRLittleEndian.getBytes("US-ASCII"))
          case _ => valueBytes
        }
      }, insert = false)))
    .map(_.bytes)

  def conditionalFlow[M](alternateRouteIndicator: DicomPart => Boolean, alternateFlow: Flow[DicomPart, DicomPart, M]) = Flow.fromGraph(GraphDSL.create() { implicit builder =>
    import GraphDSL.Implicits._

    var alternateRoute = false // determines which path is taken in the graph

    // if any part indicates that alternate route should be taken, remember this
    val shouldTakeAlternateRouteFlow = builder.add {
      Flow[DicomPart].map { part =>
        if (alternateRouteIndicator(part))
          alternateRoute = true
        part
      }
    }

    // split the flow
    val bcast = builder.add(Broadcast[DicomPart](2))

    // define gates for each path, only one path is used
    val alternateYes = Flow[DicomPart].filter(_ => alternateRoute)
    val alternateNo = Flow[DicomPart].filterNot(_ => alternateRoute)

    // merge the two paths
    val merge = builder.add(Merge[DicomPart](2))

    shouldTakeAlternateRouteFlow ~> bcast ~> alternateYes ~> alternateFlow ~> merge
    bcast ~> alternateNo ~> merge

    FlowShape(shouldTakeAlternateRouteFlow.in, merge.out)
  })
}
