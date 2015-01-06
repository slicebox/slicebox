package se.vgregion.app

import scala.concurrent.Future
import scala.concurrent.duration.DurationInt
import scala.language.postfixOps
import akka.actor.ActorRefFactory
import akka.pattern.ask
import akka.util.Timeout
import UserRepositoryDbProtocol._
import se.vgregion.dicom.DicomDispatchProtocol.Initialized

class DbUserRepository(actorFactory: ActorRefFactory, dbProps: DbProps) extends UserRepository {

  val dbActor = actorFactory.actorOf(UserRepositoryDbActor.props(dbProps), "userRepositoryDb")

  implicit val timeout = Timeout(10 seconds)

  def initialize(): Future[Any] = dbActor.ask(Initialize)

  def userByName(name: String): Future[Option[ApiUser]] = dbActor.ask(GetUserByName(name)).mapTo[Option[ApiUser]]

  def addUser(user: ApiUser): Future[Option[ApiUser]] = dbActor.ask(AddUser(user)).mapTo[Option[ApiUser]]

  def deleteUser(userName: String): Future[Option[ApiUser]] = dbActor.ask(DeleteUser(userName)).mapTo[Option[ApiUser]]

  def listUserNames(): Future[List[String]] = dbActor.ask(GetUserNames).mapTo[List[String]]

}
