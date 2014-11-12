package se.vgregion.dicom

import akka.actor.Actor
import akka.event.{ LoggingReceive, Logging }
import ScpProtocol._
import java.util.concurrent.Executors
import java.io.File
import java.util.concurrent.ScheduledExecutorService
import akka.actor.Props

class ScpCollectionActor extends Actor {
  val log = Logging(context.system, this)

  val executor = Executors.newCachedThreadPool()

  override def postStop() {
    executor.shutdown()
  }

  var dataCollection = List.empty[ScpData]

  def receive = LoggingReceive {
    case AddScp(scpData: ScpData) =>
      val scpActor = context.actorOf(Props[ScpActor])
      scpActor ! AddScpWithExecutor(scpData, executor)
    case ScpAdded(scpData) =>
      dataCollection = dataCollection :+ scpData
    case GetScpDataCollection =>
      sender ! ScpDataCollection(dataCollection)
  }
}