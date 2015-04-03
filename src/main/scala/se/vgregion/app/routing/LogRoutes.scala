package se.vgregion.app.routing

import akka.pattern.ask

import spray.httpx.SprayJsonSupport._
import spray.routing._
import spray.http.StatusCodes._

import se.vgregion.app.RestApi
import se.vgregion.log.LogProtocol._

trait LogRoutes { this: RestApi =>

  def logRoutes: Route =
    pathPrefix("log") {
      pathEndOrSingleSlash {
        get {
          parameters('startindex.as[Long].?(0), 'count.as[Long].?(20), 'subject.?, 'type.?) { (startIndex, count, subjectMaybe, typeMaybe) =>
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
              case LogEntryRemoved(logId) =>
                complete(NoContent)
            }          
        }
      }
    }

}