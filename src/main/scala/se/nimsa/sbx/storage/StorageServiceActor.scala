/*
 * Copyright 2014 Lars Edenbrandt
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

package se.nimsa.sbx.storage

import akka.actor.{Actor, Props}
import akka.event.{Logging, LoggingReceive}
import se.nimsa.sbx.storage.StorageProtocol._
import se.nimsa.sbx.util.ExceptionCatching

import scala.concurrent.duration.{DurationInt, FiniteDuration}

class StorageServiceActor(storage: StorageService,
                          cleanupInterval: FiniteDuration = 6.hours,
                          cleanupMinimumFileAge: FiniteDuration = 6.hours) extends Actor with ExceptionCatching {

  import scala.collection.mutable

  val log = Logging(context.system, this)

  implicit val ec = context.dispatcher

  val exportSets = mutable.Map.empty[Long, Seq[Long]]

  case class RemoveExportSet(id: Long)

  // ensure dcm4che uses standard ImageIO image readers for parsing compressed image data
  // (see https://github.com/dcm4che/dcm4che/blob/3.3.7/dcm4che-imageio/src/main/java/org/dcm4che3/imageio/codec/ImageReaderFactory.java#L242)
  System.setProperty("dcm4che.useImageIOServiceRegistry", "true")

  log.info("Storage service started")

  def receive = LoggingReceive {

    case msg: ImageRequest => catchAndReport {
      msg match {

        case CreateExportSet(imageIds) =>
          val exportSetId = if (exportSets.isEmpty) 1 else exportSets.keys.max + 1
          exportSets(exportSetId) = imageIds
          context.system.scheduler.scheduleOnce(cleanupInterval, self, RemoveExportSet(exportSetId))
          sender ! ExportSetId(exportSetId)

        case GetExportSetImageIds(exportSetId) =>
          sender ! exportSets.get(exportSetId)

      }
    }

    case RemoveExportSet(id) =>
      exportSets.remove(id)
  }

}
object StorageServiceActor {
  def props(storage: StorageService): Props = Props(new StorageServiceActor(storage))
}
