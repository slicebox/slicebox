package se.vgregion.app

object UserRepositoryDbProtocol {

  case object Initialize
  
  case class GetUserByName(name: String)
  
  case object GetUserNames
  
  case class AddUser(user: ApiUser)

  case class DeleteUser(userName: String)

  case object UserAlreadyAdded

}
