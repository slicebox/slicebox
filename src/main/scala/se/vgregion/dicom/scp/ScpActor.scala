package se.vgregion.dicom.scp

import java.io.File
import java.util.concurrent.Executors
import ScpProtocol._
import akka.actor.Actor
import akka.actor.actorRef2Scala
import akka.event.Logging
import akka.event.LoggingReceive
import akka.actor.PoisonPill
import java.util.concurrent.Executor
import java.util.concurrent.ThreadFactory
import java.nio.file.Path
import akka.actor.ActorRef
import akka.actor.Props

class ScpActor(scpData: ScpData, executor: Executor, dicomActor: ActorRef) extends Actor {
  val log = Logging(context.system, this)

  val scheduledExecutor = Executors.newSingleThreadScheduledExecutor(new ThreadFactory() {
    override def newThread(runnable: Runnable): Thread = {
      val thread = Executors.defaultThreadFactory().newThread(runnable)
      thread.setDaemon(true)
      thread
    }
  })

  val scp = new Scp(scpData.name, scpData.aeTitle, scpData.port, dicomActor)
  scp.device.setScheduledExecutor(scheduledExecutor)
  scp.device.setExecutor(executor)
  scp.device.bindConnections()

  override def postStop() {
    scp.device.unbindConnections()
    scheduledExecutor.shutdown()
  }

  def receive = LoggingReceive {
    case ShutdownScp =>
      log.info(s"Shutting down SCP ${scpData.name}")
      sender ! ScpShutdown
      self ! PoisonPill
  }

}

object ScpActor {
  def props(scpData: ScpData, executor: Executor, dicomActor: ActorRef): Props = Props(new ScpActor(scpData, executor, dicomActor))
}
