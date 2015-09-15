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
import se.nimsa.sbx.app.RestApi
import se.nimsa.sbx.dicom.DicomHierarchy._
import se.nimsa.sbx.storage.StorageProtocol._
import se.nimsa.sbx.storage.StorageProtocol.SourceType._
import se.nimsa.sbx.app.UserProtocol._
import se.nimsa.sbx.box.BoxProtocol._
import se.nimsa.sbx.scp.ScpProtocol._
import se.nimsa.sbx.directory.DirectoryWatchProtocol._
import se.nimsa.sbx.seriestype.SeriesTypeProtocol.SeriesTypes
import scala.concurrent.Future

trait MetadataRoutes { this: RestApi =>

  def metaDataRoutes: Route = {
    pathPrefix("metadata") {
      path("sources") {
        get {
          def futureSources =
            for {
              users <- userService.ask(GetUsers).mapTo[Users]
              boxes <- boxService.ask(GetBoxes).mapTo[Boxes]
              scps <- scpService.ask(GetScps).mapTo[Scps]
              dirs <- directoryService.ask(GetWatchedDirectories).mapTo[WatchedDirectories]
            } yield {
              users.users.map(user => Source(SourceType.USER, user.user, user.id)) ++
                boxes.boxes.map(box => Source(SourceType.BOX, box.name, box.id)) ++
                scps.scps.map(scp => Source(SourceType.SCP, scp.name, scp.id)) ++
                dirs.directories.map(dir => Source(SourceType.DIRECTORY, dir.name, dir.id))
            }
          onSuccess(futureSources) {
            complete(_)
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
                onSuccess(storageService.ask(GetPatients(startIndex, count, orderBy, orderAscending, filter, sources, seriesTypes, seriesTags))) {
                  case Patients(patients) =>
                    complete(patients)
                }
              }
          }
        } ~ path("query") {
          post {
            entity(as[Query]) { query =>
              onSuccess(storageService.ask(QueryPatients(query))) {
                case Patients(patients) =>
                  complete(patients)
              }
            }
          }
        } ~ path(LongNumber) { patientId =>
          get {
            onSuccess(storageService.ask(GetPatient(patientId)).mapTo[Option[Patient]]) {
              complete(_)
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
                onSuccess(storageService.ask(GetStudies(startIndex, count, patientId, sources, seriesTypes, seriesTags))) {
                  case Studies(studies) =>
                    complete(studies)
                }
              }
          }
        } ~ path("query") {
          post {
            entity(as[Query]) { query =>
              onSuccess(storageService.ask(QueryStudies(query))) {
                case Studies(studies) =>
                  complete(studies)
              }
            }
          }
        } ~ path(LongNumber) { studyId =>
          get {
            onSuccess(storageService.ask(GetStudy(studyId)).mapTo[Option[Study]]) {
              complete(_)
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
                onSuccess(storageService.ask(GetSeries(startIndex, count, studyId, sources, seriesTypes, seriesTags))) {
                  case SeriesCollection(series) =>
                    complete(series)
                }
              }
          }
        } ~ path("query") {
          post {
            entity(as[Query]) { query =>
              onSuccess(storageService.ask(QuerySeries(query))) {
                case SeriesCollection(series) =>
                  complete(series)
              }
            }
          }
        } ~ pathPrefix(LongNumber) { seriesId =>
          pathEndOrSingleSlash {
            get {
              onSuccess(storageService.ask(GetSingleSeries(seriesId)).mapTo[Option[Series]]) {
                complete(_)
              }
            }
          } ~ path("source") {
            get {
              onSuccess(storageService.ask(GetSeriesSource(seriesId)).mapTo[Option[SeriesSource]]) { seriesSourceMaybe =>
                def futureSourceMaybe = seriesSourceMaybe.map(seriesSource => {
                  val futureNameMaybe = seriesSource.sourceTypeId.sourceType match {
                    case USER      => userService.ask(GetUser(seriesSource.sourceTypeId.sourceId)).mapTo[Option[ApiUser]].map(_.map(_.user))
                    case BOX       => boxService.ask(GetBoxById(seriesSource.sourceTypeId.sourceId)).mapTo[Option[Box]].map(_.map(_.name))
                    case DIRECTORY => directoryService.ask(GetWatchedDirectoryById(seriesSource.sourceTypeId.sourceId)).mapTo[Option[WatchedDirectory]].map(_.map(_.name))
                    case SCP       => scpService.ask(GetScpById(seriesSource.sourceTypeId.sourceId)).mapTo[Option[ScpData]].map(_.map(_.name))
                    case UNKNOWN   => Future(None)
                  }
                  val futureName: Future[String] = futureNameMaybe.map(_.getOrElse("<source removed>"))
                  futureName.map(name => Some(Source(seriesSource.sourceTypeId.sourceType, name, seriesSource.sourceTypeId.sourceId)))
                }).getOrElse(Future(None))
                onSuccess(futureSourceMaybe) {
                  complete(_)
                }
              }
            }
          } ~ path("seriestypes") {
            get {
              onSuccess(storageService.ask(GetSeriesTypesForSeries(seriesId))) {
                case SeriesTypes(seriesTypes) =>
                  complete(seriesTypes)
              }
            }
          } ~ pathPrefix("seriestags") {
            pathEndOrSingleSlash {
              get {
                onSuccess(storageService.ask(GetSeriesTagsForSeries(seriesId))) {
                  case SeriesTags(seriesTags) =>
                    complete(seriesTags)
                }
              } ~ post {
                entity(as[SeriesTag]) { seriesTag =>
                  onSuccess(storageService.ask(AddSeriesTagToSeries(seriesTag, seriesId))) {
                    case SeriesTagAddedToSeries(seriesTag) =>
                      complete((Created, seriesTag))
                  }
                }
              }
            } ~ path(LongNumber) { seriesTagId =>
              delete {
                onSuccess(storageService.ask(RemoveSeriesTagFromSeries(seriesTagId, seriesId))) {
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
                onSuccess(storageService.ask(GetImages(startIndex, count, seriesId))) {
                  case Images(images) =>
                    complete(images)
                }
              }
          }
        } ~ path("query") {
          post {
            entity(as[Query]) { query =>
              onSuccess(storageService.ask(QueryImages(query))) {
                case Images(images) =>
                  complete(images)
              }
            }
          }
        } ~ path(LongNumber) { imageId =>
          get {
            onSuccess(storageService.ask(GetImage(imageId)).mapTo[Option[Image]]) {
              complete(_)
            }
          }
        }
      } ~ path("flatseries") {
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
                onSuccess(storageService.ask(GetFlatSeries(startIndex, count, orderBy, orderAscending, filter, sources, seriesTypes, seriesTags))) {
                  case FlatSeriesCollection(flatSeries) =>
                    complete(flatSeries)
                }
              }
          }
        } ~ path(LongNumber) { seriesId =>
          get {
            onSuccess(storageService.ask(GetSingleFlatSeries(seriesId)).mapTo[Option[FlatSeries]]) {
              complete(_)
            }
          }
        }
      }
    }
  }

  def parseSourcesString(sourcesString: String): Array[SourceTypeId] = {
    try {
      sourcesString.split(",").map(typeIdString => {
        val typeIdArray = typeIdString.split(":")
        SourceTypeId(SourceType.withName(typeIdArray(0)), typeIdArray(1).toLong)
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
