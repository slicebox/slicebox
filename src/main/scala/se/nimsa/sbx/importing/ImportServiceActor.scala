/*
 * Copyright 2017 Lars Edenbrandt
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

package se.nimsa.sbx.importing

import akka.actor.Actor
import akka.event.Logging
import akka.actor.Props
import akka.event.LoggingReceive
import akka.util.Timeout
import se.nimsa.sbx.importing.ImportProtocol._
import se.nimsa.sbx.lang.NotFoundException
import se.nimsa.sbx.util.ExceptionCatching
import se.nimsa.sbx.util.FutureUtil.await

class ImportServiceActor(importDao: ImportDAO)(implicit timeout: Timeout) extends Actor with ExceptionCatching {
  val log = Logging(context.system, this)

  log.info("Import service started")

  override def receive = LoggingReceive {

    case msg: ImportSessionRequest => catchAndReport {
      msg match {

        case AddImportSession(importSession) =>
          await(importDao.importSessionForName(importSession.name)) match {
            case Some(existingImportSession) if existingImportSession.userId != importSession.userId =>
              throw new IllegalArgumentException(s"An import session with name ${existingImportSession.name} and user ${existingImportSession.user} already exists")
            case Some(existingImportSession) =>
              sender ! existingImportSession
            case None =>
              val newImportSession = importSession.copy(filesImported = 0, filesAdded = 0, filesRejected = 0, created = now, lastUpdated = now)
              sender ! await(importDao.addImportSession(newImportSession))
          }

        case GetImportSessions(startIndex, count) =>
          sender ! ImportSessions(await(importDao.getImportSessions(startIndex, count)))

        case GetImportSession(id) =>
          sender ! await(importDao.getImportSession(id))

        case DeleteImportSession(id) =>
          sender ! await(importDao.removeImportSession(id))

        case GetImportSessionImages(id) =>
          sender ! ImportSessionImages(await(importDao.listImagesForImportSessionId(id)))

        case AddImageToSession(importSessionId, image, overwrite) =>
          await(importDao.getImportSession(importSessionId)) match {
            case Some(importSession) =>
              val importSessionImage =
                if (overwrite) {
                  val updatedImportSession = importSession.copy(filesImported = importSession.filesImported + 1, lastUpdated = now)
                  await(importDao.updateImportSession(updatedImportSession))
                  await(importDao.importSessionImageForImportSessionIdAndImageId(importSession.id, image.id))
                    .getOrElse(await(importDao.insertImportSessionImage(ImportSessionImage(-1, updatedImportSession.id, image.id))))
                } else {
                  val updatedImportSession = importSession.copy(filesImported = importSession.filesImported + 1, filesAdded = importSession.filesAdded + 1, lastUpdated = now)
                  await(importDao.updateImportSession(updatedImportSession))
                  await(importDao.insertImportSessionImage(ImportSessionImage(-1, updatedImportSession.id, image.id)))
                }
              sender ! ImageAddedToSession(importSessionImage)
            case None =>
              throw new NotFoundException(s"Import session not found for id $importSessionId")
          }

        case UpdateSessionWithRejection(importSession) =>
          val updatedImportSession = importSession.copy(filesRejected = importSession.filesRejected + 1, lastUpdated = now)
          sender ! await(importDao.updateImportSession(updatedImportSession))

      }
    }

  }

  def now = System.currentTimeMillis

}

object ImportServiceActor {
  def props(importDao: ImportDAO)(implicit timeout: Timeout): Props = Props(new ImportServiceActor(importDao))
}
