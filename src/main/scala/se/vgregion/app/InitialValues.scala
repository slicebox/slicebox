package se.vgregion.app

import akka.actor.ActorRef
import se.vgregion.filesystem.FileSystemProtocol._
import se.vgregion.dicom.ScpProtocol._

object InitialValues {

  def initFileSystemData(fileSystemActor: ActorRef) = {
    fileSystemActor ! MonitorDir("C:/users/karl/Desktop/temp")
  }

  def initScpData(scpCollectionActor: ActorRef) = {
    scpCollectionActor ! AddScp(ScpData("testSCP", "myAE", 11123))
  }

}