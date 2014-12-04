package se.vgregion.app

import akka.actor.ActorRef
import se.vgregion.filesystem.FileSystemProtocol._
import se.vgregion.dicom.ScpProtocol._
import com.typesafe.config.Config
import se.vgregion.db.DbProtocol._

object InitialValues {

  def addUsers(userRepository: UserRepository) = 
    userRepository.addUser(ApiUser("test@example.com", Administrator).withPassword("test1234"))
  
  def createTables(dbActor: ActorRef) = dbActor ! CreateTables
    
  def initFileSystemData(config: Config, fileSystemActor: ActorRef) = {
    if (config.hasPath("default.monitor-directory")) {
      val scpConfig = config.getConfig("default.monitor-directory")
      val directory = scpConfig.getString("directory")
      fileSystemActor ! MonitorDir(directory)
    }
  }

  def initScpData(config: Config, scpCollectionActor: ActorRef, fileSystemActor: ActorRef) = {
    if (config.hasPath("default.scp")) {
      val scpConfig = config.getConfig("default.scp")
      val name = scpConfig.getString("name")
      val aeTitle = scpConfig.getString("aeTitle")
      val port = scpConfig.getInt("port")
      val directory = scpConfig.getString("directory")
      scpCollectionActor ! AddScp(ScpData(name, aeTitle, port, directory))
      fileSystemActor ! MonitorDir(directory)
    }
  }

}