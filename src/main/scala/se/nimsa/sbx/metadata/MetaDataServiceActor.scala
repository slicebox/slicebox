/*
 * Copyright 2016 Lars Edenbrandt
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

import MetaDataProtocol._
import akka.actor.Actor
import akka.actor.Props
import akka.event.Logging
import akka.event.LoggingReceive
import se.nimsa.sbx.app.DbProps
import se.nimsa.sbx.app.GeneralProtocol.Source
import se.nimsa.sbx.dicom.DicomHierarchy._
import se.nimsa.sbx.lang.NotFoundException
import se.nimsa.sbx.log.SbxLog
import se.nimsa.sbx.util.ExceptionCatching

class MetaDataServiceActor(dbProps: DbProps) extends Actor with ExceptionCatching {

  import context.system

  val log = Logging(context.system, this)

  val db = dbProps.db
  val dao = new MetaDataDAO(dbProps.driver)
  val propertiesDao = new PropertiesDAO(dbProps.driver)

  log.info("Meta data service started")

  def receive = LoggingReceive {

    case AddMetaData(patient, study, series, image, source) =>
      catchAndReport {
        val metaDataAdded = MetaDataAdded.tupled(addMetaData(patient, study, series, image, source))
        log.debug(s"Added metadata $metaDataAdded")
        sender ! metaDataAdded
      }

    case DeleteMetaData(imageId) =>
      catchAndReport {
        val (deletedPatient, deletedStudy, deletedSeries, deletedImage) =
          db.withSession { implicit session =>
            dao.imageById(imageId).map(propertiesDao.deleteFully).getOrElse((None, None, None, None))
          }

        deletedPatient.foreach(patient => system.eventStream.publish(PatientDeleted(patient.id)))
        deletedStudy.foreach(study => system.eventStream.publish(StudyDeleted(study.id)))
        deletedSeries.foreach(series => system.eventStream.publish(SeriesDeleted(series.id)))
        deletedImage.foreach(image => system.eventStream.publish(ImageDeleted(image.id)))

        sender ! MetaDataDeleted(deletedPatient, deletedStudy, deletedSeries, deletedImage)
      }

    case msg: PropertiesRequest => catchAndReport {
      msg match {

        case GetSeriesTags =>
          sender ! SeriesTags(getSeriesTags)

        case GetSourceForSeries(seriesId) =>
          db.withSession { implicit session =>
            sender ! propertiesDao.seriesSourceById(seriesId)
          }

        case GetSeriesTagsForSeries(seriesId) =>
          val seriesTags = getSeriesTagsForSeries(seriesId)
          sender ! SeriesTags(seriesTags)

        case AddSeriesTagToSeries(seriesTag, seriesId) =>
          db.withSession { implicit session =>
            dao.seriesById(seriesId).getOrElse {
              throw new NotFoundException("Series not found")
            }
            val dbSeriesTag = propertiesDao.addAndInsertSeriesTagForSeriesId(seriesTag, seriesId)
            sender ! SeriesTagAddedToSeries(dbSeriesTag)
          }

        case RemoveSeriesTagFromSeries(seriesTagId, seriesId) =>
          db.withSession { implicit session =>
            propertiesDao.removeAndCleanupSeriesTagForSeriesId(seriesTagId, seriesId)
            sender ! SeriesTagRemovedFromSeries(seriesId)
          }
      }
    }

    case msg: MetaDataQuery => catchAndReport {
      msg match {
        case GetPatients(startIndex, count, orderBy, orderAscending, filter, sourceIds, seriesTypeIds, seriesTagIds) =>
          db.withSession { implicit session =>
            sender ! Patients(propertiesDao.patients(startIndex, count, orderBy, orderAscending, filter, sourceIds, seriesTypeIds, seriesTagIds))
          }

        case GetStudies(startIndex, count, patientId, sourceRefs, seriesTypeIds, seriesTagIds) =>
          db.withSession { implicit session =>
            sender ! Studies(propertiesDao.studiesForPatient(startIndex, count, patientId, sourceRefs, seriesTypeIds, seriesTagIds))
          }

        case GetSeries(startIndex, count, studyId, sourceRefs, seriesTypeIds, seriesTagIds) =>
          db.withSession { implicit session =>
            sender ! SeriesCollection(propertiesDao.seriesForStudy(startIndex, count, studyId, sourceRefs, seriesTypeIds, seriesTagIds))
          }

        case GetImages(startIndex, count, seriesId) =>
          db.withSession { implicit session =>
            sender ! Images(dao.imagesForSeries(startIndex, count, seriesId))
          }

        case GetFlatSeries(startIndex, count, orderBy, orderAscending, filter, sourceRefs, seriesTypeIds, seriesTagIds) =>
          db.withSession { implicit session =>
            sender ! FlatSeriesCollection(propertiesDao.flatSeries(startIndex, count, orderBy, orderAscending, filter, sourceRefs, seriesTypeIds, seriesTagIds))
          }

        case GetPatient(patientId) =>
          db.withSession { implicit session =>
            sender ! dao.patientById(patientId)
          }

        case GetStudy(studyId) =>
          db.withSession { implicit session =>
            sender ! dao.studyById(studyId)
          }

        case GetSingleSeries(seriesId) =>
          db.withSession { implicit session =>
            sender ! dao.seriesById(seriesId)
          }

        case GetAllSeries =>
          db.withSession { implicit session =>
            sender ! SeriesCollection(dao.series)
          }

        case GetImage(imageId) =>
          db.withSession { implicit session =>
            sender ! dao.imageById(imageId)
          }

        case GetSingleFlatSeries(seriesId) =>
          db.withSession { implicit session =>
            sender ! dao.flatSeriesById(seriesId)
          }

        case QueryPatients(query) =>
          db.withSession { implicit session =>
            sender ! Patients(propertiesDao.queryPatients(query.startIndex, query.count, query.order, query.queryProperties, query.filters))
          }

        case QueryStudies(query) =>
          db.withSession { implicit session =>
            sender ! Studies(propertiesDao.queryStudies(query.startIndex, query.count, query.order, query.queryProperties, query.filters))
          }

        case QuerySeries(query) =>
          db.withSession { implicit session =>
            sender ! SeriesCollection(propertiesDao.querySeries(query.startIndex, query.count, query.order, query.queryProperties, query.filters))
          }

        case QueryImages(query) =>
          db.withSession { implicit session =>
            sender ! Images(propertiesDao.queryImages(query.startIndex, query.count, query.order, query.queryProperties, query.filters))
          }

        case QueryFlatSeries(query) =>
          db.withSession { implicit session =>
            sender ! FlatSeriesCollection(propertiesDao.queryFlatSeries(query.startIndex, query.count, query.order, query.queryProperties, query.filters))
          }

        case GetImagesForStudy(studyId, sourceRefs, seriesTypeIds, seriesTagIds) =>
          db.withSession { implicit session =>
            sender ! Images(propertiesDao.seriesForStudy(0, 100000000, studyId, sourceRefs, seriesTypeIds, seriesTagIds)
              .flatMap(series => dao.imagesForSeries(0, 100000000, series.id)))
          }

        case GetImagesForPatient(patientId, sourceRefs, seriesTypeIds, seriesTagIds) =>
          db.withSession { implicit session =>
            sender ! Images(propertiesDao.studiesForPatient(0, 100000000, patientId, sourceRefs, seriesTypeIds, seriesTagIds)
              .flatMap(study => propertiesDao.seriesForStudy(0, 100000000, study.id, sourceRefs, seriesTypeIds, seriesTagIds)
                .flatMap(series => dao.imagesForSeries(0, 100000000, series.id))))
          }

      }
    }

  }

  def getSeriesTags =
    db.withSession { implicit session =>
      propertiesDao.listSeriesTags
    }

  def getSeriesTagsForSeries(seriesId: Long) =
    db.withSession { implicit session =>
      propertiesDao.seriesTagsForSeries(seriesId)
    }

  def addMetaData(patient: Patient, study: Study, series: Series, image: Image, source: Source): (Patient, Study, Series, Image, SeriesSource) = {
    var patientAdded = false
    var studyAdded = false
    var seriesAdded = false
    var imageAdded = false

    val (dbPatient, dbStudy, dbSeries, dbImage, dbSeriesSource) =
      db.withTransaction { implicit session =>

        val seriesSource = SeriesSource(-1, source)

        val dbPatient = dao.patientByNameAndID(patient).getOrElse {
        	patientAdded = true
          dao.insert(patient)
        }
        val dbStudy = dao.studyByUidAndPatient(study, dbPatient).getOrElse {
        	studyAdded = true
          dao.insert(study.copy(patientId = dbPatient.id))
        }
        val dbSeries = dao.seriesByUidAndStudy(series, dbStudy).getOrElse {
        	seriesAdded = true
          dao.insert(series.copy(studyId = dbStudy.id))
        }
        val dbImage = dao.imageByUidAndSeries(image, dbSeries).getOrElse {
        	imageAdded = true
          dao.insert(image.copy(seriesId = dbSeries.id))
        }
        val dbSeriesSource = propertiesDao.seriesSourceById(dbSeries.id)
          .getOrElse(propertiesDao.insertSeriesSource(seriesSource.copy(id = dbSeries.id)))

        if (dbSeriesSource.source.sourceType != source.sourceType || dbSeriesSource.source.sourceId != source.sourceId)
          SbxLog.warn("Storage", s"Existing series source does not match source of added image (${dbSeriesSource.source} vs $source). Source of added image will be lost.")

        (dbPatient, dbStudy, dbSeries, dbImage, dbSeriesSource)
      }

    if (patientAdded) context.system.eventStream.publish(PatientAdded(dbPatient, source))
    if (studyAdded) context.system.eventStream.publish(StudyAdded(dbStudy, source))
    if (seriesAdded) context.system.eventStream.publish(SeriesAdded(dbSeries, source))
    if (imageAdded) context.system.eventStream.publish(ImageAdded(dbImage, source))

    (dbPatient, dbStudy, dbSeries, dbImage, dbSeriesSource)
  }
}

object MetaDataServiceActor {
  def props(dbProps: DbProps): Props = Props(new MetaDataServiceActor(dbProps))
}
