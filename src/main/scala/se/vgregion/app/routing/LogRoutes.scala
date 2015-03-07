package se.vgregion.app.routing

import akka.pattern.ask

import spray.httpx.SprayJsonSupport._
import spray.routing._

import se.vgregion.app.RestApi
import se.vgregion.log.LogProtocol._

trait LogRoutes { this: RestApi =>

  def logRoutes: Route =
    pathPrefix("log") {
      pathEndOrSingleSlash {
        get {
          parameters('startindex.as[Long] ? 0, 'count.as[Long] ? 20) { (startIndex, count) =>
            onSuccess(logService.ask(GetLogEntries(startIndex, count))) {
              case LogEntries(logEntries) =>
                complete(logEntries)
            }
          }
        }
      }
    }

}