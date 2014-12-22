package se.vgregion.util

trait RestMessage

case class Message(message: String) extends RestMessage

case class Validation(message: String)

case class Error(message: String)

