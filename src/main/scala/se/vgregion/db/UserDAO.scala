package se.vgregion.db

import scala.slick.driver.JdbcProfile
import se.vgregion.dicom.ScpProtocol.ScpData
import se.vgregion.app.ApiUser
import se.vgregion.app.Role

class UserDAO(val driver: JdbcProfile) {
  import driver.simple._

  case class UserRow(id: Long, user: ApiUser)

  val toRow = (id: Long, user: String, role: String, password: String) => UserRow(id, ApiUser(user, Role.valueOf(role), Some(password)))
  val fromRow = (row: UserRow) => Option((row.id, row.user.user, row.user.role.toString, row.user.hashedPassword.getOrElse("")))
  
  class UserTable(tag: Tag) extends Table[UserRow](tag, "User") {
    def id = column[Long]("id", O.PrimaryKey, O.AutoInc)
    def user = column[String]("user")
    def role = column[String]("role")
    def password = column[String]("password")
    def * = (id, user, role, password) <> (toRow.tupled, fromRow)
  }

  val users = TableQuery[UserTable]

  def create(implicit session: Session) =
    users.ddl.create

  def insert(apiUser: ApiUser)(implicit session: Session) =
    findByName(apiUser.user) match {
      case Some(user) => None
      case None =>
        apiUser.hashedPassword.map(password => {
            users += UserRow(-1, apiUser)
            apiUser
        })
    }

  def findByName(name: String)(implicit session: Session) =
    users.filter(_.user === name).firstOption.map(row => row.user)

  def removeByName(name: String)(implicit session: Session) =
    users.filter(_.user === name).delete

  def list(implicit session: Session): List[ApiUser] =
    users.list.map(row => row.user)

}