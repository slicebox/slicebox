/*
 * Copyright 2014 Lars Edenbrandt
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package se.nimsa.sbx.util

import slick.basic.DatabaseConfig
import slick.dbio.{DBIO, DBIOAction, Effect, NoStream}
import slick.jdbc.JdbcProfile
import slick.jdbc.meta.MTable
import slick.lifted.TableQuery
import slick.relational.RelationalProfile

import scala.concurrent.{ExecutionContext, Future}

object DbUtil {

  def createTables[P <: RelationalProfile](dbConf: DatabaseConfig[JdbcProfile], requiredTables: (String, TableQuery[_ <: P#Table[_]])*)(implicit ec: ExecutionContext) = {

    import dbConf.profile.api.{tableQueryToTableQueryExtensionMethods, schemaActionExtensionMethods}

    dbConf.db.run {
      MTable.getTables("%").flatMap { existingTables =>
        val tablesToCreate = requiredTables.filter(required => !existingTables.exists(_.name.name.equalsIgnoreCase(required._1))).map(_._2)
        if (tablesToCreate.nonEmpty)
          tablesToCreate.map(_.schema).reduceLeft(_ ++ _).create
        else
          DBIO.successful({})
      }
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
    def unwrap: DBIOAction[Option[T], NoStream, A] =
      self.getOrElse(DBIO.successful(None))
  }

}
