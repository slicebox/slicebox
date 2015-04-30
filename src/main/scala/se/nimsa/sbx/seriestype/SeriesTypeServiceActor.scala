package se.nimsa.sbx.seriestype

import SeriesTypeProtocol._
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
            
          case AddSeriesType(seriesType) =>
            val dbSeriesType = addSeriesTypeToDb(seriesType)
            sender ! SeriesTypeAdded(dbSeriesType)
            
          case UpdateSeriesType(seriesType) =>
            val dbSeriesType = updateSeriesTypeInDb(seriesType)
            sender ! SeriesTypeUpdated
            
          case RemoveSeriesType(seriesTypeId) =>
            removeSeriesTypeFromDb(seriesTypeId)
            sender ! SeriesTypeRemoved(seriesTypeId)
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
  
  def addSeriesTypeToDb(seriesType: SeriesType): SeriesType =
    db.withSession { implicit session =>
      seriesTypeDao.insertSeriesType(seriesType)
    }
  
  def updateSeriesTypeInDb(seriesType: SeriesType): Unit =
    db.withSession { implicit session =>
      seriesTypeDao.updateSeriesType(seriesType)
    }
  
  def removeSeriesTypeFromDb(seriesTypeId: Long): Unit =
    db.withSession { implicit session =>
      seriesTypeDao.removeSeriesType(seriesTypeId)
    }
}


object SeriesTypeServiceActor {
  def props(dbProps: DbProps): Props = Props(new SeriesTypeServiceActor(dbProps))
}