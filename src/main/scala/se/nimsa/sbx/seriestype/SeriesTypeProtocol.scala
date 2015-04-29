package se.nimsa.sbx.seriestype

object SeriesTypeProtocol {

  import se.nimsa.sbx.model.Entity
  
  case class SeriesType(id: Long, name: String) extends Entity
  
  case class SeriesTypeRule(id: Long, seriesTypeId: Long) extends Entity
  
  case class SeriesTypeRuleAttribute(id: Long, seriesTypeRuleId: Long, group: Int, element: Int, path: Option[String]) extends Entity
  
  
  case class SeriesTypes(seriesTypes: Seq[SeriesType])
  
  
  sealed trait SeriesTypeRequest
  
  case object GetSeriesTypes extends SeriesTypeRequest
}