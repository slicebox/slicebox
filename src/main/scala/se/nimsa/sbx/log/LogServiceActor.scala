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

package se.nimsa.sbx.log

import akka.actor.{Actor, Props}
import akka.event.{Logging, LoggingReceive}
import akka.pattern.pipe
import se.nimsa.sbx.log.LogProtocol._

class LogServiceActor(logDao: LogDAO) extends Actor {
  val log = Logging(context.system, this)

  implicit val ec = context.dispatcher

  override def preStart {
    context.system.eventStream.subscribe(self, classOf[AddLogEntry])
  }

  override def postStop {
    context.system.eventStream.unsubscribe(self)
  }

  log.info("Log service started")

  def receive = LoggingReceive {
    case AddLogEntry(logEntry) =>
      pipe(logDao.insertLogEntry(logEntry).map(LogEntryAdded)).to(sender)

    case GetLogEntries(startIndex, count) =>
      pipe(logDao.listLogEntries(startIndex, count).map(LogEntries)).to(sender)

    case GetLogEntriesBySubject(subject, startIndex, count) =>
      pipe(logDao.logEntriesBySubject(subject, startIndex, count).map(LogEntries)).to(sender)

    case GetLogEntriesByType(entryType, startIndex, count) =>
      pipe(logDao.logEntriesByType(entryType, startIndex, count).map(LogEntries)).to(sender)

    case GetLogEntriesBySubjectAndType(subject, entryType, startIndex, count) =>
      pipe(logDao.logEntriesBySubjectAndType(subject, entryType, startIndex, count).map(LogEntries)).to(sender)

    case RemoveLogEntry(id) =>
      pipe(logDao.removeLogEntry(id).map(_ => LogEntryRemoved(id))).to(sender)
  }

}

object LogServiceActor {
  def props(logDao: LogDAO): Props = Props(new LogServiceActor(logDao))
}
