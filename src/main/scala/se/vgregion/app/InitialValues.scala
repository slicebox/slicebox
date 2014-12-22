package se.vgregion.app

import com.typesafe.config.Config
import akka.actor.ActorRef
import akka.pattern.ask
import akka.util.Timeout
import scala.concurrent.duration.DurationInt
import scala.language.postfixOps
import se.vgregion.dicom.DicomDispatchProtocol._
import scala.concurrent.ExecutionContext
import akka.actor.ActorDSL._
import akka.actor.ActorSystem

object InitialValues {

  def addUsers(userRepository: UserRepository) =
    userRepository.addUser(ApiUser("test@example.com", Administrator).withPassword("test1234"))

  def initialize(dicomDispatchActor: ActorRef, userRepository: UserRepository)(implicit system: ActorSystem) = {
    implicit val ec = system.dispatcher
    implicit val i = inbox()
    implicit val timeout = Timeout(10 seconds)
    dicomDispatchActor ! Initialize
    i.receive(timeout.duration) match {
      case Initialized => userRepository.initialize()
      case InitializationFailed =>
        println("Initlializatin failed")
        System.exit(1)
    }
  }

  def initFileSystemData(config: Config, dicomDispatchActor: ActorRef) = {
    if (config.hasPath("default.monitor-directory")) {
      val directoryConfig = config.getConfig("default.monitor-directory")
      val directory = directoryConfig.getString("directory")
      dicomDispatchActor ! WatchDirectory(directory)
    }
  }

  def initScpData(config: Config, dicomDispatchActor: ActorRef) = {
    if (config.hasPath("default.scp")) {
      val scpConfig = config.getConfig("default.scp")
      val name = scpConfig.getString("name")
      val aeTitle = scpConfig.getString("aeTitle")
      val port = scpConfig.getInt("port")
      dicomDispatchActor ! AddScp(ScpData(name, aeTitle, port))
    }
  }

}