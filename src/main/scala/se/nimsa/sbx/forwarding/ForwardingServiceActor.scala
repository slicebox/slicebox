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

package se.nimsa.sbx.forwarding

import ForwardingProtocol._
import akka.actor.Actor
import akka.actor.Props
import akka.event.Logging
import akka.event.LoggingReceive
import akka.util.Timeout
import akka.pattern.ask
import se.nimsa.sbx.app.DbProps
import se.nimsa.sbx.dicom.DicomHierarchy._
import se.nimsa.sbx.storage.StorageProtocol._
import scala.concurrent.Future

class ForwardingServiceActor(dbProps: DbProps)(implicit timeout: Timeout) extends Actor {

  val log = Logging(context.system, this)

  implicit val system = context.system
  implicit val ec = context.dispatcher

  val db = dbProps.db
  val forwardingDao = new ForwardingDAO(dbProps.driver)

  val storageService = context.actorSelection("../StorageService")

  override def preStart {
    context.system.eventStream.subscribe(context.self, classOf[ImageAdded])
  }

  log.info("Forwarding service started")

  def receive = LoggingReceive {

    case ImageAdded(image) =>
      maybeForwardImage(image)
      
    case msg: ForwardingRequest =>

      msg match {
        case GetForwardingRules =>
            val forwardingRules = getForwardingRulesFromDb()
            sender ! ForwardingRules(forwardingRules)

          case AddForwardingRule(forwardingRule) =>
            val dbForwardingRule = addForwardingRuleToDb(forwardingRule)
            sender ! ForwardingRuleAdded(dbForwardingRule)

          case RemoveForwardingRule(forwardingRuleId) =>
            removeForwardingRuleFromDb(forwardingRuleId)
            sender ! ForwardingRuleRemoved(forwardingRuleId)
          
      }
  }

  def getForwardingRulesFromDb() =
    db.withSession { implicit session =>
      forwardingDao.listForwardingRules
    }

  def addForwardingRuleToDb(forwardingRule: ForwardingRule): ForwardingRule =
    db.withSession { implicit session =>
      forwardingDao.insertForwardingRule(forwardingRule)
    }

  def removeForwardingRuleFromDb(forwardingRuleId: Long): Unit =
    db.withSession { implicit session =>
      forwardingDao.removeForwardingRule(forwardingRuleId)
    }

  def maybeForwardImage(image: Image): Unit = {
    // get source of image
//    val futureImageFileMaybe = storageService.ask(GetImageFile(image.id)).mapTo[Option[ImageFile]]
//    futureImageFileMaybe.foreach(_.map(imageFile => {
//      val sourceTypeId = imageFile.sourceTypeId
//    }))
    
    // check if there is a forwarding rule for this source, if not exit
    // if source is box, check if end of transaction, if not add to forwarding transaction and exit, if it is send transaction
    // if source is not box, add to forwarding transaction
  }
  
//  def forwardingRuleBySourceTypeAndId(sourceTypeId: SourceTypeId): Option[ForwardingRule] =
//    db.withSession { implicit session =>
//      //forwardingDao.forwardingRuleBySouceTypeAndId(sourceTypeId)
//      null
//    }
    
  // TODO change
  def getAllSeries(): Future[Seq[Series]] =
    storageService.ask(GetAllSeries).mapTo[SeriesCollection].map(_.series)

}

object ForwardingServiceActor {
  def props(dbProps: DbProps, timeout: Timeout): Props = Props(new ForwardingServiceActor(dbProps)(timeout))
}
