package se.nimsa.sbx.util

import slick.backend.DatabaseConfig
import slick.dbio.{DBIO, DBIOAction, Effect, NoStream}
import slick.driver.JdbcProfile
import slick.jdbc.meta.MTable
import slick.lifted.{Query, TableQuery}
import slick.profile.RelationalProfile

import scala.concurrent.{ExecutionContext, Future}

object DbUtil {

  def createTables[P <: RelationalProfile](dbConf: DatabaseConfig[JdbcProfile], requiredTables: Seq[(String, TableQuery[_ <: P#Table[_]])])(implicit ec: ExecutionContext) = {

    import dbConf.driver.api._
    val db = dbConf.db

    db.run(MTable.getTables("%")).map(_.toList).flatMap { existingTables =>
      val tablesToCreate = requiredTables.filter(required => !existingTables.exists(_.name.name == required._1)).map(_._2)
      if (tablesToCreate.nonEmpty)
        db.run(tablesToCreate.map(_.schema).reduceLeft(_ ++ _).create)
      else
        Future.successful({})
    }
  }

  def columnExists(dbConf: DatabaseConfig[JdbcProfile], tableName: String, columnName: String)(implicit ec: ExecutionContext): Future[Boolean] = dbConf.db.run {
    MTable.getTables(tableName).flatMap(tables => tables.headOption
      .map(_.getColumns.map(columns => columns.exists(_.name == columnName)))
      .getOrElse(DBIO.successful(false)))
  }

  def checkColumnExists(dbConf: DatabaseConfig[JdbcProfile], maybeColumnName: Option[String], tableNames: String*)(implicit ec: ExecutionContext): Future[Unit] =
    maybeColumnName.map(columnName => checkColumnExists(dbConf, columnName, tableNames: _*)).getOrElse(Future.successful(Unit))

  def checkColumnExists(dbConf: DatabaseConfig[JdbcProfile], columnName: String, tableNames: String*)(implicit ec: ExecutionContext): Future[Unit] =
    Future.sequence(tableNames.map(name => columnExists(dbConf, name, columnName)))
      .map {
        exists =>
          if (!exists.contains(true))
            throw new IllegalArgumentException(s"Property $columnName does not exist")
      }

  implicit class ActionOptionAction[T, A <: Effect, B <: Effect](val self: DBIOAction[Option[DBIOAction[T, NoStream, B]], NoStream, A]) extends AnyVal {
    def unwrap(implicit ec: ExecutionContext): DBIOAction[Option[T], NoStream, A with B] =
      self.flatMap(oa => oa.map(_.map(Some(_))).getOrElse(DBIO.successful(None)))
  }

  implicit class OptionActionOption[T, A <: Effect](val self: Option[DBIOAction[Option[T], NoStream, A]]) extends AnyVal {
    def unwrap(implicit ec: ExecutionContext): DBIOAction[Option[T], NoStream, A] =
      self.getOrElse(DBIO.successful(None))
  }
}
