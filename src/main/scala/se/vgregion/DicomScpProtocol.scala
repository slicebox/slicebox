package se.vgregion

import java.nio.file.Path
import java.io.File
import spray.json.DefaultJsonProtocol

object DicomScpProtocol {

  // incoming
  
  case class AddNode(port: Int, AeTitle: String)
  
  // outgoing

  case object NodeAdded
  
  //----------------------------------------------
  // JSON
  //----------------------------------------------

}