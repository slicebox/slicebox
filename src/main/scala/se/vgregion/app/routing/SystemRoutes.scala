package se.vgregion.app.routing

import scala.concurrent.duration.DurationInt

import akka.actor.ActorContext

import spray.routing.Route

import se.vgregion.app.AuthInfo
import se.vgregion.app.RestApi
import se.vgregion.app.UserProtocol.UserRole

trait SystemRoutes { this: RestApi =>

  def systemRoutes(authInfo: AuthInfo): Route =
    path("stop") {
      (post | parameter('method ! "post")) {
        authorize(authInfo.hasPermission(UserRole.ADMINISTRATOR)) {
          complete {
            val system = actorRefFactory.asInstanceOf[ActorContext].system
            system.scheduler.scheduleOnce(1.second)(system.shutdown())(system.dispatcher)
            "Shutting down in 1 second..."
          }
        }
      }
    }

}