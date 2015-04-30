package se.nimsa.sbx.seriestype

object SeriesTypeProtocol {

  import se.nimsa.sbx.model.Entity
  
  case class SeriesType(id: Long, name: String) extends Entity
  
  case class SeriesTypeRule(id: Long, seriesTypeId: Long) extends Entity
  
  case class SeriesTypeRuleAttribute(
      id: Long, seriesTypeRuleId: Long,
      group: Int,
      element: Int,
      path: Option[String],
      value: String) extends Entity
  
  
  case class SeriesTypes(seriesTypes: Seq[SeriesType])
  
  
  sealed trait SeriesTypeRequest
  
  case object GetSeriesTypes extends SeriesTypeRequest
  
  case class AddSeriesType(seriesType: SeriesType) extends SeriesTypeRequest
  
  case class UpdateSeriesType(seriesType: SeriesType) extends SeriesTypeRequest
  
  case class RemoveSeriesType(seriesTypeId: Long) extends SeriesTypeRequest
  
  case class SeriesTypeAdded(seriesType: SeriesType)
  
  case object SeriesTypeUpdated
  
  case class SeriesTypeRemoved(seriesTypeId: Long)
}