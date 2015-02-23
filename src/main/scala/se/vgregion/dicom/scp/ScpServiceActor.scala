package se.vgregion.dicom.scp

import java.nio.file.Path
import java.util.concurrent.Executors
import scala.language.postfixOps
import akka.actor.Actor
import akka.actor.PoisonPill
import akka.actor.Props
import akka.event.Logging
import akka.event.LoggingReceive
import se.vgregion.app.DbProps
import se.vgregion.dicom.DicomDispatchActor
import se.vgregion.dicom.DicomProtocol._
import se.vgregion.util.ExceptionCatching

class ScpServiceActor(dbProps: DbProps, storage: Path) extends Actor with ExceptionCatching {
  val log = Logging(context.system, this)

  val db = dbProps.db
  val dao = new ScpDataDAO(dbProps.driver)

  val executor = Executors.newCachedThreadPool()
  
  setupDb()
  setupScps()

  override def postStop() {
    executor.shutdown()
  }

  def receive = LoggingReceive {

    case msg: ScpRequest =>

      catchAndReport {
      
        msg match {

          case AddScp(name, aeTitle, port) =>
            val scpData = ScpData(-1, name, aeTitle, port)
            val id = scpDataToId(scpData)
            context.child(id) match {
              case Some(actor) =>
                sender ! ScpAdded(name)
              case None =>

                addScp(scpData)

                context.actorOf(ScpActor.props(scpData, executor), id)

                sender ! ScpAdded(name)

            }

          case RemoveScp(scpDataId) =>
            db.withSession { implicit session =>
              dao.scpDataForId(scpDataId)
            } match {
              case Some(scpData) =>
                db.withSession { implicit session =>
                  dao.deleteScpDataWithId(scpDataId)
                }

                val id = scpDataToId(scpData)
                context.child(id) match {
                  case Some(actor) =>

                    removeScp(scpData)

                    actor ! PoisonPill

                    sender ! ScpRemoved(scpDataId)

                  case None =>
                    sender ! ScpRemoved(scpDataId)
                }

              case None =>
                sender ! ScpRemoved(scpDataId)
            }

          case GetScpDataCollection =>
            val scps = getScps()
            sender ! ScpDataCollection(scps)

        }
      }

    case msg: DatasetReceivedByScp =>
      context.parent ! msg

  }

  def scpDataToId(scpData: ScpData) = scpData.name

  def addScp(scpData: ScpData) =
    db.withSession { implicit session =>
      dao.insert(scpData)
    }

  def removeScp(scpData: ScpData) =
    db.withSession { implicit session =>
      dao.deleteScpDataWithId(scpData.id)
    }

  def getScps() =
    db.withSession { implicit session =>
      dao.allScpDatas
    }

  def setupDb() =
    db.withSession { implicit session =>
      dao.create
    }

  def setupScps() =
    db.withTransaction { implicit session =>
      val scps = dao.allScpDatas
      scps foreach (scpData => context.actorOf(ScpActor.props(scpData, executor), scpDataToId(scpData)))
    }

}

object ScpServiceActor {
  def props(dbProps: DbProps, storage: Path): Props = Props(new ScpServiceActor(dbProps, storage))
}