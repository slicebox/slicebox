package se.vgregion

import akka.actor.Actor
import akka.event.{LoggingReceive, Logging}
import StoreScpProtocol._
import java.util.concurrent.Executors
import java.io.File

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