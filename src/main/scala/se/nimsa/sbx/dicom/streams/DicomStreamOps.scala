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
import se.nimsa.dicom.data.{PatientName => _, _}
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
import se.nimsa.sbx.dicom.SliceboxTags._
import se.nimsa.sbx.dicom.{Contexts, ImageAttribute}
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
  def scheduleTask(delay: FiniteDuration)(task: => Unit): Cancellable

  /**
    * Creates a streaming source of anonymized and harmonized DICOM data
    *
    * @param imageId          ID of image to load
    * @param customAnonValues forced values of attributes as pairs of tag number and string value encoded in UTF-8 (ASCII) format
    * @return a `Source` of anonymized DICOM byte chunks
    */
  protected def anonymizedDicomData(imageId: Long, customAnonValues: Seq[TagValue], storage: StorageService)(implicit ec: ExecutionContext): StreamSource[ByteString, NotUsed] = {
    val source = storage.dataSource(imageId, None)
    anonymizedDicomDataSource(source, anonymizationKeyInsert(imageId), customAnonValues)
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
    val sink = dicomDataSink(storage.fileSink(tempPath), storage.parseFlow(None), anonymizationKeyQuery, contexts, reverseAnonymization)
    bytesSource.runWith(sink)
      .flatMap(elements => storeDicomData(elements, source, tempPath, storage))
      .recover {
        case t: Throwable =>
          scheduleTask(30.seconds) {
            storage.deleteByName(Seq(tempPath)) // delete temp file once file system has released handle
          }
          if (!t.isInstanceOf[DicomStreamException]) throw new DicomStreamException(t.getMessage) else throw t
      }
  }

  /**
    * Retrieve data from the system, anonymize it - regardless of already anonymous or not, delete the old data, and
    * write the new data back to the system.
    *
    * @param imageId          ID of image to anonymize
    * @param customAnonValues forced values of attributes as pairs of tag number and string value encoded in UTF-8 (ASCII) format
    * @param storage          the storage backend (file, runtime, S3 etc)
    * @return the anonymized metadata stored in the system
    */
  protected def anonymizeData(imageId: Long, customAnonValues: Seq[TagValue], storage: StorageService)
                             (implicit materializer: Materializer, ec: ExecutionContext): Future[Option[MetaDataAdded]] =
    callMetaDataService[Option[Image]](GetImage(imageId)).flatMap { imageMaybe =>
      imageMaybe.map { image =>
        callMetaDataService[Option[SeriesSource]](GetSourceForSeries(image.seriesId)).map { seriesSourceMaybe =>
          seriesSourceMaybe.map { seriesSource =>
            val forcedSource = storage
              .dataSource(imageId, None)
              .via(blacklistFilter(Set(TagPath.fromTag(Tag.PatientIdentityRemoved), TagPath.fromTag(Tag.DeidentificationMethod))))
            val anonymizedSource = anonymizedDicomDataSource(forcedSource, anonymizationKeyInsert(imageId), customAnonValues)
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
    val sink = dicomDataSink(storage.fileSink(tempPath), storage.parseFlow(None), anonymizationKeyQuery, Contexts.extendedContexts, reverseAnonymization = false)

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

            val length = element.length
            val values = element.vr match {
              case VR.OW | VR.OF | VR.OB | VR.OD if length > 20 => List(s"< Binary data >")
              case _ => element.value.toStrings(element.vr, element.bigEndian, characterSets).toList
            }
            val namePath = tagPath.toList.map(_.tag).map(Dictionary.keywordOf)

            ImageAttribute(tagPath, namePath, element.vr.toString, length, values) :: Nil
          case (tagPath: TagPath, element: SequenceElement) =>
            val namePath = tagPath.toList.map(_.tag).map(Dictionary.keywordOf)
            ImageAttribute(tagPath, namePath, "SQ", element.length, Nil) :: Nil
          case (tagPath: TagPath, element: FragmentsElement) =>
            val namePath = tagPath.toList.map(_.tag).map(Dictionary.keywordOf)
            ImageAttribute(tagPath, namePath, element.vr.toString, -1, List(s"< Fragments >")) :: Nil
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

  protected def readPngImageData(imageId: Long, frameNumber: Int, windowMin: Int, windowMax: Int, imageHeight: Int, storage: StorageService)(implicit materializer: Materializer, ec: ExecutionContext): Future[Array[Byte]] = {

    def inflatedSource(source: StreamSource[DicomPart, NotUsed]): StreamSource[ByteString, _] =
      source
        .via(modifyFlow(
          TagModification.contains(TagPath.fromTag(Tag.TransferSyntaxUID), valueBytes => {
            valueBytes.utf8String.trim match {
              case UID.DeflatedExplicitVRLittleEndian => padToEvenLength(ByteString(UID.ExplicitVRLittleEndian), VR.UI)
              case _ => valueBytes
            }
          }, insert = false)))
        .via(fmiGroupLengthFlow)
        .map(_.bytes)

    def scaleImage(image: BufferedImage, imageHeight: Int): BufferedImage = {
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

    Future {
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
  }

  private[streams] val anonymizationKeyQuery: ElementsPart => Future[AnonymizationKeyOpResult] =
    p => {
      val patientName = p.elements.getString(Tag.PatientName).getOrElse("")
      val patientID = p.elements.getString(Tag.PatientID).getOrElse("")
      val studyInstanceUID = p.elements.getString(Tag.StudyInstanceUID).getOrElse("")
      val seriesInstanceUID = p.elements.getString(Tag.SeriesInstanceUID).getOrElse("")
      val sopInstanceUID = p.elements.getString(Tag.SOPInstanceUID).getOrElse("")
      callAnonymizationService[AnonymizationKeyOpResult](QueryReverseAnonymizationKeyValues(patientName, patientID, studyInstanceUID, seriesInstanceUID, sopInstanceUID))
    }

  private[streams] def anonymizationKeyQueryFlow(anonymizationKeyQuery: ElementsPart => Future[AnonymizationKeyOpResult], label: String)(implicit ec: ExecutionContext) =
    identityFlow
      .mapAsync(1) {
        case p: ElementsPart if p.label == label => anonymizationKeyQuery(p).map(key => AnonymizationKeyOpResultPart(key))
        case p: DicomPart => Future.successful(p)
      }

  private[streams] def anonymizationKeyInsert(imageId: Long): Set[AnonymizationKeyValueData] => Future[AnonymizationKeyOpResult] =
    keyValues => callAnonymizationService[AnonymizationKeyOpResult](InsertAnonymizationKeyValues(imageId, keyValues))


  private[streams] def anonymizationKeyInsertFlow(anonymizationKeyInsert: Set[AnonymizationKeyValueData] => Future[AnonymizationKeyOpResult],
                                                  customAnonValues: Seq[TagValue],
                                                  before: String, after: String)(implicit ec: ExecutionContext) = {

    // this is the old insert flow, incorporate somehow below
    case class AnonymizationKeyValueDataPart(keyValueData: Set[AnonymizationKeyValueData]) extends MetaPart

    identityFlow
      .statefulMapConcat {
        var afterElements: Option[Elements] = None

        () => {
          case p: ElementsPart if p.label == after => // collected last so will be first on stream
            afterElements = Some(p.elements)
            p :: Nil
          case p: ElementsPart if p.label == before =>
            afterElements.map { anonElements =>
              val realElements = p.elements
              val tagValues = valueTags.flatMap { tagLevel =>
                tagLevel.tagPath match {
                  case tp: TagPathTag =>
                    realElements.getSingleString(tp).map { value =>
                      val anonValue = customAnonValues
                        .find(_.tagPath == tp)
                        .map(_.value)
                        .orElse(anonElements.getSingleString(tp))
                        .getOrElse("")
                      AnonymizationKeyValueData(tagLevel.level, tp, value, anonValue)
                    }
                  case _ => None
                }
              }
              p :: AnonymizationKeyValueDataPart(tagValues) :: Nil
            }.getOrElse {
              p :: AnonymizationKeyValueDataPart(Set.empty) :: Nil
            }
          case p => p :: Nil
        }
      }
      .mapAsync(1) {
        case p: AnonymizationKeyValueDataPart =>
          anonymizationKeyInsert(p.keyValueData).map(AnonymizationKeyOpResultPart)
        case p: DicomPart => Future.successful(p)
      }
  }

  private[streams] def storeDicomData(elements: Elements, source: Source, tempPath: String, storage: StorageService)
                                     (implicit ec: ExecutionContext): Future[MetaDataAdded] =
    callMetaDataService[MetaDataAdded](AddMetaData(elements, source)).map { metaDataAdded =>
      storage.move(tempPath, storage.imageName(metaDataAdded.image.id))
      metaDataAdded
    }

  private[streams] def maybeDeflateFlow: Flow[DicomPart, DicomPart, NotUsed] =
    conditionalFlow({ case p: ElementsPart => p.elements.getString(Tag.TransferSyntaxUID).isDefined && DicomParsing.isDeflated(p.elements.getString(Tag.TransferSyntaxUID).get) },
      deflateDatasetFlow,
      identityFlow,
      routeADefault = false)

  private[streams] def createTempPath() = s"tmp-${java.util.UUID.randomUUID().toString}"

  private[streams] def dicomDataSink(storageSink: Sink[ByteString, Future[Done]],
                                     parseFlow: ParseFlow,
                                     anonymizationKeyQuery: ElementsPart => Future[AnonymizationKeyOpResult],
                                     contexts: Seq[Context],
                                     reverseAnonymization: Boolean)
                                    (implicit ec: ExecutionContext): Sink[ByteString, Future[Elements]] = {

    val validationContexts = Contexts.asNamePairs(contexts).map(ValidationContext.tupled)

    def runBothKeepRight[A, B]: (Future[A], Future[B]) => Future[B] = (left, right) => left.flatMap(_ => right)

    Sink.fromGraph(GraphDSL.create(storageSink, elementSink)(runBothKeepRight) { implicit builder =>
      (storageSink, elementSink) =>
        import GraphDSL.Implicits._

        val label = "collect-reverse"

        val baseFlow = validateFlowWithContext(validationContexts, drainIncoming = true)
          .via(parseFlow)
          .via(collectFlow(anonKeysTags ++ anonymizationTags, label))

        val flow = builder.add {
          if (reverseAnonymization)
            baseFlow
              .via(conditionalFlow({ case p: ElementsPart if p.label == label => isAnonymous(p.elements) },
                groupLengthDiscardFilter
                  .via(toIndeterminateLengthSequences)
                  .via(toUtf8Flow)
                  .via(anonymizationKeyQueryFlow(anonymizationKeyQuery, label))
                  .via(reverseAnonFlow),
                identityFlow
              ))
          else
            baseFlow
        }

        val bcast = builder.add(Broadcast[DicomPart](2))

        flow ~> bcast.in
        bcast.out(0) ~> maybeDeflateFlow.map(_.bytes) ~> storageSink
        bcast.out(1) ~> whitelistFilter(encodingTags ++ tagsToStoreInDB) ~> elementFlow ~> elementSink

        SinkShape(flow.in)
    })
  }

  private[streams] def anonymizedDicomDataSource(storageSource: StreamSource[DicomPart, NotUsed],
                                                 anonymizationKeyInsert: Set[AnonymizationKeyValueData] => Future[AnonymizationKeyOpResult],
                                                 customAnonValues: Seq[TagValue])(implicit ec: ExecutionContext): StreamSource[ByteString, NotUsed] = {

    val (before, after) = ("collect-anon-before", "collect-anon-after")
    val tags = encodingTags ++ anonymizationTags ++ anonKeysTags ++ valueTags.map(_.tagPath)

    storageSource
      .via(collectFlow(tags, before)) // collect necessary info before anonymization
      .via(conditionalFlow(
      { case p: ElementsPart if p.label == before => !isAnonymous(p.elements) },
      // data needs anonymization - this is the actual anonymization flow
      groupLengthDiscardFilter
        .via(toIndeterminateLengthSequences)
        .via(toUtf8Flow)
        .via(anonFlow)
        .via(collectFlow(tags, after)) // collect necessary info before anonymization
        .via(anonymizationKeyInsertFlow(anonymizationKeyInsert, customAnonValues, before, after))
        .via(harmonizeAnonFlow(customAnonValues))
        .via(fmiGroupLengthFlow),
      // data does not need anonymization - just pass through
      identityFlow
    ))
      .via(maybeDeflateFlow)
      .map(_.bytes)
  }
}