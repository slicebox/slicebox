package se.vgregion.box

import scala.slick.driver.JdbcProfile
import org.h2.jdbc.JdbcSQLException
import scala.slick.jdbc.meta.MTable
import BoxProtocol._

class BoxDAO(val driver: JdbcProfile) {
  import driver.simple._

  case class ConfigRow(key: Long, config: BoxConfig, active: Boolean)

  val toConfigRow = (key: Long, name: String, url: String, active: Boolean) => ConfigRow(key, BoxConfig(name, url), active)
  val fromConfigRow = (row: ConfigRow) => Option((row.key, row.config.name, row.config.url, row.active))

  class ConfigTable(tag: Tag) extends Table[ConfigRow](tag, "BoxConfig") {
    def key = column[Long]("key", O.PrimaryKey, O.AutoInc)
    def name = column[String]("name")
    def url = column[String]("url")
    def active = column[Boolean]("active")
    def * = (key, name, url, active) <> (toConfigRow.tupled, fromConfigRow)
  }

  val configs = TableQuery[ConfigTable]

  def create(implicit session: Session) =
    if (MTable.getTables("BoxConfig").list.isEmpty) {
      configs.ddl.create
    }

  def insertConfig(config: BoxConfig, active: Boolean)(implicit session: Session) =
    configs += ConfigRow(-1, config, active)

  def removeConfig(config: BoxConfig)(implicit session: Session) =
    configs.filter(_.name === config.name).delete

  def listConfigs(implicit session: Session): List[BoxConfig] =
    configs.list.map(_.config)

}