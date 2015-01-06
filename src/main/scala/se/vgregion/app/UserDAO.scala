package se.vgregion.app

import scala.slick.driver.JdbcProfile
import org.h2.jdbc.JdbcSQLException
import scala.slick.jdbc.meta.MTable

class UserDAO(val driver: JdbcProfile) {
  import driver.simple._

  case class UserRow(key: Long, user: ApiUser)

  val toRow = (key: Long, user: String, role: String, password: String) => UserRow(key, ApiUser(user, Role.valueOf(role), Some(password)))
  val fromRow = (row: UserRow) => Option((row.key, row.user.user, row.user.role.toString, row.user.hashedPassword.getOrElse("")))

  class UserTable(tag: Tag) extends Table[UserRow](tag, "User") {
    def key = column[Long]("key", O.PrimaryKey, O.AutoInc)
    def user = column[String]("user")
    def role = column[String]("role")
    def password = column[String]("password")
    def * = (key, user, role, password) <> (toRow.tupled, fromRow)
  }

  val users = TableQuery[UserTable]

  def create(implicit session: Session) =
    if (MTable.getTables("User").list.isEmpty) {
      users.ddl.create
    }

  def insert(apiUser: ApiUser)(implicit session: Session) =
    findUserByName(apiUser.user) match {
      case Some(user) => None
      case None =>
        apiUser.hashedPassword.map(password => {
          users += UserRow(-1, apiUser)
          apiUser
        })
    }

  def delete(userName: String)(implicit session: Session) = {
    val userOption = findUserByName(userName)
    userOption.foreach(user => users.filter(_.user === userName).delete)
    userOption
  }

  def findUserByName(name: String)(implicit session: Session) =
    users.filter(_.user === name).firstOption.map(row => row.user)

  def findKeyByName(name: String)(implicit session: Session) =
    users.filter(_.user === name).firstOption.map(row => row.key)

  def removeByName(name: String)(implicit session: Session) =
    users.filter(_.user === name).delete

  def listUserNames(implicit session: Session): List[String] =
    users.list.map(row => row.user.user)

  def list(implicit session: Session): List[ApiUser] =
    users.list.map(row => row.user)

}