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

package se.nimsa.sbx.app.routing

import akka.pattern.ask
import se.nimsa.sbx.app.GeneralProtocol._
import se.nimsa.sbx.app.SliceboxBase
import se.nimsa.sbx.dicom.DicomHierarchy._
import se.nimsa.sbx.metadata.MetaDataProtocol._
import se.nimsa.sbx.seriestype.SeriesTypeProtocol._
import akka.http.scaladsl.model.StatusCodes._
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.Route

trait MetadataRoutes {
  this: SliceboxBase =>

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
            parameters((
              'startindex.as(nonNegativeFromStringUnmarshaller) ? 0,
              'count.as(nonNegativeFromStringUnmarshaller) ? 20,
              'orderby.as[String].?,
              'orderascending.as[Boolean] ? true,
              'filter.as[String].?,
              'sources.as[String].?,
              'seriestypes.as[String].?,
              'seriestags.as[String].?)) { (startIndex, count, orderBy, orderAscending, filter, sourcesString, seriesTypesString, seriesTagsString) =>
              val sources = sourcesString.map(parseSourcesString).getOrElse(Array.empty)
              val seriesTypes = seriesTypesString.map(parseIdsString).getOrElse(Array.empty)
              val seriesTags = seriesTagsString.map(parseIdsString).getOrElse(Array.empty)
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
            (get & rejectEmptyResponse) {
              onSuccess(metaDataService.ask(GetPatient(patientId)).mapTo[Option[Patient]]) {
                complete(_)
              }
            }
          } ~ path("images") {
            parameters((
              'sources.as[String].?,
              'seriestypes.as[String].?,
              'seriestags.as[String].?)) { (sourcesString, seriesTypesString, seriesTagsString) =>
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
            parameters((
              'startindex.as(nonNegativeFromStringUnmarshaller) ? 0,
              'count.as(nonNegativeFromStringUnmarshaller) ? 20,
              'patientid.as[Long],
              'sources.as[String].?,
              'seriestypes.as[String].?,
              'seriestags.as[String].?)) { (startIndex, count, patientId, sourcesString, seriesTypesString, seriesTagsString) =>
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
            (get & rejectEmptyResponse) {
              onSuccess(metaDataService.ask(GetStudy(studyId)).mapTo[Option[Study]]) {
                complete(_)
              }
            }
          } ~ path("images") {
            parameters((
              'sources.as[String].?,
              'seriestypes.as[String].?,
              'seriestags.as[String].?)) { (sourcesString, seriesTypesString, seriesTagsString) =>
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
            parameters((
              'startindex.as(nonNegativeFromStringUnmarshaller) ? 0,
              'count.as(nonNegativeFromStringUnmarshaller) ? 20,
              'studyid.as[Long],
              'sources.as[String].?,
              'seriestypes.as[String].?,
              'seriestags.as[String].?)) { (startIndex, count, studyId, sourcesString, seriesTypesString, seriesTagsString) =>
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
            (get & rejectEmptyResponse) {
              onSuccess(metaDataService.ask(GetSingleSeries(seriesId)).mapTo[Option[Series]]) {
                complete(_)
              }
            }
          } ~ path("source") {
            (get & rejectEmptyResponse) {
              onSuccess(metaDataService.ask(GetSourceForSeries(seriesId)).mapTo[Option[SeriesSource]]) { seriesSourceMaybe =>
                complete(seriesSourceMaybe.map(_.source))
              }
            }
          } ~ pathPrefix("seriestypes") {
            pathEndOrSingleSlash {
              get {
                onSuccess(seriesTypeService.ask(GetSeriesTypesForSeries(seriesId))) {
                  case SeriesTypes(seriesTypes) =>
                    complete(seriesTypes)
                }
              } ~ delete {
                onSuccess(seriesTypeService.ask(RemoveSeriesTypesFromSeries(seriesId))) {
                  case _ =>
                    complete(NoContent)
                }
              }
            } ~ path(LongNumber) { seriesTypeId =>
              put {
                onSuccess(metaDataService.ask(GetSingleSeries(seriesId)).mapTo[Option[Series]]) {
                  case Some(series) =>
                    onSuccess(seriesTypeService.ask(GetSeriesType(seriesTypeId)).mapTo[Option[SeriesType]]) {
                      case Some(seriesType) =>
                        onSuccess(seriesTypeService.ask(AddSeriesTypeToSeries(series, seriesType))) {
                          case _ =>
                            complete(NoContent)
                        }
                      case None =>
                        complete(NotFound)
                    }
                  case None =>
                    complete(NotFound)
                }
              } ~ delete {
                onSuccess(seriesTypeService.ask(RemoveSeriesTypeFromSeries(seriesId, seriesTypeId))) {
                  case _ =>
                    complete(NoContent)
                }
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
                    case SeriesTagAddedToSeries(addedSeriesTag) =>
                      complete((Created, addedSeriesTag))
                  }
                }
              }
            } ~ path(LongNumber) { seriesTagId =>
              delete {
                onSuccess(metaDataService.ask(RemoveSeriesTagFromSeries(seriesTagId, seriesId))) {
                  case SeriesTagRemovedFromSeries(_) =>
                    complete(NoContent)
                }
              }
            }
          }
        }
      } ~ pathPrefix("images") {
        pathEndOrSingleSlash {
          get {
            parameters((
              'startindex.as(nonNegativeFromStringUnmarshaller) ? 0,
              'count.as(nonNegativeFromStringUnmarshaller) ? 20,
              'seriesid.as[Long])) { (startIndex, count, seriesId) =>
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
          (get & rejectEmptyResponse) {
            onSuccess(metaDataService.ask(GetImage(imageId)).mapTo[Option[Image]]) {
              complete(_)
            }
          }
        }
      } ~ pathPrefix("flatseries") {
        pathEndOrSingleSlash {
          get {
            parameters((
              'startindex.as(nonNegativeFromStringUnmarshaller) ? 0,
              'count.as(nonNegativeFromStringUnmarshaller) ? 20,
              'orderby.as[String].?,
              'orderascending.as[Boolean] ? true,
              'filter.as[String].?,
              'sources.as[String].?,
              'seriestypes.as[String].?,
              'seriestags.as[String].?)) { (startIndex, count, orderBy, orderAscending, filter, sourcesString, seriesTypesString, seriesTagsString) =>
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
          (get & rejectEmptyResponse) {
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
