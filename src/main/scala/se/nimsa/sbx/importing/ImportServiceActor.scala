package se.nimsa.sbx.importing

import akka.actor.Actor
import se.nimsa.sbx.app.DbProps
import akka.event.Logging
import akka.actor.Props

class ImportServiceActor(dbProps: DbProps) extends Actor {
  val log = Logging(context.system, this)

  val db = dbProps.db;
  val dao = new ImportingDAO(dbProps.driver)

  log.info("Import service started!")

  override def receive = {
    case msg => log.debug(s"Import service received $msg")
  }
}

object ImportServiceActor {
  def props(dbProps: DbProps): Props = Props(new ImportServiceActor(dbProps))
}
