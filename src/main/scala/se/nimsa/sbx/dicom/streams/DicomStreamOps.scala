/*
 * Copyright 2014 Lars Edenbrandt
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package se.nimsa.sbx.dicom.streams

import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream

import akka.actor.Cancellable
import akka.stream.scaladsl.{Broadcast, Flow, GraphDSL, Sink, StreamConverters, Source => StreamSource}
import akka.stream.{Materializer, SinkShape}
import akka.util.ByteString
import akka.{Done, NotUsed}
import javax.imageio.ImageIO
import org.dcm4che3.imageio.plugins.dcm.DicomImageReadParam
import se.nimsa.dicom.data.DicomParts._
import se.nimsa.dicom.data.Elements._
import se.nimsa.dicom.data.TagPath.TagPathTag
import se.nimsa.dicom.data.{DicomParsing, Elements, Keyword, _}
import se.nimsa.dicom.streams.CollectFlow._
import se.nimsa.dicom.streams.DicomFlows._
import se.nimsa.dicom.streams.ElementFlows._
import se.nimsa.dicom.streams.ElementSink.elementSink
import se.nimsa.dicom.streams.ModifyFlow._
import se.nimsa.dicom.streams.{DicomStreamException, ParseFlow}
import se.nimsa.sbx.anonymization.AnonymizationProtocol._
import se.nimsa.sbx.app.GeneralProtocol.{Source, SourceType}
import se.nimsa.sbx.dicom.Contexts.Context
import se.nimsa.sbx.dicom.DicomHierarchy.Image
import se.nimsa.sbx.dicom.DicomPropertyValue.{PatientID, PatientName}
import se.nimsa.sbx.dicom.{Contexts, ImageAttribute}
import se.nimsa.sbx.filtering.FilteringProtocol.TagFilterType.{BLACKLIST, WHITELIST}
import se.nimsa.sbx.filtering.FilteringProtocol.{GetFilterForSource, TagFilterSpec}
import se.nimsa.sbx.lang.NotFoundException
import se.nimsa.sbx.metadata.MetaDataProtocol._
import se.nimsa.sbx.storage.StorageProtocol.ImageInformation
import se.nimsa.sbx.storage.StorageService
import se.nimsa.sbx.util.SbxExtensions._

import scala.concurrent.duration.{DurationInt, FiniteDuration}
import scala.concurrent.{ExecutionContext, Future}
import scala.reflect.ClassTag

/**
  * Stream operations for loading and saving DICOM data from and to storage
  */
trait DicomStreamOps {

  import AnonymizationFlow._
  import DicomStreamUtil._
  import HarmonizeAnonymizationFlow._
  import ReverseAnonymizationFlow._

  def callAnonymizationService[R: ClassTag](message: Any): Future[R]
  def callMetaDataService[R: ClassTag](message: Any): Future[R]
  def callFilteringService[R: ClassTag](message: Any): Future[R]
  def scheduleTask(delay: FiniteDuration)(task: => Unit): Cancellable

