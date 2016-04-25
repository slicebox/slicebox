package se.nimsa.sbx.importing

import akka.actor.Actor
import se.nimsa.sbx.app.DbProps
import akka.event.Logging
import akka.actor.Props
import akka.event.LoggingReceive
import se.nimsa.sbx.importing.ImportProtocol._
import se.nimsa.sbx.metadata.MetaDataProtocol.GetAllSeries

class ImportServiceActor(dbProps: DbProps) extends Actor {
  val log = Logging(context.system, this)

  val db = dbProps.db
  val dao = new ImportDAO(dbProps.driver)

  log.info("Import service started")

  override def receive = LoggingReceive {
    case AddImportSession(importSession) =>
      db.withSession { implicit session =>
        val newImportSession = importSession.copy(filesImported = 0, filesAdded = 0, filesRejected = 0,
          created = System.currentTimeMillis(), lastUpdated = System.currentTimeMillis())
        sender ! dao.addImportSession(newImportSession)
      }

    case GetImportSessions =>
      db.withSession { implicit session =>
        sender ! ImportSessions(dao.getImportSessions)
      }

    case GetImportSession(id) =>
      db.withSession { implicit session =>
        sender ! dao.getImportSession(id)
      }

    case DeleteImportSession(id) =>
      db.withSession { implicit session =>
        sender ! dao.removeImportSession(id)
      }

    case GetImportSessionImages(id) =>
      db.withSession { implicit session =>
        sender ! ImportSessionImages(dao.listImagesForImportSesstionId(id))
      }

    case AddImageToSession(importSession, image, overwrite) =>
      db.withSession { implicit session =>
        sender ! dao.insertImportSessionImage(ImportSessionImage(id = 0, importSessionId = importSessionId, imageId = image.id))
      }

    case msg =>
  }
}

object ImportServiceActor {
  def props(dbProps: DbProps): Props = Props(new ImportServiceActor(dbProps))
}
