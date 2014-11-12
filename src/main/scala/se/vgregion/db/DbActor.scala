package se.vgregion.db

import akka.actor.Actor
import DbProtocol._


class DbActor extends Actor {
   
  def receive = {
    case InsertMetaData(metaData) =>
      println("Inserting meta data")
    case GetMetaDataEntries =>
      println("Getting meta data entries")
  }
  
}