  /**
    * Creates a streaming source of anonymized and harmonized DICOM data
    *
    * @param imageId   ID of image to load
    * @param tagValues forced values of attributes as pairs of tag number and string value encoded in UTF-8 (ASCII) format
    * @return a `Source` of anonymized DICOM byte chunks
    */
  protected def anonymizedDicomData(imageId: Long, tagValues: Seq[TagValue], storage: StorageService)(implicit ec: ExecutionContext): StreamSource[ByteString, NotUsed] = {
    val source = storage.dataSource(imageId, None)
    anonymizedDicomDataSource(source, getOrCreateAnonKey(tagValues), tagValues)
  }

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
  protected def storeDicomData(bytesSource: StreamSource[ByteString, _], source: Source, storage: StorageService, contexts: Seq[Context], reverseAnonymization: Boolean)
                              (implicit materializer: Materializer, ec: ExecutionContext): Future[MetaDataAdded] = {
    val tempPath = createTempPath()
    val filter = callFilteringService[Option[TagFilterSpec]](GetFilterForSource(source))
    println(filter)
    filter.flatMap(maybeTagFilter => {
      val tagFilter = maybeTagFilter.map(tagFilterSpecToFlow(_)).getOrElse(NO_ACTION_FILTER)

      val sink = dicomDataSink(storage.fileSink(tempPath), storage.parseFlow(None), reverseAnonymizationKeysForPatient, contexts, reverseAnonymization, tagFilter)
      bytesSource.runWith(sink)
        .flatMap(elements => storeDicomData(elements, source, tempPath, storage))
        .recover {
          case t: Throwable =>
            scheduleTask(30.seconds) {
              storage.deleteByName(Seq(tempPath)) // delete temp file once file system has released handle
            }
            if (!t.isInstanceOf[DicomStreamException]) throw new DicomStreamException(t.getMessage) else throw t
        }
    })
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
  protected def anonymizeData(imageId: Long, tagValues: Seq[TagValue], storage: StorageService)
                             (implicit materializer: Materializer, ec: ExecutionContext): Future[Option[MetaDataAdded]] =
    callMetaDataService[Option[Image]](GetImage(imageId)).flatMap { imageMaybe =>
      imageMaybe.map { image =>
        callMetaDataService[Option[SeriesSource]](GetSourceForSeries(image.seriesId)).map { seriesSourceMaybe =>
          seriesSourceMaybe.map { seriesSource =>
            val forcedSource = storage
              .dataSource(imageId, None)
              .via(blacklistFilter(Set(TagPath.fromTag(Tag.PatientIdentityRemoved), TagPath.fromTag(Tag.DeidentificationMethod))))
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


  protected def modifyData(imageId: Long, tagModifications: Seq[TagModification], storage: StorageService)
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
    val sink = dicomDataSink(storage.fileSink(tempPath), storage.parseFlow(None), reverseAnonymizationKeysForPatient, Contexts.extendedContexts, reverseAnonymization = false, NO_ACTION_FILTER)

    val futureModifiedTempFile =
      storage
        .dataSource(imageId, None)
        .via(groupLengthDiscardFilter)
        .via(toIndeterminateLengthSequences)
        .via(toUtf8Flow)
        .via(modifyFlow(tagModifications: _*))
        .via(fmiGroupLengthFlow)
        .map(_.bytes)
        .runWith(sink)

    for {
      (sourceMaybe, tags) <- futureSourceAndTags
      source = sourceMaybe.getOrElse(Source(SourceType.UNKNOWN, SourceType.UNKNOWN.toString, -1))
      elements <- futureModifiedTempFile
      metaDataDeleted <- callMetaDataService[MetaDataDeleted](DeleteMetaData(Seq(imageId)))
      _ = storage.deleteFromStorage(Seq(imageId))
      metaDataAdded <- storeDicomData(elements, source, tempPath, storage)
      seriesId = metaDataAdded.series.id
      _ <- Future.sequence {
        tags.map { tag =>
          callMetaDataService[SeriesTagAddedToSeries](AddSeriesTagToSeries(tag, seriesId))
        }
      }
    } yield (metaDataDeleted, metaDataAdded)

  }

  protected def readImageAttributes(imageId: Long, storage: StorageService): StreamSource[ImageAttribute, NotUsed] =
    storage
      .dataSource(imageId, Some(Tag.PixelData))
      .via(bulkDataFilter)
      .via(elementFlow)
      .via(tagPathFlow)
      .statefulMapConcat {
        var characterSets = CharacterSets.defaultOnly

        () => {
          case (tagPath: TagPath, element: ValueElement) =>
            if (element.tag == Tag.SpecificCharacterSet)
              characterSets = CharacterSets(element)

            val tag = element.tag
            val length = element.length
            val values = element.vr match {
              case VR.OW | VR.OF | VR.OB | VR.OD if length > 20 => List(s"< Binary data ($length bytes) >")
              case _ => element.value.toStrings(element.vr, element.bigEndian, characterSets).toList
            }
            val multiplicity = values.length
            val tagPathTag = tagPath match {
              case tp: TagPathTag => tp
              case tp => tp.previous.thenTag(tp.tag) // should not happen
            }
            val tagPathTags = tagPath.toList.map(_.tag)
            val namePath = tagPathTags.init.map(Keyword.valueOf)

            ImageAttribute(
              tag,
              groupNumber(tag),
              elementNumber(tag),
              Keyword.valueOf(tag),
              element.vr.toString,
              multiplicity,
              length,
              tagPath.depth,
              tagPathTag,
              tagPathTags,
              namePath,
              values) :: Nil
          case _ => Nil
        }
      }

  protected def readImageInformation(imageId: Long, storage: StorageService)(implicit materializer: Materializer, ec: ExecutionContext): Future[ImageInformation] =
    storage
      .dataSource(imageId, Some(Tag.LargestImagePixelValue + 1))
      .via(whitelistFilter(imageInformationTags))
      .via(elementFlow)
      .runWith(elementSink)
      .map { elements =>
        val instanceNumber = elements.getInt(Tag.InstanceNumber).getOrElse(1)
        val imageIndex = elements.getInt(Tag.ImageIndex).getOrElse(1)
        val frameIndex = if (instanceNumber > imageIndex) instanceNumber else imageIndex
        ImageInformation(
          elements.getInt(Tag.NumberOfFrames).getOrElse(1),
          frameIndex.toInt,
          elements.getInt(Tag.SmallestImagePixelValue).getOrElse(0),
          elements.getInt(Tag.LargestImagePixelValue).getOrElse(0)
        )
      }

  protected def readPngImageData(imageId: Long, frameNumber: Int, windowMin: Int, windowMax: Int, imageHeight: Int, storage: StorageService)(implicit materializer: Materializer, ec: ExecutionContext): Future[Array[Byte]] = Future {
    // dcm4che does not support viewing of deflated data, cf. Github issue #42
    // As a workaround, do streaming inflate and mapping of transfer syntax
    val source = inflatedSource(storage.dataSource(imageId, None))
    val is = source.runWith(StreamConverters.asInputStream())
    val iis = ImageIO.createImageInputStream(is)

    val imageReader = ImageIO.getImageReadersByFormatName("DICOM").next
    imageReader.setInput(iis)
    val param = imageReader.getDefaultReadParam.asInstanceOf[DicomImageReadParam]
    if (windowMin < windowMax) {
      param.setWindowCenter((windowMax - windowMin) / 2)
      param.setWindowWidth(windowMax - windowMin)
    }

    try {
      val bi = try {
        val image = imageReader.read(frameNumber - 1, param)
        scaleImage(image, imageHeight)
      } catch {
        case e: NotFoundException => throw e
        case e: Exception => throw new IllegalArgumentException(e)
      }
      val baos = new ByteArrayOutputStream
      ImageIO.write(bi, "png", baos)
      baos.close()
      baos.toByteArray
    } finally {
      iis.close()
    }
  }

  private[streams] def reverseAnonymizationKeysForPatient(implicit ec: ExecutionContext): (PatientName, PatientID) => Future[Seq[AnonymizationKey]] =
    (patientName: PatientName, patientID: PatientID) => callAnonymizationService[AnonymizationKeys](GetReverseAnonymizationKeysForPatient(patientName.value, patientID.value))
      .map(_.anonymizationKeys)

  private[streams] def getOrCreateAnonKey(tagValues: Seq[TagValue]): DicomInfoPart => Future[AnonymizationKey] =
    (info: DicomInfoPart) => callAnonymizationService[AnonymizationKey](GetOrCreateAnonymizationKey(
      info.patientName, info.patientID, info.patientSex, info.patientBirthDate, info.patientAge, info.studyInstanceUID,
      info.studyDescription, info.studyID, info.accessionNumber, info.seriesInstanceUID, info.seriesDescription,
      info.protocolName, info.frameOfReferenceUID, tagValues))

  private[streams] def scaleImage(image: BufferedImage, imageHeight: Int): BufferedImage = {
    val ratio = imageHeight / image.getHeight.asInstanceOf[Double]
    if (ratio != 0.0 && ratio != 1.0) {
      val imageWidth = (image.getWidth * ratio).asInstanceOf[Int]
      val resized = new BufferedImage(imageWidth, imageHeight, image.getType)
      val g = resized.createGraphics()
      g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
      g.drawImage(image, 0, 0, imageWidth, imageHeight, 0, 0, image.getWidth, image.getHeight, null)
      g.dispose()
      resized
    } else {
      image
    }

  }

  private[streams] def storeDicomData(elements: Elements, source: Source, tempPath: String, storage: StorageService)
                                     (implicit ec: ExecutionContext): Future[MetaDataAdded] =
    callMetaDataService[MetaDataAdded](AddMetaData(elements, source)).map { metaDataAdded =>
      storage.move(tempPath, storage.imageName(metaDataAdded.image.id))
      metaDataAdded
    }

  private[streams] def maybeDeflateFlow: Flow[DicomPart, DicomPart, NotUsed] = conditionalFlow(
    {
      case p: DicomInfoPart => p.transferSyntaxUid.isDefined && DicomParsing.isDeflated(p.transferSyntaxUid.get)
    }, deflateDatasetFlow, Flow.fromFunction(identity), routeADefault = false)

  private[streams] def createTempPath() = s"tmp-${java.util.UUID.randomUUID().toString}"

  private[streams] def reverseAnonymizationKeyPartForPatient(query: (PatientName, PatientID) => Future[Seq[AnonymizationKey]])
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

  private[streams] def dicomDataSink(storageSink: Sink[ByteString, Future[Done]],
                                     parseFlow: ParseFlow,
                                     reverseAnonymizationKeysForPatient: (PatientName, PatientID) => Future[Seq[AnonymizationKey]],
                                     contexts: Seq[Context],
                                     reverseAnonymization: Boolean,
                                     tagFilterFlow: Flow[DicomPart, DicomPart, NotUsed])
                                    (implicit ec: ExecutionContext): Sink[ByteString, Future[Elements]] = {

    val validationContexts = Contexts.asNamePairs(contexts).map(ValidationContext.tupled)

    def runBothKeepRight[A, B] = (futureLeft: Future[A], futureRight: Future[B]) => futureLeft.flatMap(_ => futureRight)

    Sink.fromGraph(GraphDSL.create(storageSink, elementSink)(runBothKeepRight) { implicit builder =>
      (storageSink, elementSink) =>
        import GraphDSL.Implicits._

        val baseFlow = validateFlowWithContext(validationContexts, drainIncoming = true)
          .via(parseFlow)
          .via(collectFlow(basicInfoTags, "basictags"))
          .via(tagFilterFlow)
          .map(attributesToInfoPart(_, "basictags")) // needed for e.g. maybe deflate flow

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
        bcast.out(1) ~> whitelistFilter(tagsToStoreInDB) ~> elementFlow ~> elementSink

        SinkShape(flow.in)
    })
  }

  private[streams] def toTagModifications(tagValues: Seq[TagValue]): Seq[TagModification] =
    tagValues.map(tv => TagModification.endsWith(TagPath.fromTag(tv.tag), _ => padToEvenLength(ByteString(tv.value), tv.tag), insert = true))

  private[streams] def anonymizedDicomDataSource(storageSource: StreamSource[DicomPart, NotUsed],
                                                 getOrCreateAnonKey: DicomInfoPart => Future[AnonymizationKey],
                                                 tagValues: Seq[TagValue])(implicit ec: ExecutionContext): StreamSource[ByteString, NotUsed] =
    storageSource // DicomPart...
      .via(collectFlow(extendedInfoTags, "extendedtags")) // DicomAttributes :: DicomPart...
      .map(attributesToInfoPart(_, "extendedtags")) // DicomInfoPart :: DicomPart...
      .mapAsync(1)(getOrCreateAnonKeyPart(getOrCreateAnonKey)) // PartialAnonymizationKeyPart :: DicomPart...
      .via(maybeAnonFlow) // PartialAnonymizationKeyPart needed here to determine if data needs anonymization
      .via(maybeHarmonizeAnonFlow) // PartialAnonymizationKeyPart needed here to determine if data needs harmonizing
      .via(modifyFlow(toTagModifications(tagValues): _*))
      .via(fmiGroupLengthFlow) // update meta information group length
      .via(maybeDeflateFlow)
      .map(_.bytes)

  private[streams] def inflatedSource(source: StreamSource[DicomPart, NotUsed]): StreamSource[ByteString, _] = source
    .via(modifyFlow(
      TagModification.contains(TagPath.fromTag(Tag.TransferSyntaxUID), valueBytes => {
        valueBytes.utf8String.trim match {
          case UID.DeflatedExplicitVRLittleEndian => padToEvenLength(ByteString(UID.ExplicitVRLittleEndian), VR.UI)
          case _ => valueBytes
        }
      }, insert = false)))
    .via(fmiGroupLengthFlow)
    .map(_.bytes)

  private def tagFilterSpecToFlow(tagFilterSpec: TagFilterSpec): Flow[DicomPart, DicomPart, NotUsed] =
    tagFilterSpec match {
      case TagFilterSpec(_, _, WHITELIST, tags) =>
        whitelistFilter(tags.toSet)
      case TagFilterSpec(_, _, BLACKLIST, tags) =>
        blacklistFilter(tags.toSet)
      case _ => //Should not happen
        NO_ACTION_FILTER
    }

  val NO_ACTION_FILTER = blacklistFilter(Set())
}