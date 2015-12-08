/*
 * Copyright 2015 Lars Edenbrandt
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

package se.nimsa.sbx.app.routing

import akka.pattern.ask
import spray.http.StatusCodes.NoContent
import spray.http.StatusCodes.Created
import spray.httpx.SprayJsonSupport._
import spray.routing._
import se.nimsa.sbx.app.SliceboxService
import se.nimsa.sbx.dicom.DicomHierarchy._
import se.nimsa.sbx.metadata.MetaDataProtocol._
import se.nimsa.sbx.app.GeneralProtocol._
import se.nimsa.sbx.app.GeneralProtocol.SourceType._
import se.nimsa.sbx.user.UserProtocol._
import se.nimsa.sbx.box.BoxProtocol._
import se.nimsa.sbx.scp.ScpProtocol._
import se.nimsa.sbx.directory.DirectoryWatchProtocol._
import se.nimsa.sbx.seriestype.SeriesTypeProtocol.SeriesTypes
import scala.concurrent.Future
import se.nimsa.sbx.scu.ScuProtocol.GetScus
import se.nimsa.sbx.scu.ScuProtocol.Scus
import se.nimsa.sbx.seriestype.SeriesTypeProtocol.GetSeriesTypesForSeries

trait MetadataRoutes { this: SliceboxService =>

  def metaDataRoutes: Route =
    pathPrefix("metadata") {
      path("seriestags") {
        get {
          onSuccess(metaDataService.ask(GetSeriesTags)) {
            case SeriesTags(seriesTags) =>
              complete(seriesTags)
          }
        }
      } ~ pathPrefix("patients") {
        pathEndOrSingleSlash {
          get {
            parameters(
              'startindex.as[Long] ? 0,
              'count.as[Long] ? 20,
              'orderby.as[String].?,
              'orderascending.as[Boolean] ? true,
              'filter.as[String].?,
              'sources.as[String].?,
              'seriestypes.as[String].?,
              'seriestags.as[String].?) { (startIndex, count, orderBy, orderAscending, filter, sourcesString, seriesTypesString, seriesTagsString) =>
                val sources = sourcesString.map(parseSourcesString(_)).getOrElse(Array.empty)
                val seriesTypes = seriesTypesString.map(parseIdsString(_)).getOrElse(Array.empty)
                val seriesTags = seriesTagsString.map(parseIdsString(_)).getOrElse(Array.empty)
                onSuccess(metaDataService.ask(GetPatients(startIndex, count, orderBy, orderAscending, filter, sources, seriesTypes, seriesTags))) {
                  case Patients(patients) =>
                    complete(patients)
                }
              }
          }
        } ~ path("query") {
          post {
            entity(as[Query]) { query =>
              onSuccess(metaDataService.ask(QueryPatients(query))) {
                case Patients(patients) =>
                  complete(patients)
              }
            }
          }
        } ~ pathPrefix(LongNumber) { patientId =>
          pathEndOrSingleSlash {
            get {
              onSuccess(metaDataService.ask(GetPatient(patientId)).mapTo[Option[Patient]]) {
                complete(_)
              }
            }
          } ~ path("images") {
            parameters(
              'sources.as[String].?,
              'seriestypes.as[String].?,
              'seriestags.as[String].?) { (sourcesString, seriesTypesString, seriesTagsString) =>
                val sources = sourcesString.map(parseSourcesString(_)).getOrElse(Array.empty)
                val seriesTypes = seriesTypesString.map(parseIdsString(_)).getOrElse(Array.empty)
                val seriesTags = seriesTagsString.map(parseIdsString(_)).getOrElse(Array.empty)
                onSuccess(metaDataService.ask(GetImagesForPatient(patientId, sources, seriesTypes, seriesTags))) {
                  case Images(images) =>
                    complete(images)
                }
              }
          }
        }
      } ~ pathPrefix("studies") {
        pathEndOrSingleSlash {
          get {
            parameters(
              'startindex.as[Long] ? 0,
              'count.as[Long] ? 20,
              'patientid.as[Long],
              'sources.as[String].?,
              'seriestypes.as[String].?,
              'seriestags.as[String].?) { (startIndex, count, patientId, sourcesString, seriesTypesString, seriesTagsString) =>
                val sources = sourcesString.map(parseSourcesString(_)).getOrElse(Array.empty)
                val seriesTypes = seriesTypesString.map(parseIdsString(_)).getOrElse(Array.empty)
                val seriesTags = seriesTagsString.map(parseIdsString(_)).getOrElse(Array.empty)
                onSuccess(metaDataService.ask(GetStudies(startIndex, count, patientId, sources, seriesTypes, seriesTags))) {
                  case Studies(studies) =>
                    complete(studies)
                }
              }
          }
        } ~ path("query") {
          post {
            entity(as[Query]) { query =>
              onSuccess(metaDataService.ask(QueryStudies(query))) {
                case Studies(studies) =>
                  complete(studies)
              }
            }
          }
        } ~ pathPrefix(LongNumber) { studyId =>
          pathEndOrSingleSlash {
            get {
              onSuccess(metaDataService.ask(GetStudy(studyId)).mapTo[Option[Study]]) {
                complete(_)
              }
            }
          } ~ path("images") {
            parameters(
              'sources.as[String].?,
              'seriestypes.as[String].?,
              'seriestags.as[String].?) { (sourcesString, seriesTypesString, seriesTagsString) =>
                val sources = sourcesString.map(parseSourcesString(_)).getOrElse(Array.empty)
                val seriesTypes = seriesTypesString.map(parseIdsString(_)).getOrElse(Array.empty)
                val seriesTags = seriesTagsString.map(parseIdsString(_)).getOrElse(Array.empty)
                onSuccess(metaDataService.ask(GetImagesForStudy(studyId, sources, seriesTypes, seriesTags))) {
                  case Images(images) =>
                    complete(images)
                }
              }
          }
        }
      } ~ pathPrefix("series") {
        pathEndOrSingleSlash {
          get {
            parameters(
              'startindex.as[Long] ? 0,
              'count.as[Long] ? 20,
              'studyid.as[Long],
              'sources.as[String].?,
              'seriestypes.as[String].?,
              'seriestags.as[String].?) { (startIndex, count, studyId, sourcesString, seriesTypesString, seriesTagsString) =>
                val sources = sourcesString.map(parseSourcesString(_)).getOrElse(Array.empty)
                val seriesTypes = seriesTypesString.map(parseIdsString(_)).getOrElse(Array.empty)
                val seriesTags = seriesTagsString.map(parseIdsString(_)).getOrElse(Array.empty)
                onSuccess(metaDataService.ask(GetSeries(startIndex, count, studyId, sources, seriesTypes, seriesTags))) {
                  case SeriesCollection(series) =>
                    complete(series)
                }
              }
          }
        } ~ path("query") {
          post {
            entity(as[Query]) { query =>
              onSuccess(metaDataService.ask(QuerySeries(query))) {
                case SeriesCollection(series) =>
                  complete(series)
              }
            }
          }
        } ~ pathPrefix(LongNumber) { seriesId =>
          pathEndOrSingleSlash {
            get {
              onSuccess(metaDataService.ask(GetSingleSeries(seriesId)).mapTo[Option[Series]]) {
                complete(_)
              }
            }
          } ~ path("source") {
            get {
              onSuccess(metaDataService.ask(GetSourceForSeries(seriesId)).mapTo[Option[SeriesSource]]) { seriesSourceMaybe =>
                complete(seriesSourceMaybe.map(_.source))
              }
            }
          } ~ path("seriestypes") {
            get {
              onSuccess(seriesTypeService.ask(GetSeriesTypesForSeries(seriesId))) {
                case SeriesTypes(seriesTypes) =>
                  complete(seriesTypes)
              }
            }
          } ~ pathPrefix("seriestags") {
            pathEndOrSingleSlash {
              get {
                onSuccess(metaDataService.ask(GetSeriesTagsForSeries(seriesId))) {
                  case SeriesTags(seriesTags) =>
                    complete(seriesTags)
                }
              } ~ post {
                entity(as[SeriesTag]) { seriesTag =>
                  onSuccess(metaDataService.ask(AddSeriesTagToSeries(seriesTag, seriesId))) {
                    case SeriesTagAddedToSeries(seriesTag) =>
                      complete((Created, seriesTag))
                  }
                }
              }
            } ~ path(LongNumber) { seriesTagId =>
              delete {
                onSuccess(metaDataService.ask(RemoveSeriesTagFromSeries(seriesTagId, seriesId))) {
                  case SeriesTagRemovedFromSeries(seriesId) =>
                    complete(NoContent)
                }
              }
            }
          }
        }
      } ~ pathPrefix("images") {
        pathEndOrSingleSlash {
          get {
            parameters(
              'startindex.as[Long] ? 0,
              'count.as[Long] ? 20,
              'seriesid.as[Long]) { (startIndex, count, seriesId) =>
                onSuccess(metaDataService.ask(GetImages(startIndex, count, seriesId))) {
                  case Images(images) =>
                    complete(images)
                }
              }
          }
        } ~ path("query") {
          post {
            entity(as[Query]) { query =>
              onSuccess(metaDataService.ask(QueryImages(query))) {
                case Images(images) =>
                  complete(images)
              }
            }
          }
        } ~ path(LongNumber) { imageId =>
          get {
            onSuccess(metaDataService.ask(GetImage(imageId)).mapTo[Option[Image]]) {
              complete(_)
            }
          }
        }
      } ~ pathPrefix("flatseries") {
        pathEndOrSingleSlash {
          get {
            parameters(
              'startindex.as[Long] ? 0,
              'count.as[Long] ? 20,
              'orderby.as[String].?,
              'orderascending.as[Boolean] ? true,
              'filter.as[String].?,
              'sources.as[String].?,
              'seriestypes.as[String].?,
              'seriestags.as[String].?) { (startIndex, count, orderBy, orderAscending, filter, sourcesString, seriesTypesString, seriesTagsString) =>
                val sources = sourcesString.map(parseSourcesString(_)).getOrElse(Array.empty)
                val seriesTypes = seriesTypesString.map(parseIdsString(_)).getOrElse(Array.empty)
                val seriesTags = seriesTagsString.map(parseIdsString(_)).getOrElse(Array.empty)
                onSuccess(metaDataService.ask(GetFlatSeries(startIndex, count, orderBy, orderAscending, filter, sources, seriesTypes, seriesTags))) {
                  case FlatSeriesCollection(flatSeries) =>
                    complete(flatSeries)
                }
              }
          }
        } ~ path("query") {
          post {
            entity(as[Query]) { query =>
              onSuccess(metaDataService.ask(QueryFlatSeries(query))) {
                case FlatSeriesCollection(series) =>
                  complete(series)
              }
            }
          }
        } ~ path(LongNumber) { seriesId =>
          get {
            onSuccess(metaDataService.ask(GetSingleFlatSeries(seriesId)).mapTo[Option[FlatSeries]]) {
              complete(_)
            }
          }
        }
      }
    }

  def parseSourcesString(sourcesString: String): Array[SourceRef] = {
    try {
      sourcesString.split(",").map(typeIdString => {
        val typeIdArray = typeIdString.split(":")
        SourceRef(SourceType.withName(typeIdArray(0)), typeIdArray(1).toLong)
      })
    } catch {
      case e: Exception =>
        throw new IllegalArgumentException("Sources parameter must be formatted as a comma separated list of sourceType:sourceId elements, e.g. BOX:23,BOX:12,USER:2")
    }
  }

  def parseIdsString(seriesTypesString: String): Array[Long] = {
    try {
      seriesTypesString.split(",").map(_.toLong)
    } catch {
      case e: Exception =>
        throw new IllegalArgumentException("Series types and series tags parameters must be formatted as a comma separated list of ids, e.g. 2,5,11")
    }
  }

}
