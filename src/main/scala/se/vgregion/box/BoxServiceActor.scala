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

class BoxServiceActor(dbProps: DbProps, host: String, port: Int) extends Actor with ExceptionCatching {

  val db = dbProps.db
  val dao = new BoxDAO(dbProps.driver)

  setupDb()
  setupBoxes()

  def receive = LoggingReceive {
    case msg: BoxRequest => msg match {

      case GenerateBoxBaseUrl(remoteBoxName) =>
        val token = UUID.randomUUID().toString()
        val baseUrl = s"http://$host/$port/api/box/$token"
        val box = Box(-1, remoteBoxName, token, baseUrl, BoxSendMethod.POLL)
        catchAndReport {
          addBoxToDb(box)
        }
        sender ! BoxBaseUrlGenerated(baseUrl)

      case AddRemoteBox(remoteBox) =>
        val box = pushBoxByBaseUrl(remoteBox.baseUrl) match {
          case Some(box) =>
            // already in the db, do nothing
            box
          case None =>
            val token = baseUrlToToken(remoteBox.baseUrl)
            val box = Box(-1, remoteBox.name, token, remoteBox.baseUrl, BoxSendMethod.PUSH)
            catchAndReport {
              addBoxToDb(box)
            }
            box
        }
        context.child(BoxSendMethod.PUSH + box.id.toString) match {
          case Some(actor) =>
          // do nothing
          case None =>
            startPushActor(box)
        }
        context.child(BoxSendMethod.POLL + box.id.toString) match {
          case Some(actor) =>
          // do nothing
          case None =>
            startPollActor(box)
        }
        sender ! RemoteBoxAdded(box)

      case RemoveBox(boxId) =>
        boxById(boxId).foreach(box => {
          context.child(BoxSendMethod.PUSH + boxId.toString)
            .foreach(_ ! PoisonPill)
          context.child(BoxSendMethod.POLL + boxId.toString)
            .foreach(_ ! PoisonPill)
        })
        removeBoxFromDb(boxId)
        sender ! BoxRemoved(boxId)

      case GetBoxes =>
        val boxes = getBoxesFromDb()
        sender ! Boxes(boxes)

      case ValidateToken(token) =>
        if (tokenIsValid(token))
          sender ! ValidToken(token)
        else
          sender ! InvalidToken(token)
    }
  }

  def setupDb() =
    db.withSession { implicit session =>
      dao.create
    }

  def baseUrlToToken(url: String) = {
    val trimmedUrl = url.trim.stripSuffix("/")
    trimmedUrl.substring(trimmedUrl.lastIndexOf("/"))
  }

  def setupBoxes() =
    getBoxesFromDb foreach (box => box.sendMethod match {
      case BoxSendMethod.POLL =>
        startPollActor(box)
      case BoxSendMethod.PUSH =>
        startPushActor(box)
    })

  def startPushActor(box: Box) =
    context.actorOf(BoxPushActor.props(box), box.id.toString)

  def startPollActor(box: Box) =
    context.actorOf(BoxPollActor.props(box), box.id.toString)

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
  def props(dbProps: DbProps, host: String, port: Int): Props = Props(new BoxServiceActor(dbProps, host, port))
}