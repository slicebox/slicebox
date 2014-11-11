package se.vgregion.filesystem

import akka.actor.Actor
import akka.event.{LoggingReceive, Logging}
import FileSystemProtocol._
import java.nio.file.Paths
import akka.actor.actorRef2Scala

class FileSystemActor extends Actor {
	val log = Logging(context.system, this)
	val watchServiceTask = new WatchServiceTask(self)
	val watchThread = new Thread(watchServiceTask, "WatchService")

	override def preStart() {
		watchThread.setDaemon(true)
		watchThread.start()
	}

	override def postStop() {
		watchThread.interrupt()
	}

  var files = List.empty[FileName]
  
	def receive = LoggingReceive {
		case MonitorDir(dir) =>
			watchServiceTask watchRecursively Paths.get(dir)
      sender ! MonitoringDir
		case Created(file) =>
      files = files :+ FileName(file.getName)
		case Deleted(fileOrDir) =>
      files = files diff List(FileName(fileOrDir.getName))
    case GetFileNames =>
      sender ! FileNames(files)
      
	}
}