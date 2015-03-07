package se.vgregion.app.routing

import akka.pattern.ask

import spray.httpx.SprayJsonSupport._
import spray.routing._

import se.vgregion.app.RestApi
import se.vgregion.dicom.DicomProtocol._

trait SeriesRoutes { this: RestApi =>

  def seriesRoutes: Route =
    pathPrefix("series") {
      path("datasets") {
        get {
          parameters('seriesId.as[Long]) { seriesId =>
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