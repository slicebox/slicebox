package se.vgregion.dicom.scp

import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory

import akka.actor.Actor
import akka.actor.ActorRef
import akka.actor.PoisonPill
import akka.actor.Props
import akka.event.Logging
import akka.event.LoggingReceive
import se.vgregion.dicom.DicomProtocol._

class ScpActor(name: String, aeTitle: String, port: Int, executor: Executor) extends Actor {
  val log = Logging(context.system, this)

  val scheduledExecutor = Executors.newSingleThreadScheduledExecutor(new ThreadFactory() {
    override def newThread(runnable: Runnable): Thread = {
      val thread = Executors.defaultThreadFactory().newThread(runnable)
      thread.setDaemon(true)
      thread
    }
  })

  val scp = new Scp(name, aeTitle, port, self)
  scp.device.setScheduledExecutor(scheduledExecutor)
  scp.device.setExecutor(executor)
  scp.device.bindConnections()
  log.info(s"Started SCP $name with AE title $aeTitle on port $port")

  override def postStop() {
    scp.device.unbindConnections()
    scheduledExecutor.shutdown()
    log.info(s"Stopped SCP $name")
  }

  def receive = LoggingReceive {
    case DatasetReceivedByScp(dataset) =>
      context.system.eventStream.publish(DatasetReceived(dataset))
  }

}

object ScpActor {
  def props(name: String, aeTitle: String, port: Int, executor: Executor): Props = Props(new ScpActor(name, aeTitle, port, executor))
}
