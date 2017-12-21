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
import javax.imageio.ImageIO

import akka.actor.Cancellable
import akka.stream.scaladsl.{Broadcast, Flow, GraphDSL, Sink, StreamConverters, Source => StreamSource}
import akka.stream.{Materializer, SinkShape}
import akka.util.ByteString
import akka.{Done, NotUsed}
import org.dcm4che3.data._
import org.dcm4che3.imageio.plugins.dcm.DicomImageReadParam
import org.dcm4che3.io.DicomStreamException
import se.nimsa.dcm4che.streams.DicomFlows._
import se.nimsa.dcm4che.streams.DicomModifyFlow._
import se.nimsa.dcm4che.streams.DicomParts._
import se.nimsa.dcm4che.streams.TagPath.TagPathSequence
import se.nimsa.dcm4che.streams._
import se.nimsa.sbx.anonymization.AnonymizationProtocol._
import se.nimsa.sbx.app.GeneralProtocol.{Source, SourceType}
import se.nimsa.sbx.dicom.Contexts.Context
import se.nimsa.sbx.dicom.DicomHierarchy.Image
import se.nimsa.sbx.dicom.DicomPropertyValue.{PatientID, PatientName}
import se.nimsa.sbx.dicom.{Contexts, DicomUtil, ImageAttribute}
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
  import HarmonizeAnonymizationFlow._
  import ReverseAnonymizationFlow._
  import DicomStreamUtil._

  def callAnonymizationService[R: ClassTag](message: Any): Future[R]
  def callMetaDataService[R: ClassTag](message: Any): Future[R]
  def scheduleTask(delay: FiniteDuration)(task: => Unit): Cancellable

  /**
    * Creates a streaming source of anonymized and harmonized DICOM data
    *
    * @param imageId   ID of image to load
    * @param tagValues forced values of attributes as pairs of tag number and string value encoded in UTF-8 (ASCII) format
    * @return a `Source` of anonymized DICOM byte chunks
    */
  protected def anonymizedDicomData(imageId: Long, tagValues: Seq[TagValue], storage: StorageService)
                         (implicit materializer: Materializer, ec: ExecutionContext): StreamSource[ByteString, NotUsed] = {
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
    val sink = dicomDataSink(storage.fileSink(tempPath), storage.parseFlow(None), reverseAnonymizationKeysForPatient, contexts, reverseAnonymization)
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
    val sink = dicomDataSink(storage.fileSink(tempPath), storage.parseFlow(None), reverseAnonymizationKeysForPatient, Contexts.extendedContexts, reverseAnonymization = false)

    val futureModifiedTempFile =
      storage
        .dataSource(imageId, None)
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

  protected def readImageAttributes(imageId: Long, storage: StorageService)(implicit materializer: Materializer, ec: ExecutionContext): StreamSource[ImageAttribute, NotUsed] =
    storage
      .dataSource(imageId, Some(Tag.PixelData))
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

  protected def readImageInformation(imageId: Long, storage: StorageService)(implicit materializer: Materializer, ec: ExecutionContext): Future[ImageInformation] =
    storage
      .dataSource(imageId, Some(imageInformationTags.max + 1))
      .via(DicomFlows.whitelistFilter(imageInformationTags))
      .via(DicomFlows.attributeFlow)
      .runWith(DicomAttributesSink.attributesSink)
      .map {
        case (_, maybeAttributes) =>
          maybeAttributes.map { attributes =>
            val instanceNumber = attributes.getInt(Tag.InstanceNumber, 1)
            val imageIndex = attributes.getInt(Tag.ImageIndex, 1)
            val frameIndex = if (instanceNumber > imageIndex) instanceNumber else imageIndex
            ImageInformation(
              attributes.getInt(Tag.NumberOfFrames, 1),
              frameIndex,
              attributes.getInt(Tag.SmallestImagePixelValue, 0),
              attributes.getInt(Tag.LargestImagePixelValue, 0))
          }.getOrElse(ImageInformation(0, 0, 0, 0))
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

  private[streams] def storeDicomData(maybeDataset: Option[Attributes], source: Source, tempPath: String, storage: StorageService)
                                     (implicit ec: ExecutionContext): Future[MetaDataAdded] = {
    val attributes: Attributes = maybeDataset.getOrElse(throw new DicomStreamException("DICOM data has no dataset"))
    callMetaDataService[MetaDataAdded](AddMetaData(attributes, source)).map { metaDataAdded =>
      storage.move(tempPath, storage.imageName(metaDataAdded.image.id))
      metaDataAdded
    }
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

  private[streams] def dicomDataSink(storageSink: Sink[ByteString, Future[Done]], parseFlow: DicomParseFlow, reverseAnonymizationKeysForPatient: (PatientName, PatientID) => Future[Seq[AnonymizationKey]], contexts: Seq[Context], reverseAnonymization: Boolean)
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

  private[streams] def getOrCreateAnonKeyPart(getOrCreateAnonKey: DicomInfoPart => Future[AnonymizationKey])
                            (implicit ec: ExecutionContext): DicomPart => Future[DicomPart] = {
    case info: DicomInfoPart if info.isAnonymized =>
      Future.successful(PartialAnonymizationKeyPart(None, hasPatientInfo = false, hasStudyInfo = false, hasSeriesInfo = false))
    case info: DicomInfoPart =>
      getOrCreateAnonKey(info).map(key => PartialAnonymizationKeyPart(Some(key), hasPatientInfo = true, hasStudyInfo = true, hasSeriesInfo = true))
    case part: DicomPart => Future.successful(part)
  }

  private[streams] def toTagModifications(tagValues: Seq[TagValue]): Seq[TagModification] =
    tagValues.map(tv => TagModification.endsWith(TagPath.fromTag(tv.tag), _ => padToEvenLength(ByteString(tv.value), tv.tag), insert = true))

  private[streams] def anonymizedDicomDataSource(storageSource: StreamSource[DicomPart, NotUsed],
                                getOrCreateAnonKey: DicomInfoPart => Future[AnonymizationKey],
                                tagValues: Seq[TagValue])
                               (implicit ec: ExecutionContext, materializer: Materializer): StreamSource[ByteString, NotUsed] =
    storageSource // DicomPart...
      .via(collectAttributesFlow(extendedInfoTags)) // DicomAttributes :: DicomPart...
      .mapAsync(1)(attributesToInfoPart) // DicomInfoPart :: DicomPart...
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

}