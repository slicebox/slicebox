package se.vgregion.box

import scala.slick.driver.JdbcProfile
import org.h2.jdbc.JdbcSQLException
import scala.slick.jdbc.meta.MTable

class BoxDAO(val driver: JdbcProfile) {
  import driver.simple._

  case class ConfigRow(key: Long, config: BoxConfig)

  val toRow = (key: Long, name: String, url: String) => ConfigRow(key, BoxConfig(name, url))
  val fromRow = (row: ConfigRow) => Option((row.key, row.config.name, row.config.url))

  class ConfigTable(tag: Tag) extends Table[ConfigRow](tag, "BoxConfig") {
    def key = column[Long]("key", O.PrimaryKey, O.AutoInc)
    def name = column[String]("name")
    def url = column[String]("url")
    def * = (key, name, url) <> (toRow.tupled, fromRow)
  }

  val configs = TableQuery[ConfigTable]

  class TokenTable(tag: Tag) extends Table[(Long, String)](tag, "Token") {
    def key = column[Long]("key", O.PrimaryKey, O.AutoInc)
    def token = column[String]("token")
    def * = (key, token)
  }

  val tokens = TableQuery[TokenTable]

  def create(implicit session: Session) =
    if (MTable.getTables("BoxConfig").list.isEmpty) {
      (configs.ddl ++ tokens.ddl).create
    }

  def insertConfig(config: BoxConfig)(implicit session: Session) =
    configs += ConfigRow(-1, config)

  def removeConfig(config: BoxConfig)(implicit session: Session) =
    configs.filter(_.name === config.name).delete

  def listConfigs(implicit session: Session): List[BoxConfig] =
    configs.list.map(_.config)

  def insertToken(token: String)(implicit session: Session) =
    tokens += ((-1, token))

  def removeToken(token: String)(implicit session: Session) =
    tokens.filter(_.token === token).delete

  def listTokens(implicit session: Session): List[String] =
    tokens.list.map(_._2)

}