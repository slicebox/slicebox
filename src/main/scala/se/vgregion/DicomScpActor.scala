package se.vgregion

import akka.actor.Actor
import akka.event.{LoggingReceive, Logging}
import FileSystemProtocol._
import java.nio.file.Paths

class DicomScpActor extends Actor {
	val log = Logging(context.system, this)
	//val watchServiceTask = new WatchServiceTask(self)
	//val watchThread = new Thread(watchServiceTask, "WatchService")

	override def preStart() {
		//watchThread.setDaemon(true)
		//watchThread.start()
	}

	override def postStop() {
		//watchThread.interrupt()
	}

  var files = List.empty[FileName]
  
	def receive = LoggingReceive {
    null
	}
}