package se.vgregion.dicom

import akka.actor.Actor
import akka.event.{ LoggingReceive, Logging }
import ScpProtocol._
import se.vgregion.db.DbProtocol._
import java.util.concurrent.Executors
import java.io.File
import java.util.concurrent.ScheduledExecutorService
import akka.actor.Props
import akka.actor.ActorRef
import akka.actor.PoisonPill
import akka.pattern._
import akka.util.Timeout
import scala.concurrent.duration._
import scala.language.postfixOps
import akka.actor.Status.Success
import akka.actor.Status.Failure

class ScpCollectionActor(dbActor: ActorRef) extends Actor {
  val log = Logging(context.system, this)

  val executor = Executors.newCachedThreadPool()

  override def postStop() {
    executor.shutdown()
  }

  def receive = LoggingReceive {
    case AddScp(scpData) =>
      context.child(scpData.name) match {
        case Some(actor) =>
          sender ! ScpAlreadyAdded(scpData)
        case None =>
          try {
            context.actorOf(Props(classOf[ScpActor], scpData, executor), scpData.name)
            dbActor ! InsertScpData(scpData)
            sender ! ScpAdded(scpData)
          } catch {
            case _: Throwable => sender ! ScpSetupFailed
          }
      }
    case DeleteScp(name) =>
      context.child(name) match {
        case Some(actor) =>
          dbActor ! RemoveScpData(name)
          log.info(s"Deleting actor $actor")
          actor ! ShutdownScp
          sender ! ScpDeleted(name)
        case None =>
          sender ! ScpNotFound(name)
      }
    case GetScpDataCollection =>
      dbActor forward GetScpDataEntries
  }
}