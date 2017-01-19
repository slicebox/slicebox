/*
 * Copyright 2017 Lars Edenbrandt
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

import akka.actor.{Actor, Props}
import akka.event.{Logging, LoggingReceive}
import akka.util.Timeout
import se.nimsa.sbx.dicom.DicomUtil._
import se.nimsa.sbx.lang.NotFoundException
import se.nimsa.sbx.metadata.MetaDataProtocol._
import se.nimsa.sbx.util.ExceptionCatching
import se.nimsa.sbx.util.FutureUtil.await

class MetaDataServiceActor(metaDataDao: MetaDataDAO, propertiesDao: PropertiesDAO)(implicit timeout: Timeout) extends Actor with ExceptionCatching {

  import context.system

  val log = Logging(context.system, this)

  log.info("Meta data service started")

  def receive = LoggingReceive {

    case AddMetaData(attributes, source) =>
      catchAndReport {
        val metaData = await(propertiesDao.addMetaData(
          attributesToPatient(attributes),
          attributesToStudy(attributes),
          attributesToSeries(attributes),
          attributesToImage(attributes),
          source))
        log.debug(s"Added metadata $metaData")
        context.system.eventStream.publish(metaData)
        sender ! metaData
      }

    case DeleteMetaData(imageId) =>
      catchAndReport {
        val (deletedPatient, deletedStudy, deletedSeries, deletedImage) =
          await(metaDataDao.imageById(imageId)).map(image => await(propertiesDao.deleteFully(image))).getOrElse((None, None, None, None))

        val metaDataDeleted = MetaDataDeleted(deletedPatient, deletedStudy, deletedSeries, deletedImage)
        system.eventStream.publish(metaDataDeleted)

        sender ! metaDataDeleted
      }

    case msg: PropertiesRequest => catchAndReport {
      msg match {

        case GetSeriesTags =>
          sender ! SeriesTags(getSeriesTags)

        case GetSourceForSeries(seriesId) =>
          sender ! await(propertiesDao.seriesSourceById(seriesId))

        case GetSeriesTagsForSeries(seriesId) =>
          val seriesTags = getSeriesTagsForSeries(seriesId)
          sender ! SeriesTags(seriesTags)

        case AddSeriesTagToSeries(seriesTag, seriesId) =>
          await(metaDataDao.seriesById(seriesId)).getOrElse {
            throw new NotFoundException("Series not found")
          }
          val dbSeriesTag = await(propertiesDao.addAndInsertSeriesTagForSeriesId(seriesTag, seriesId))
          sender ! SeriesTagAddedToSeries(dbSeriesTag)

        case RemoveSeriesTagFromSeries(seriesTagId, seriesId) =>
          await(propertiesDao.removeAndCleanupSeriesTagForSeriesId(seriesTagId, seriesId))
          sender ! SeriesTagRemovedFromSeries(seriesId)
      }
    }

    case msg: MetaDataQuery => catchAndReport {
      msg match {
        case GetPatients(startIndex, count, orderBy, orderAscending, filter, sourceIds, seriesTypeIds, seriesTagIds) =>
          sender ! Patients(await(propertiesDao.patients(startIndex, count, orderBy, orderAscending, filter, sourceIds, seriesTypeIds, seriesTagIds)))

        case GetStudies(startIndex, count, patientId, sourceRefs, seriesTypeIds, seriesTagIds) =>
          sender ! Studies(await(propertiesDao.studiesForPatient(startIndex, count, patientId, sourceRefs, seriesTypeIds, seriesTagIds)))

        case GetSeries(startIndex, count, studyId, sourceRefs, seriesTypeIds, seriesTagIds) =>
          sender ! SeriesCollection(await(propertiesDao.seriesForStudy(startIndex, count, studyId, sourceRefs, seriesTypeIds, seriesTagIds)))

        case GetImages(startIndex, count, seriesId) =>
          sender ! Images(await(metaDataDao.imagesForSeries(startIndex, count, seriesId)))

        case GetFlatSeries(startIndex, count, orderBy, orderAscending, filter, sourceRefs, seriesTypeIds, seriesTagIds) =>
          sender ! FlatSeriesCollection(await(propertiesDao.flatSeries(startIndex, count, orderBy, orderAscending, filter, sourceRefs, seriesTypeIds, seriesTagIds)))

        case GetPatient(patientId) =>
          sender ! await(metaDataDao.patientById(patientId))

        case GetStudy(studyId) =>
          sender ! await(metaDataDao.studyById(studyId))

        case GetSingleSeries(seriesId) =>
          sender ! await(metaDataDao.seriesById(seriesId))

        case GetAllSeries =>
          sender ! SeriesCollection(await(metaDataDao.series))

        case GetImage(imageId) =>
          sender ! await(metaDataDao.imageById(imageId))

        case GetSingleFlatSeries(seriesId) =>
          sender ! await(metaDataDao.flatSeriesById(seriesId))

        case QueryPatients(query) =>
          sender ! Patients(await(propertiesDao.queryPatients(query.startIndex, query.count, query.order, query.queryProperties, query.filters)))

        case QueryStudies(query) =>
          sender ! Studies(await(propertiesDao.queryStudies(query.startIndex, query.count, query.order, query.queryProperties, query.filters)))

        case QuerySeries(query) =>
          sender ! SeriesCollection(await(propertiesDao.querySeries(query.startIndex, query.count, query.order, query.queryProperties, query.filters)))

        case QueryImages(query) =>
          sender ! Images(await(propertiesDao.queryImages(query.startIndex, query.count, query.order, query.queryProperties, query.filters)))

        case QueryFlatSeries(query) =>
          sender ! FlatSeriesCollection(await(propertiesDao.queryFlatSeries(query.startIndex, query.count, query.order, query.queryProperties, query.filters)))

        case GetImagesForStudy(studyId, sourceRefs, seriesTypeIds, seriesTagIds) =>
          sender ! Images(await(propertiesDao.seriesForStudy(0, 100000000, studyId, sourceRefs, seriesTypeIds, seriesTagIds))
            .flatMap(series => await(metaDataDao.imagesForSeries(0, 100000000, series.id))))

        case GetImagesForPatient(patientId, sourceRefs, seriesTypeIds, seriesTagIds) =>
          sender ! Images(await(propertiesDao.studiesForPatient(0, 100000000, patientId, sourceRefs, seriesTypeIds, seriesTagIds))
            .flatMap(study => await(propertiesDao.seriesForStudy(0, 100000000, study.id, sourceRefs, seriesTypeIds, seriesTagIds))
              .flatMap(series => await(metaDataDao.imagesForSeries(0, 100000000, series.id)))))

      }
    }

  }

  def getSeriesTags =
    await(propertiesDao.listSeriesTags)

  def getSeriesTagsForSeries(seriesId: Long) =
    await(propertiesDao.seriesTagsForSeries(seriesId))

}

object MetaDataServiceActor {
  def props(metaDataDao: MetaDataDAO, propertiesDao: PropertiesDAO, timeout: Timeout): Props = Props(new MetaDataServiceActor(metaDataDao, propertiesDao)(timeout))
}
