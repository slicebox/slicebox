package se.nimsa.sbx.dicom.scp

import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory
import akka.actor.Actor
import akka.actor.ActorRef
import akka.actor.PoisonPill
import akka.actor.Props
import akka.event.Logging
import akka.event.LoggingReceive
import se.nimsa.sbx.dicom.DicomProtocol._
import java.util.Date
import se.nimsa.sbx.log.SbxLog

class ScpActor(scpData: ScpData, executor: Executor) extends Actor {
  
  import context.system
  
  val log = Logging(context.system, this)

  val scheduledExecutor = Executors.newSingleThreadScheduledExecutor(new ThreadFactory() {
    override def newThread(runnable: Runnable): Thread = {
      val thread = Executors.defaultThreadFactory().newThread(runnable)
      thread.setDaemon(true)
      thread
    }
  })

  val scp = new Scp(scpData.name, scpData.aeTitle, scpData.port, self)
  scp.device.setScheduledExecutor(scheduledExecutor)
  scp.device.setExecutor(executor)
  scp.device.bindConnections()
  SbxLog.info("SCP", s"Started SCP ${scpData.name} with AE title ${scpData.aeTitle} on port ${scpData.port}")

  override def postStop() {
    scp.device.unbindConnections()
    scheduledExecutor.shutdown()
    SbxLog.info("SCP", s"Stopped SCP ${scpData.name}")
  }

  def receive = LoggingReceive {
    case DatasetReceivedByScp(dataset) =>
      SbxLog.info("SCP", "Dataset received")
      context.system.eventStream.publish(DatasetReceived(dataset))
  }

}

object ScpActor {
  def props(scpData: ScpData, executor: Executor): Props = Props(new ScpActor(scpData, executor))
}
