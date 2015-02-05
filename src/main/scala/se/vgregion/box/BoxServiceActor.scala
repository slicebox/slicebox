package se.vgregion.box

import se.vgregion.app.DbProps
import akka.actor.Actor
import akka.event.LoggingReceive
import se.vgregion.box.BoxProtocol._
import akka.actor.Props
import akka.actor.PoisonPill
import java.util.UUID
import akka.actor.Status.Failure
import se.vgregion.util.ExceptionCatching
import java.nio.file.Path

class BoxServiceActor(dbProps: DbProps, storage: Path, host: String, port: Int) extends Actor with ExceptionCatching {

  val db = dbProps.db
  val dao = new BoxDAO(dbProps.driver)

  setupDb()
  setupBoxes()

  def receive = LoggingReceive {
    case msg: BoxRequest => msg match {

      case GenerateBoxBaseUrl(remoteBoxName) =>
        catchAndReport {
          val token = UUID.randomUUID().toString()
          val baseUrl = s"http://$host/$port/api/box/$token"
          val box = Box(-1, remoteBoxName, token, baseUrl, BoxSendMethod.POLL)
          addBoxToDb(box)
          sender ! BoxBaseUrlGenerated(baseUrl)
        }

      case AddRemoteBox(remoteBox) =>
        catchAndReport {
          val box = pushBoxByBaseUrl(remoteBox.baseUrl) getOrElse {
            val token = baseUrlToToken(remoteBox.baseUrl)
            val box = Box(-1, remoteBox.name, token, remoteBox.baseUrl, BoxSendMethod.PUSH)
            addBoxToDb(box)
          }
          maybeStartPushActor(box)
          maybeStartPollActor(box)
          sender ! RemoteBoxAdded(box)
        }

      case RemoveBox(boxId) =>
        catchAndReport {
          boxById(boxId).foreach(box => {
            context.child(pushActorName(box))
              .foreach(_ ! PoisonPill)
            context.child(pollActorName(box))
              .foreach(_ ! PoisonPill)
          })
          removeBoxFromDb(boxId)
          sender ! BoxRemoved(boxId)
        }

      case GetBoxes =>
        catchAndReport {
          val boxes = getBoxesFromDb()
          sender ! Boxes(boxes)
        }

      case ValidateToken(token) =>
        catchAndReport {
          if (tokenIsValid(token))
            sender ! ValidToken(token)
          else
            sender ! InvalidToken(token)
        }
    }
  }

  def setupDb(): Unit =
    db.withSession { implicit session =>
      dao.create
    }

  def teardownDb(): Unit =
    db.withSession { implicit session =>
      dao.drop
    }

  def baseUrlToToken(url: String): String =
    try {
      val trimmedUrl = url.trim.stripSuffix("/")
      val token = trimmedUrl.substring(trimmedUrl.lastIndexOf("/") + 1)
      // see if the UUID class accepts the string as a valid token, throw exception if not
      UUID.fromString(token)
      token
    } catch {
      case e: Exception => throw new IllegalArgumentException("Malformed box base url: " + url, e)
    }

  def setupBoxes(): Unit =
    getBoxesFromDb foreach (box => box.sendMethod match {
      case BoxSendMethod.POLL =>
        maybeStartPollActor(box)
      case BoxSendMethod.PUSH =>
        maybeStartPushActor(box)
    })

  def maybeStartPushActor(box: Box): Unit = {
    val actorName = pushActorName(box)
    if (context.child(actorName).isEmpty)
      context.actorOf(BoxPushActor.props(box, dbProps, storage), actorName)
  }

  def maybeStartPollActor(box: Box): Unit = {
    val actorName = pollActorName(box)
    if (context.child(actorName).isEmpty)
      context.actorOf(BoxPollActor.props(box), actorName)
  }

  def pushActorName(box: Box): String = BoxSendMethod.PUSH + "-" + box.id.toString

  def pollActorName(box: Box): String = BoxSendMethod.POLL + "-" + box.id.toString

  def addBoxToDb(box: Box): Box =
    db.withSession { implicit session =>
      dao.insertBox(box)
    }

  def boxById(boxId: Long): Option[Box] =
    db.withSession { implicit session =>
      dao.boxById(boxId)
    }

  def pushBoxByBaseUrl(baseUrl: String): Option[Box] =
    db.withSession { implicit session =>
      dao.pushBoxByBaseUrl(baseUrl)
    }

  def removeBoxFromDb(boxId: Long) =
    db.withSession { implicit session =>
      dao.removeBox(boxId)
    }

  def getBoxesFromDb(): Seq[Box] =
    db.withSession { implicit session =>
      dao.listBoxes
    }

  def tokenIsValid(token: String): Boolean =
    db.withSession { implicit session =>
      true // TODO
    }
}

object BoxServiceActor {
  def props(dbProps: DbProps, storage: Path, host: String, port: Int): Props = Props(new BoxServiceActor(dbProps, storage, host, port))
}