package se.nimsa.sbx.seriestype

import java.util.Objects
import scala.collection.mutable.ArrayBuffer
import org.dcm4che3.data.Attributes
import org.dcm4che3.util.TagUtils
import SeriesTypeProtocol._
import akka.actor.Actor
import akka.actor.Props
import akka.actor.Stash
import akka.event.Logging
import akka.event.LoggingReceive
import se.nimsa.sbx.app.DbProps
import se.nimsa.sbx.dicom.DicomHierarchy._
import se.nimsa.sbx.storage.MetaDataDAO
import se.nimsa.sbx.storage.PropertiesDAO
import se.nimsa.sbx.storage.StorageProtocol._
import se.nimsa.sbx.util.ExceptionCatching
import se.nimsa.sbx.log.SbxLog
import se.nimsa.sbx.dicom.DicomUtil

class SeriesTypeUpdateActor(dbProps: DbProps) extends Actor with Stash with ExceptionCatching {

  val log = Logging(context.system, this)

  implicit val system = context.system

  val db = dbProps.db

  val metaDataDao = new MetaDataDAO(dbProps.driver)
  val propertiesDao = new PropertiesDAO(dbProps.driver)
  val seriesTypeDao = new SeriesTypeDAO(dbProps.driver)

  val storageService = context.actorSelection("../StorageService")

  setupDb()

  val seriesTypesUpdateQueue: ArrayBuffer[Series] = ArrayBuffer()

  log.info("Series type update service started")

  self ! UpdateSeriesTypesForAllSeries

  def receive = LoggingReceive {

    case msg: SeriesTypesUpdateRequest =>

      catchAndReport {

        msg match {

          case UpdateSeriesTypesForSeries(seriesId) =>
            SbxLog.info("Series Types", s"Starting update for series id ${seriesId}")
            updateSeriesTypesForSeriesWithId(seriesId)

          case UpdateSeriesTypesForAllSeries =>
            if (seriesTypesUpdateQueue.isEmpty) {
              SbxLog.info("Series Types", "Starting update for all series")
            }
            updateSeriesTypesForAllSeries()

          case GetUpdateSeriesTypesRunningStatus =>
            sender ! UpdateSeriesTypesRunningStatus(seriesTypesUpdateQueue.size > 0)

        }
      }

    case PollSeriesTypesUpdateQueue => pollSeriesTypesUpdateQueue()
  }

  def waitForDataset(series: Series): PartialFunction[Any, Unit] = LoggingReceive {
    case Some(dataset: Attributes) =>
      handleLoadedDataset(dataset, series)
      finishSeriesSeriesTypesUpdate()

    case None =>
      finishSeriesSeriesTypesUpdate()

    case PollSeriesTypesUpdateQueue => // Ignore since we only update one series at a time

    case GetUpdateSeriesTypesRunningStatus =>
      sender ! UpdateSeriesTypesRunningStatus(true)

    case msg =>
      stash()
  }

  def finishSeriesSeriesTypesUpdate() = {
    context.unbecome()
    self ! PollSeriesTypesUpdateQueue
    unstashAll()
  }

  def updateSeriesTypesForSeriesWithId(seriesId: Long) =
    getSeriesFromDb(seriesId).foreach { series =>
      seriesTypesUpdateQueue += series
      self ! PollSeriesTypesUpdateQueue
    }

  def updateSeriesTypesForAllSeries() = {
    seriesTypesUpdateQueue ++= getAllSeriesFromDb()
    self ! PollSeriesTypesUpdateQueue
  }

  def pollSeriesTypesUpdateQueue() = {
    if (seriesTypesUpdateQueue.isEmpty) {
      SbxLog.info("Series Types", "Finished update")
    }

    seriesTypesUpdateQueue.headOption.foreach { series =>
      seriesTypesUpdateQueue -= series
      updateSeriesTypesForSeries(series)
    }
  }

