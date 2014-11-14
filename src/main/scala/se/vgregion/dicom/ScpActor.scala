package se.vgregion.dicom

import java.io.File
import java.util.concurrent.Executors

import ScpProtocol._
import akka.actor.Actor
import akka.actor.actorRef2Scala
import akka.event.Logging
import akka.event.LoggingReceive

class ScpActor(storageDirectory: File) extends Actor {
	val log = Logging(context.system, this)
  
  val scheduledExecutor = Executors.newSingleThreadScheduledExecutor()
  
	override def postStop() {
    scheduledExecutor.shutdown()
	}

	def receive = LoggingReceive {
    case AddScpWithExecutor(data, executor) =>
      val scp = new Scp(data.name, data.aeTitle, data.port, storageDirectory)
      scp.device.setScheduledExecutor(scheduledExecutor)
      scp.device.setExecutor(executor)
      scp.device.bindConnections()
      sender ! ScpAdded(data)
	}
}