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

package se.nimsa.sbx.anonymization

import akka.actor.{Actor, Props}
import akka.event.{Logging, LoggingReceive}
import akka.pattern.pipe
import se.nimsa.sbx.anonymization.AnonymizationProtocol._
import se.nimsa.sbx.app.GeneralProtocol.ImageDeleted

import scala.concurrent.ExecutionContext

class AnonymizationServiceActor(anonymizationDao: AnonymizationDAO, purgeEmptyAnonymizationKeys: Boolean)(implicit ec: ExecutionContext) extends Actor {

  val log = Logging(context.system, this)

  override def preStart {
    context.system.eventStream.subscribe(context.self, classOf[ImageDeleted])
  }

  log.info("Anonymization service started")

  def receive = LoggingReceive {

    case ImageDeleted(imageId) =>
      anonymizationDao.removeAnonymizationKeyImagesForImageId(imageId, purgeEmptyAnonymizationKeys)

    case msg: AnonymizationRequest =>

      msg match {
        case RemoveAnonymizationKey(anonymizationKeyId) =>
          pipe(anonymizationDao.removeAnonymizationKey(anonymizationKeyId).map(_ => AnonymizationKeyRemoved(anonymizationKeyId))).to(sender)

        case GetAnonymizationKeys(startIndex, count, orderBy, orderAscending, filter) =>
          pipe(anonymizationDao.anonymizationKeys(startIndex, count, orderBy, orderAscending, filter).map(AnonymizationKeys)).to(sender)

        case GetAnonymizationKey(anonymizationKeyId) =>
          pipe(anonymizationDao.anonymizationKeyForId(anonymizationKeyId)).to(sender)

        case GetImageIdsForAnonymizationKey(anonymizationKeyId) =>
          pipe(anonymizationDao.anonymizationKeyImagesForAnonymizationKeyId(anonymizationKeyId).map(_.map(_.imageId))).to(sender)

        case GetAnonymizationKeysForPatient(patientName, patientID) =>
          pipe(anonymizationDao.anonymizationKeysForPatient(patientName, patientID).map(AnonymizationKeys)).to(sender)

        case GetReverseAnonymizationKeysForPatient(anonPatientName, anonPatientID) =>
          pipe(anonymizationDao.anonymizationKeysForAnonPatient(anonPatientName, anonPatientID).map(AnonymizationKeys)).to(sender)

        case AddAnonymizationKey(anonymizationKey) =>
          pipe(anonymizationDao.insertAnonymizationKey(anonymizationKey).map(AnonymizationKeyAdded)).to(sender)

        case QueryAnonymizationKeys(query) =>
          val order = query.order.map(_.orderBy)
          val orderAscending = query.order.forall(_.orderAscending)
          pipe(anonymizationDao.queryAnonymizationKeys(query.startIndex, query.count, order, orderAscending, query.queryProperties)).to(sender)
      }
  }

}

object AnonymizationServiceActor {
  def props(anonymizationDao: AnonymizationDAO, purgeEmptyAnonymizationKeys: Boolean)(implicit ec: ExecutionContext): Props = Props(new AnonymizationServiceActor(anonymizationDao, purgeEmptyAnonymizationKeys))
}
