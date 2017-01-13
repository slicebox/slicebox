package se.nimsa.sbx.util

import slick.backend.DatabaseConfig
import slick.dbio.{DBIO, DBIOAction, NoStream}
import slick.driver.JdbcProfile
import slick.jdbc.meta.MTable
import slick.lifted.TableQuery

import scala.concurrent.Future

object DbUtil {

  def createTables(dbConf: DatabaseConfig[JdbcProfile], requiredTables: Seq[(String, TableQuery[_])]) = {

    import dbConf.driver.api._
    val db = dbConf.db

    db.run(MTable.getTables("%")).map(_.toList).flatMap { existingTables =>
      val tablesToCreate = requiredTables.filter(required => !existingTables.exists(_.name.name == required._1)).map(_._2)
      if (tablesToCreate.nonEmpty)
        db.run(tablesToCreate.map(t => tableQueryToTableQueryExtensionMethods(t).schema).reduceLeft(_ ++ _).create)
      else
        Future.successful({})
    }
  }

  def columnExists(dbConf: DatabaseConfig[JdbcProfile], tableName: String, columnName: String): Future[Boolean] = dbConf.db.run {
    MTable.getTables(tableName).flatMap { tables =>
      if (tables.isEmpty)
        DBIO.successful(false)
      else
        tables.head.getColumns.map(columns => columns.exists(_.name == columnName))
    }
  }

  def checkColumnExists(dbConf: DatabaseConfig[JdbcProfile], maybeColumnName: Option[String], tableNames: String*): Future[Unit] =
    maybeColumnName.map(columnName => checkColumnExists(dbConf, columnName, tableNames: _*)).getOrElse(Future.successful(Unit))

  def checkColumnExists(dbConf: DatabaseConfig[JdbcProfile], columnName: String, tableNames: String*): Future[Unit] =
    Future.sequence(tableNames.map(name => columnExists(dbConf, name, columnName)))
      .map { exists =>
        if (!exists.contains(true))
          throw new IllegalArgumentException(s"Property $columnName does not exist")
      }

  implicit class ActionOptionAction[T](val self: DBIOAction[Option[DBIOAction[T, NoStream, _]], NoStream, _]) extends AnyVal {
    def unwrap: DBIOAction[Option[T], NoStream, _] =
      self.flatMap(of => of.map(_.map(Some(_))).getOrElse(DBIO.successful(None)))
  }

  implicit class OptionActionOption[T](val self: Option[DBIOAction[Option[T], NoStream, _]]) extends AnyVal {
    def unwrap: DBIOAction[Option[T], NoStream, _] =
      self.getOrElse(DBIO.successful(None))
  }

}
