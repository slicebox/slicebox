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

class ScpCollectionActor(dbProps: DbProps, storage: Path) extends Actor with PerEventCreator {
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
            context.actorOf(ScpActor.props(scpData, executor), scpData.name)
            sender ! ScpAdded(scpData)
          } catch {
            case e: Throwable => sender ! ScpSetupFailed(e.getMessage)
          }
      }
      
    case RemoveScp(scpData) =>
      context.child(scpData.name) match {
        case Some(actor) =>
          actor ! PoisonPill
          sender ! ScpRemoved(scpData)
        case None =>
          sender ! ScpNotFound(scpData)
      }
      
    case msg: DatasetReceivedByScp =>
       perEvent(DicomDispatchActor.props(null, self, storage, dbProps), msg)
      
  }
}

object ScpCollectionActor {
  def props(dbProps: DbProps, storage: Path): Props = Props(new ScpCollectionActor(dbProps, storage))
}