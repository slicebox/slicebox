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

package se.nimsa.sbx.scp

import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory
import akka.actor.Actor
import akka.actor.ActorRef
import akka.actor.PoisonPill
import akka.actor.Props
import akka.event.Logging
import akka.event.LoggingReceive
import java.util.Date
import se.nimsa.sbx.log.SbxLog
import se.nimsa.sbx.storage.StorageProtocol.DatasetReceived
import se.nimsa.sbx.app.GeneralProtocol._
import ScpProtocol._

class ScpActor(scpData: ScpData, executor: Executor) extends Actor {
  
  import context.system
  
  val log = Logging(context.system, this)

  val scheduledExecutor = Executors.newSingleThreadScheduledExecutor(new ThreadFactory() {
    override def newThread(runnable: Runnable): Thread = {
      val thread = Executors.defaultThreadFactory().newThread(runnable)
      thread.setDaemon(true)
      thread
    }
  })

  val scp = new Scp(scpData.name, scpData.aeTitle, scpData.port, self)
  scp.device.setScheduledExecutor(scheduledExecutor)
  scp.device.setExecutor(executor)
  scp.device.bindConnections()
  SbxLog.info("SCP", s"Started SCP ${scpData.name} with AE title ${scpData.aeTitle} on port ${scpData.port}")

  override def postStop() {
    scp.device.unbindConnections()
    scheduledExecutor.shutdown()
    SbxLog.info("SCP", s"Stopped SCP ${scpData.name}")
  }

  def receive = LoggingReceive {
    case DatasetReceivedByScp(dataset) =>
      log.debug("SCP", "Dataset received using SCP ${scpData.name}")
      context.system.eventStream.publish(DatasetReceived(dataset, Source(SourceType.SCP, scpData.name, scpData.id)))
  }

}

object ScpActor {
  def props(scpData: ScpData, executor: Executor): Props = Props(new ScpActor(scpData, executor))
}
