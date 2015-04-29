package se.nimsa.sbx.seriestype

import SeriesTypeProtocol.GetSeriesTypes
import SeriesTypeProtocol.SeriesType
import SeriesTypeProtocol.SeriesTypeRequest
import SeriesTypeProtocol.SeriesTypes
import akka.actor.Actor
import akka.actor.Props
import akka.actor.Stash
import akka.actor.actorRef2Scala
import akka.event.LoggingReceive
import se.nimsa.sbx.app.DbProps
import se.nimsa.sbx.util.ExceptionCatching
import se.nimsa.sbx.util.SequentialPipeToSupport

class SeriesTypeServiceActor(dbProps: DbProps) extends Actor with Stash
  with SequentialPipeToSupport with ExceptionCatching {
  
  val db = dbProps.db
  val seriesTypeDao = new SeriesTypeDAO(dbProps.driver)
  
  setupDb()

  def receive = LoggingReceive {
    
    case msg: SeriesTypeRequest =>

      catchAndReport {

        msg match {

          case GetSeriesTypes =>
            val seriesTypes = getSeriesTypesFromDb()
            sender ! SeriesTypes(seriesTypes)
        }
      }
  }
  
  def setupDb(): Unit =
    db.withSession { implicit session =>
      seriesTypeDao.create
    }

  def teardownDb(): Unit =
    db.withSession { implicit session =>
      seriesTypeDao.drop
    }
  
  def getSeriesTypesFromDb(): Seq[SeriesType] =
    db.withSession { implicit session =>
      seriesTypeDao.listSeriesTypes
    }
}


object SeriesTypeServiceActor {
  def props(dbProps: DbProps): Props = Props(new SeriesTypeServiceActor(dbProps))
}