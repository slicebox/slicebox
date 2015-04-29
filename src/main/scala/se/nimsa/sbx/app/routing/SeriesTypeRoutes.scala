package se.nimsa.sbx.app.routing

import akka.pattern.ask

import spray.http.ContentTypes
import spray.http.HttpData
import spray.http.HttpEntity
import spray.http.StatusCodes._
import spray.httpx.SprayJsonSupport._
import spray.routing._

import se.nimsa.sbx.app.AuthInfo
import se.nimsa.sbx.app.RestApi
import se.nimsa.sbx.seriestype.SeriesTypeProtocol._

trait SeriesTypeRoutes { this: RestApi =>

  def seriesTypeRoutes(authInfo: AuthInfo): Route =
    pathPrefix("seriestypes") {
      pathEndOrSingleSlash {
        get {
          onSuccess(seriesTypeService.ask(GetSeriesTypes)) {
            case SeriesTypes(seriesTypes) =>
              complete(seriesTypes)
          }
        }
      }
  }
}