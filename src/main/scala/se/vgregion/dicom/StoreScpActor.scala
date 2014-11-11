package se.vgregion.dicom

import java.io.File
import java.util.concurrent.Executors

import StoreScpProtocol._
import akka.actor.Actor
import akka.actor.actorRef2Scala
import akka.event.Logging
import akka.event.LoggingReceive

class StoreScpActor extends Actor {
	val log = Logging(context.system, this)
  
  val scheduledExecutor = Executors.newSingleThreadScheduledExecutor()
  
	override def postStop() {
    scheduledExecutor.shutdown()
	}

	def receive = LoggingReceive {
    case AddStoreScpWithExecutor(data, executor) =>
      val storeScp = new StoreScp(data.name, data.aeTitle, data.port, new File("C:/users/karl/Desktop/temp"))
      storeScp.device.setScheduledExecutor(scheduledExecutor)
      storeScp.device.setExecutor(executor)
      storeScp.device.bindConnections()
      sender ! StoreScpAdded(data)
	}
}