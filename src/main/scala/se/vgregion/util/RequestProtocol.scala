package se.vgregion.util

import spray.json._

case class ClientError(message: String)
case class ServerError(message: String)

object ClientError extends DefaultJsonProtocol {
  implicit val format = jsonFormat1(ClientError.apply)
}

object ServerError extends DefaultJsonProtocol {
  implicit val format = jsonFormat1(ServerError.apply)
}
