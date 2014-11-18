package se.vgregion.dicom

import java.io.File
import java.util.concurrent.Executors
import ScpProtocol._
import akka.actor.Actor
import akka.actor.actorRef2Scala
import akka.event.Logging
import akka.event.LoggingReceive
import akka.actor.PoisonPill
import java.util.concurrent.Executor

class ScpActor(scpData: ScpData, storageDirectory: String, executor: Executor) extends Actor {
  val log = Logging(context.system, this)

  val scheduledExecutor = Executors.newSingleThreadScheduledExecutor()

  val storageDirectoryFile = new File(storageDirectory)
  
  if (!storageDirectoryFile.exists() || !storageDirectoryFile.isDirectory()) {
    throw new Exception("SCP storage directory does not exist or is not a directory")
  }
  
  val scp = new Scp(scpData.name, scpData.aeTitle, scpData.port, storageDirectoryFile)
  scp.device.setScheduledExecutor(scheduledExecutor)
  scp.device.setExecutor(executor)
  scp.device.bindConnections()

  def receive = LoggingReceive {
    case ShutdownScp =>
      log.info(s"Shutting down SCP ${scpData.name}")
      scp.device.unbindConnections()
      scheduledExecutor.shutdown()
      sender ! ScpShutdown
      self ! PoisonPill
  }
  
}