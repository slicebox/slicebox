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

package se.nimsa.sbx.app.routing

import akka.pattern.ask
import akka.http.scaladsl.model.StatusCodes._
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.Route
import se.nimsa.sbx.app.SliceboxBase
import se.nimsa.sbx.log.LogProtocol._

trait LogRoutes { this: SliceboxBase =>

  def logRoutes: Route =
    pathPrefix("log") {
      pathEndOrSingleSlash {
        get {
          parameters(
            'startindex.as(nonNegativeFromStringUnmarshaller).?(0),
            'count.as(nonNegativeFromStringUnmarshaller).?(20),
            'subject.?,
            'type.?) { (startIndex, count, subjectMaybe, typeMaybe) =>
            val msg =
              subjectMaybe.flatMap(subject => typeMaybe.map(entryType => GetLogEntriesBySubjectAndType(subject, LogEntryType.withName(entryType), startIndex, count)))
                .orElse(subjectMaybe.map(subject => GetLogEntriesBySubject(subject, startIndex, count)))
                .orElse(typeMaybe.map(entryType => GetLogEntriesByType(LogEntryType.withName(entryType), startIndex, count)))
                .getOrElse(GetLogEntries(startIndex, count))
            onSuccess(logService.ask(msg)) {
              case LogEntries(logEntries) =>
                complete(logEntries)
            }
          }
        } 
      } ~ path(LongNumber) { logId =>
        delete {
            onSuccess(logService.ask(RemoveLogEntry(logId))) {
              case LogEntryRemoved(_) =>
                complete(NoContent)
            }          
        }
      }
    }

}
