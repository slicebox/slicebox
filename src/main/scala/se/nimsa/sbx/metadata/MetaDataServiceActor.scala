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

package se.nimsa.sbx.metadata

import akka.actor.{Actor, Props, Stash}
import akka.event.{Logging, LoggingReceive}
import akka.pattern.pipe
import akka.util.Timeout
import se.nimsa.sbx.dicom.DicomUtil._
import se.nimsa.sbx.lang.NotFoundException
import se.nimsa.sbx.metadata.MetaDataProtocol._
import se.nimsa.sbx.util.SequentialPipeToSupport

import scala.concurrent.Future

class MetaDataServiceActor(metaDataDao: MetaDataDAO, propertiesDao: PropertiesDAO) extends Actor with Stash with SequentialPipeToSupport {

  import context.system

  implicit val executor = system.dispatcher

  val log = Logging(context.system, this)

  log.info("Meta data service started")

  def receive = LoggingReceive {

    case AddMetaData(attributes, source) =>
      val addFuture = propertiesDao.addMetaData(
        attributesToPatient(attributes),
        attributesToStudy(attributes),
        attributesToSeries(attributes),
        attributesToImage(attributes),
        source)
      addFuture.map(metaData => s"Added metadata $metaData").foreach(log.debug)
      addFuture.foreach(context.system.eventStream.publish)
      addFuture.pipeSequentiallyTo(sender)

    case DeleteMetaData(image) =>
      val deleteFuture = propertiesDao.deleteFully(image).map(MetaDataDeleted.tupled)
      deleteFuture.foreach(system.eventStream.publish)
      deleteFuture.pipeSequentiallyTo(sender)

    case msg: PropertiesRequest =>
      msg match {

        case GetSeriesTags =>
          pipe(propertiesDao.listSeriesTags.map(SeriesTags)).to(sender)

        case GetSourceForSeries(seriesId) =>
          pipe(propertiesDao.seriesSourceById(seriesId)).to(sender)

        case GetSeriesTagsForSeries(seriesId) =>
          pipe(propertiesDao.seriesTagsForSeries(seriesId).map(SeriesTags)).to(sender)

        case AddSeriesTagToSeries(seriesTag, seriesId) =>
          propertiesDao.addSeriesTagToSeries(seriesTag, seriesId).map(_.getOrElse {
            throw new NotFoundException("Series not found")
          }).map(SeriesTagAddedToSeries)
            .pipeSequentiallyTo(sender)

        case RemoveSeriesTagFromSeries(seriesTagId, seriesId) =>
          propertiesDao.removeAndCleanupSeriesTagForSeriesId(seriesTagId, seriesId)
            .map(_ => SeriesTagRemovedFromSeries(seriesId))
            .pipeSequentiallyTo(sender)
      }

    case msg: MetaDataQuery =>
      msg match {
        case GetPatients(startIndex, count, orderBy, orderAscending, filter, sourceIds, seriesTypeIds, seriesTagIds) =>
          pipe(propertiesDao.patients(startIndex, count, orderBy, orderAscending, filter, sourceIds, seriesTypeIds, seriesTagIds).map(Patients)).to(sender)

        case GetStudies(startIndex, count, patientId, sourceRefs, seriesTypeIds, seriesTagIds) =>
          pipe(propertiesDao.studiesForPatient(startIndex, count, patientId, sourceRefs, seriesTypeIds, seriesTagIds).map(Studies)).to(sender)

        case GetSeries(startIndex, count, studyId, sourceRefs, seriesTypeIds, seriesTagIds) =>
          pipe(propertiesDao.seriesForStudy(startIndex, count, studyId, sourceRefs, seriesTypeIds, seriesTagIds).map(SeriesCollection)).to(sender)

        case GetImages(startIndex, count, seriesId) =>
          pipe(metaDataDao.imagesForSeries(startIndex, count, seriesId).map(Images)).to(sender)

        case GetFlatSeries(startIndex, count, orderBy, orderAscending, filter, sourceRefs, seriesTypeIds, seriesTagIds) =>
          pipe(propertiesDao.flatSeries(startIndex, count, orderBy, orderAscending, filter, sourceRefs, seriesTypeIds, seriesTagIds).map(FlatSeriesCollection)).to(sender)

        case GetPatient(patientId) =>
          pipe(metaDataDao.patientById(patientId)).to(sender).to(sender)

        case GetStudy(studyId) =>
          pipe(metaDataDao.studyById(studyId)).to(sender)

        case GetSingleSeries(seriesId) =>
          pipe(metaDataDao.seriesById(seriesId)).to(sender)

        case GetAllSeries =>
          pipe(metaDataDao.series.map(SeriesCollection)).to(sender)

        case GetImage(imageId) =>
          pipe(metaDataDao.imageById(imageId)).to(sender)

        case GetSingleFlatSeries(seriesId) =>
          pipe(metaDataDao.flatSeriesById(seriesId)).to(sender)

        case QueryPatients(query) =>
          pipe(propertiesDao.queryPatients(query.startIndex, query.count, query.order, query.queryProperties, query.filters).map(Patients)).to(sender)

        case QueryStudies(query) =>
          pipe(propertiesDao.queryStudies(query.startIndex, query.count, query.order, query.queryProperties, query.filters).map(Studies)).to(sender)

        case QuerySeries(query) =>
          pipe(propertiesDao.querySeries(query.startIndex, query.count, query.order, query.queryProperties, query.filters).map(SeriesCollection)).to(sender)

        case QueryImages(query) =>
          pipe(propertiesDao.queryImages(query.startIndex, query.count, query.order, query.queryProperties, query.filters).map(Images)).to(sender)

        case QueryFlatSeries(query) =>
          pipe(propertiesDao.queryFlatSeries(query.startIndex, query.count, query.order, query.queryProperties, query.filters).map(FlatSeriesCollection)).to(sender)

        case GetImagesForStudy(studyId, sourceRefs, seriesTypeIds, seriesTagIds) =>
          val imagesFuture = propertiesDao.seriesForStudy(0, 100000000, studyId, sourceRefs, seriesTypeIds, seriesTagIds)
            .flatMap(series => Future.sequence(series.map(s => metaDataDao.imagesForSeries(0, 100000000, s.id))))
            .map(_.flatten)
          pipe(imagesFuture.map(Images)).to(sender)

        case GetImagesForPatient(patientId, sourceRefs, seriesTypeIds, seriesTagIds) =>
          val imagesFuture = propertiesDao.studiesForPatient(0, 100000000, patientId, sourceRefs, seriesTypeIds, seriesTagIds)
            .flatMap(studies => Future.sequence(studies.map(study => propertiesDao.seriesForStudy(0, 100000000, study.id, sourceRefs, seriesTypeIds, seriesTagIds)
              .flatMap(series => Future.sequence(series.map(s => metaDataDao.imagesForSeries(0, 100000000, s.id)))
                .map(_.flatten))))
              .map(_.flatten))
          pipe(imagesFuture.map(Images)).to(sender)
      }

  }

}

object MetaDataServiceActor {
  def props(metaDataDao: MetaDataDAO, propertiesDao: PropertiesDAO): Props = Props(new MetaDataServiceActor(metaDataDao, propertiesDao))
}
