package se.vgregion.dicom

import akka.actor.Actor
import akka.event.{ LoggingReceive, Logging }
import StoreScpProtocol._
import java.util.concurrent.Executors
import java.io.File
import java.util.concurrent.ScheduledExecutorService
import akka.actor.Props

class StoreScpCollectionActor extends Actor {
  val log = Logging(context.system, this)

  val executor = Executors.newCachedThreadPool()

  override def postStop() {
    executor.shutdown()
  }

  var dataCollection = List.empty[StoreScpData]

  def receive = LoggingReceive {
    case AddStoreScp(storeScpData: StoreScpData) =>
      val scpActor = context.actorOf(Props[StoreScpActor])
      scpActor ! AddStoreScpWithExecutor(storeScpData, executor)
    case StoreScpAdded(storeScpData) =>
      dataCollection = dataCollection :+ storeScpData
    case GetStoreScpDataCollection =>
      sender ! StoreScpDataCollection(dataCollection)
  }
}