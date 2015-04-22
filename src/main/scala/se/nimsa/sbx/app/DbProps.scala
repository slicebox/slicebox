package se.nimsa.sbx.app

import scala.slick.jdbc.JdbcBackend.Database
import scala.slick.driver.JdbcProfile

case class DbProps(db: Database, driver: JdbcProfile)
