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

package se.nimsa.sbx.crypto

import akka.actor.Actor
import akka.actor.Props
import akka.event.Logging
import akka.event.LoggingReceive
import CryptoProtocol._
import scala.concurrent.Future
import akka.pattern.PipeToSupport

class CryptoServiceActor extends Actor with PipeToSupport {

  val log = Logging(context.system, this)

  implicit val system = context.system
  implicit val ec = context.dispatcher

  log.info("Crypto service started")

  def receive = LoggingReceive {
    case msg: CryptoRequest => msg match {
      case EncryptData(bytes, key) =>      
        Future {
          EncryptedData(bytes)
        }.pipeTo(sender)
      case DecryptData(bytes, key) =>      
        Future {
          DecryptedData(bytes)
        }.pipeTo(sender)
    }
  }

}

object CryptoServiceActor {
  def props(): Props = Props(new CryptoServiceActor)
}
