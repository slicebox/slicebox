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

class ScpCollectionActor(dbActor: ActorRef, storageDirectory: File) extends Actor {
  val log = Logging(context.system, this)

  val executor = Executors.newCachedThreadPool()

  override def postStop() {
    executor.shutdown()
  }

  def receive = LoggingReceive {
    case AddScp(scpData: ScpData) =>
      val scpActor = context.actorOf(Props(classOf[ScpActor], storageDirectory))
      scpActor ! AddScpWithExecutor(scpData, executor)
    case ScpAdded(scpData) =>
      dbActor ! InsertScpData(scpData)
    case GetScpDataCollection =>
      dbActor forward GetScpDataEntries
  }
}