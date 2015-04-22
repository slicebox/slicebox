package se.nimsa.sbx.app.routing

import akka.pattern.ask

import spray.httpx.SprayJsonSupport._
import spray.routing._

import se.nimsa.sbx.app.RestApi
import se.nimsa.sbx.dicom.DicomProtocol._

trait SeriesRoutes { this: RestApi =>

  def seriesRoutes: Route =
    pathPrefix("series") {
      path("datasets") {
        get {
          parameters('seriesid.as[Long]) { seriesId =>
            onSuccess(dicomService.ask(GetImages(seriesId))) {
              case Images(images) =>
                val imageURLs =
                  images.map(image => SeriesDataset(image.id, s"$apiBaseURL/images/${image.id}"))
                complete(imageURLs)
            }
          }
        }
      }
    }

}