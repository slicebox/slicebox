package se.vgregion.dicom.scp

import java.util.concurrent.Executors
import scala.language.postfixOps
import akka.actor.Actor
import akka.actor.ActorRef
import akka.actor.Props
import akka.event.Logging
import akka.event.LoggingReceive
import se.vgregion.app.DbProps
import se.vgregion.dicom.DicomDispatchProtocol._
import java.nio.file.Path
import se.vgregion.dicom.DicomDispatchActor
import se.vgregion.util.PerEventCreator
import akka.actor.PoisonPill
import se.vgregion.util.ClientError
import se.vgregion.util.ServerError

class ScpCollectionActor(dbProps: DbProps, storage: Path) extends Actor with PerEventCreator {
  val log = Logging(context.system, this)

  val db = dbProps.db
  val dao = new ScpDataDAO(dbProps.driver)

  setupDb()

  val executor = Executors.newCachedThreadPool()

  override def postStop() {
    executor.shutdown()
  }

  def receive = LoggingReceive {

    case msg: ScpRequest => msg match {

      case AddScp(scpData) =>
        val id = scpDataToId(scpData)
        context.child(id) match {
          case Some(actor) =>
            sender ! ClientError("Could not create SCP: SCP already added: " + id)
          case None =>
            try {

              addScp(scpData)

              context.actorOf(ScpActor.props(scpData, executor), id)

              sender ! ScpAdded(scpData)

            } catch {
              case e: Throwable => sender ! ServerError("Could not create SCP: " + e.getMessage)
            }
        }

      case RemoveScp(scpData) =>
        val id = scpDataToId(scpData)
        context.child(id) match {
          case Some(actor) =>

            removeScp(scpData)

            actor ! PoisonPill

            sender ! ScpRemoved(scpData)

          case None =>
            sender ! ClientError("SCP does not exist: " + id)
        }

      case GetScpDataCollection =>
        val scps = getScps()
        sender ! ScpDataCollection(scps)

    }

    case msg: DatasetReceivedByScp =>
      perEvent(DicomDispatchActor.props(null, self, storage, dbProps), msg)

  }

  def scpDataToId(scpData: ScpData) = scpData.name

  def addScp(scpData: ScpData) =
    db.withSession { implicit session =>
      dao.insert(scpData)
    }

  def removeScp(scpData: ScpData) =
    db.withSession { implicit session =>
      dao.removeByName(scpData.name)
    }

  def getScps() =
    db.withSession { implicit session =>
      dao.list
    }

  def setupDb() =
    db.withSession { implicit session =>
      dao.create
    }

}

object ScpCollectionActor {
  def props(dbProps: DbProps, storage: Path): Props = Props(new ScpCollectionActor(dbProps, storage))
}