  def updateSeriesTypesForSeries(series: Series) = {
    removeAllSeriesTypesForSeriesFromDb(series)

    val imageIdOpt = getImageIdForSeries(series)

    imageIdOpt match {
      case Some(imageId) =>
        context.become(waitForDataset(series))
        storageService ! GetDataset(imageId)

      case None =>
        self ! PollSeriesTypesUpdateQueue
    }
  }

  def handleLoadedDataset(dataset: Attributes, series: Series) = {
    val allSeriesTypes = db.withSession { implicit session =>
      seriesTypeDao.listSeriesTypes
    }

    updateSeriesTypesForDataset(dataset, series, allSeriesTypes)
  }

  def updateSeriesTypesForDataset(dataset: Attributes, series: Series, allSeriesTypes: Seq[SeriesType]): Unit = {
    allSeriesTypes.foreach { seriesType =>
      evaluateSeriesTypeForSeries(seriesType, dataset, series)
    }
  }

  def evaluateSeriesTypeForSeries(seriesType: SeriesType, dataset: Attributes, series: Series) = {
    val rules = getSeriesTypeRulesFromDb(seriesType)

    val evaluationResult = rules.foldLeft(false) { (acc, rule) =>
      if (acc) {
        true // A previous rule already matched the dataset so no need to evaluate more rules
      } else {
        evaluateRuleForSeries(rule, dataset, series)
      }
    }

    if (evaluationResult) {
      addSeriesTypeForSeriesToDb(seriesType, series)
    }
  }

  def evaluateRuleForSeries(rule: SeriesTypeRule, dataset: Attributes, series: Series): Boolean = {
    val attributes = getSeriesTypeRuleAttributesFromDb(rule)

    attributes.foldLeft(true) { (acc, attribute) =>
      if (!acc) {
        false // A previous attribute already failed matching the dataset so no need to evaluate more attributes
      } else {
        evaluateRuleAttributeForToSeries(attribute, dataset, series)
      }
    }
  }

  def evaluateRuleAttributeForToSeries(attribute: SeriesTypeRuleAttribute, dataset: Attributes, series: Series): Boolean = {
    val attrs = attribute.tagPath.map(pathString => {
      try {
        val pathTags = pathString.split(",").map(_.toInt)
        pathTags.foldLeft(dataset)((nested, tag) => nested.getNestedDataset(tag))
      } catch {
        case e: Exception =>
          dataset
      }
    }).getOrElse(dataset)
    DicomUtil.concatenatedStringForTag(attrs, attribute.tag) == attribute.values
  }

  def getSeriesFromDb(seriesId: Long): Option[Series] =
    db.withSession { implicit session =>
      metaDataDao.seriesById(seriesId)
    }

  def getAllSeriesFromDb(): List[Series] =
    db.withSession { implicit session =>
      metaDataDao.series
    }

  def getImageIdForSeries(series: Series): Option[Long] =
    db.withSession { implicit session =>
      metaDataDao.imagesForSeries(0, 1, series.id)
    }
      .headOption.map(_.id)

  def getSeriesTypeRulesFromDb(seriesType: SeriesType) =
    db.withSession { implicit session =>
      seriesTypeDao.listSeriesTypeRulesForSeriesTypeId(seriesType.id)
    }

  def getSeriesTypeRuleAttributesFromDb(seriesTypeRule: SeriesTypeRule) =
    db.withSession { implicit session =>
      seriesTypeDao.listSeriesTypeRuleAttributesForSeriesTypeRuleId(seriesTypeRule.id)
    }

  def addSeriesTypeForSeriesToDb(seriesType: SeriesType, series: Series) =
    db.withSession { implicit session =>
      seriesTypeDao.insertSeriesSeriesType(SeriesSeriesType(series.id, seriesType.id))
    }

  def removeAllSeriesTypesForSeriesFromDb(series: Series) =
    db.withSession { implicit session =>
      seriesTypeDao.removeSeriesTypesForSeriesId(series.id)
    }

  def setupDb(): Unit =
    db.withSession { implicit session =>
      seriesTypeDao.create
    }
}

object SeriesTypeUpdateActor {
  def props(dbProps: DbProps): Props = Props(new SeriesTypeUpdateActor(dbProps))
}