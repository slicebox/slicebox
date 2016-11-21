package se.nimsa.sbx.app

import akka.http.scaladsl.unmarshalling.PredefinedFromStringUnmarshallers

package object routing {

  val sessionField = "slicebox-session"

  val nonNegativeFromStringUnmarshaller = PredefinedFromStringUnmarshallers.longFromStringUnmarshaller.map { number =>
    if (number < 0) throw new IllegalArgumentException("number must be non-negative")
    number
  }

}
