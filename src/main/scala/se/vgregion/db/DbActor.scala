package se.vgregion.db

import akka.actor.Actor
import DbProtocol._
import scala.slick.driver.H2Driver.simple._


class DbActor extends Actor {

  val db = Database.forURL("jdbc:h2:mem:hello", driver = "org.h2.Driver")
   
  def receive = {
    case InsertMetaData(metaData) =>
      println("Inserting meta data")
    case GetMetaDataEntries =>
      println("Getting meta data entries")
  }
  
}