/*
 * Copyright 2015 Lars Edenbrandt
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package se.nimsa.sbx.dicom.directory

import java.nio.file.Files
import java.nio.file.Path
import akka.actor.Actor
import akka.actor.ActorRef
import akka.actor.Props
import akka.event.Logging
import akka.event.LoggingReceive
import se.nimsa.sbx.dicom.DicomUtil._
import se.nimsa.sbx.dicom.DicomProtocol.FileAddedToWatchedDirectory
import org.dcm4che3.data.Tag
import java.nio.file.Paths
import se.nimsa.sbx.dicom.DicomProtocol.FileReceived
import se.nimsa.sbx.dicom.DicomProtocol.SourceType
import se.nimsa.sbx.dicom.DicomProtocol.WatchedDirectory

class DirectoryWatchActor(watchedDirectory: WatchedDirectory) extends Actor {
  val log = Logging(context.system, this)

  val watchServiceTask = new DirectoryWatch(self)

  val watchThread = new Thread(watchServiceTask, "WatchService")

  override def preStart() {
    watchThread.setDaemon(true)
    watchThread.start()
    watchServiceTask watchRecursively Paths.get(watchedDirectory.path)
  }

  override def postStop() {
    watchThread.interrupt()
  }

  def receive = LoggingReceive {
    case FileAddedToWatchedDirectory(path) =>
      if (Files.isRegularFile(path))
        context.system.eventStream.publish(FileReceived(path, SourceType.DIRECTORY, Some(watchedDirectory.id)))
  }

}

object DirectoryWatchActor {
  def props(watchedDirectory: WatchedDirectory): Props = Props(new DirectoryWatchActor(watchedDirectory))
}
