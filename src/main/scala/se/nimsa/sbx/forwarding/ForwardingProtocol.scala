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

import se.nimsa.sbx.storage.StorageProtocol._
import se.nimsa.sbx.app.GeneralProtocol._
import se.nimsa.sbx.model.Entity
import se.nimsa.sbx.dicom.DicomHierarchy.Image
import akka.actor.ActorRef

object ForwardingProtocol {

  case class ForwardingRule(id: Long, source: Source, destination: Destination, keepImages: Boolean) extends Entity
  
  case class ForwardingTransaction(id: Long, forwardingRuleId: Long, lastUpdated: Long, enroute: Boolean, delivered: Boolean)
  
  case class ForwardingTransactionImage(id: Long, forwardingTransactionId: Long, imageId: Long)
  
  
  sealed trait ForwardingRequest

  case object GetForwardingRules extends ForwardingRequest

  case class AddForwardingRule(forwardingRule: ForwardingRule) extends ForwardingRequest
  
  case class RemoveForwardingRule(forwardingRuleId: Long) extends ForwardingRequest
  
  
  case class ForwardingRules(forwardingRules: List[ForwardingRule])
  
  case class ForwardingRuleAdded(forwardingRule: ForwardingRule)
  
  case class ForwardingRuleRemoved(forwardingRuleId: Long)
  
  
  case object PollForwardingQueue
  
  case object FinalizeSentTransactions

  case class UpdateTransaction(forwardingTransaction: ForwardingTransaction, enroute: Boolean, delivered: Boolean)

  case class MakeTransfer(forwardingRule: ForwardingRule, forwardingTransaction: ForwardingTransaction)

  case class MaybeAddImageToForwardingQueue(image: Image, sourceTypeAndId: SourceTypeId, origin: ActorRef)
  
  
  case class ImagesAddedToForwardingQueue(transactionImages: List[ForwardingTransactionImage])
  
  case class TransactionsEnroute(transactions: List[ForwardingTransaction])
  
  case class TransactionMarkedAsDelivered(transactionMaybe: Option[ForwardingTransaction])
  
  case class TransactionsFinalized(transactionsToRemove: List[ForwardingTransaction], idsOfDeletedImages: List[Long])
}
