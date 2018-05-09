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

package se.nimsa.sbx.importing

import akka.actor.{Actor, Props, Stash}
import akka.event.{Logging, LoggingReceive}
import akka.pattern.pipe
import se.nimsa.sbx.importing.ImportProtocol._
import se.nimsa.sbx.lang.NotFoundException
import se.nimsa.sbx.util.SbxExtensions._
import se.nimsa.sbx.util.{ExceptionCatching, SequentialPipeToSupport}

import scala.concurrent.{ExecutionContext, Future}

class ImportServiceActor(importDao: ImportDAO) extends Actor with ExceptionCatching with Stash with SequentialPipeToSupport {

  import context.system

  implicit val ec: ExecutionContext = system.dispatcher

  val log = Logging(context.system, this)
  log.info("Import service started")

  override def receive = LoggingReceive {

    case msg: ImportSessionRequest =>
      msg match {

        case AddImportSession(importSession) =>
          importDao.importSessionForName(importSession.name).flatMap {
            case Some(existingImportSession) if existingImportSession.userId != importSession.userId =>
              Future.failed(new IllegalArgumentException(s"An import session with name ${existingImportSession.name} and user ${existingImportSession.user} already exists"))
            case Some(existingImportSession) =>
              Future.successful(existingImportSession)
            case None =>
              val newImportSession = importSession.copy(filesImported = 0, filesAdded = 0, filesRejected = 0, created = now, lastUpdated = now)
              importDao.addImportSession(newImportSession)
          }.pipeSequentiallyTo(sender)

        case GetImportSessions(startIndex, count) =>
          pipe(importDao.getImportSessions(startIndex, count).map(ImportSessions)).to(sender)

        case GetImportSession(id) =>
          pipe(importDao.getImportSession(id)).to(sender)

        case DeleteImportSession(id) =>
          importDao.removeImportSession(id).pipeSequentiallyTo(sender)

        case GetImportSessionImages(id) =>
          pipe(importDao.listImagesForImportSessionId(id).map(ImportSessionImages)).to(sender)

        case AddImageToSession(importSessionId, image, overwrite) =>
          importDao.getImportSession(importSessionId).flatMap {
            case Some(importSession) =>
              val importSessionImage =
                if (overwrite) {
                  val updatedImportSession = importSession.copy(filesImported = importSession.filesImported + 1, lastUpdated = now)
                  importDao.updateImportSession(updatedImportSession)
                    .flatMap { _ =>
                      importDao.importSessionImageForImportSessionIdAndImageId(importSession.id, image.id)
                        .flatMap(_
                          .map(Future.successful)
                          .getOrElse(importDao.insertImportSessionImage(ImportSessionImage(-1, updatedImportSession.id, image.id))))
                    }
                } else {
                  val updatedImportSession = importSession.copy(filesImported = importSession.filesImported + 1, filesAdded = importSession.filesAdded + 1, lastUpdated = now)
                  importDao.updateImportSession(updatedImportSession)
                    .flatMap(_ => importDao.insertImportSessionImage(ImportSessionImage(-1, updatedImportSession.id, image.id)))
                }
              importSessionImage.map(ImageAddedToSession)
            case None =>
              Future.failed(new NotFoundException(s"Import session not found for id $importSessionId"))
          }.pipeSequentiallyTo(sender)

        case UpdateSessionWithRejection(importSessionId) =>
          importDao.getImportSession(importSessionId).map(_.map { importSession =>
            val updatedImportSession = importSession.copy(filesRejected = importSession.filesRejected + 1, lastUpdated = now)
            importDao.updateImportSession(updatedImportSession)
          }).unwrap.pipeSequentiallyTo(sender)

      }

  }

  def now: Long = System.currentTimeMillis

}

object ImportServiceActor {
  def props(importDao: ImportDAO): Props = Props(new ImportServiceActor(importDao))
}
