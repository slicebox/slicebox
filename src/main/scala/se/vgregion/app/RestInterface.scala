package se.vgregion.app

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import scala.concurrent.duration.DurationInt
import scala.slick.driver.H2Driver
import scala.slick.jdbc.JdbcBackend.Database
import akka.actor.Actor
import akka.actor.ActorContext
import akka.pattern.ask
import akka.util.Timeout
import spray.http.StatusCodes.Forbidden
import spray.http.StatusCodes.BadRequest
import spray.http.StatusCodes.OK
import spray.httpx.PlayTwirlSupport.twirlHtmlMarshaller
import spray.httpx.SprayJsonSupport._
import spray.httpx.marshalling.ToResponseMarshallable.isMarshallable
import spray.routing._
import com.typesafe.config.ConfigFactory
import se.vgregion.box.BoxProtocol._
import se.vgregion.box.BoxServiceActor
import se.vgregion.dicom.DicomDispatchActor
import se.vgregion.dicom.DicomHierarchy._
import se.vgregion.dicom.DicomProtocol._
import spray.http.MultipartFormData
import se.vgregion.dicom.DicomUtil
import spray.http.FormFile
import spray.http.HttpEntity
import spray.http.HttpData
import spray.http.ContentTypes

class RestInterface extends Actor with RestApi {

  def actorRefFactory = context

  def dbUrl = "jdbc:h2:storage"

  def createStorageDirectory = {
    val storagePath = Paths.get(sliceboxConfig.getString("storage"))
    if (!Files.exists(storagePath))
      Files.createDirectories(storagePath)
    if (!Files.isDirectory(storagePath))
      throw new IllegalArgumentException("Storage directory is not a directory.")
    storagePath
  }

  def receive = runRoute(routes)

}

trait RestApi extends HttpService with JsonFormats {

  implicit def executionContext = actorRefFactory.dispatcher

  implicit val timeout = Timeout(10.seconds)

  val config = ConfigFactory.load()
  val sliceboxConfig = config.getConfig("slicebox")

  def createStorageDirectory(): Path
  def dbUrl(): String

  def db = Database.forURL(dbUrl,
    user = sliceboxConfig.getString("db.user"),
    password = sliceboxConfig.getString("db.password"),
    driver = "org.h2.Driver")
  val dbProps = DbProps(db, H2Driver)

  val storage = createStorageDirectory()

  val userService = new DbUserRepository(actorRefFactory, dbProps)
  val boxService = actorRefFactory.actorOf(BoxServiceActor.props(dbProps, config.getString("http.host"), config.getInt("http.port")), "BoxService")
  val dicomService = actorRefFactory.actorOf(DicomDispatchActor.props(storage, dbProps), "DicomDispatch")

  val authenticator = new Authenticator(userService)

  implicit def sliceboxExceptionHandler =
    ExceptionHandler {
      case e: IllegalArgumentException =>
        complete((BadRequest, "Illegal arguments: " + e.getMessage()))
    }

  def staticResourcesRoutes =
    get {
      pathPrefix("assets") {
        path(Rest) { path =>
          getFromResource("public/" + path)
        }
      }
    }

  def angularRoutes =
    get {
      pathPrefix("") {
        complete(views.html.index())
      }
    }

  def directoryRoutes: Route =
    pathPrefix("directory") {
      post {
        entity(as[WatchDirectory]) { directory =>
          onSuccess(dicomService.ask(directory)) {
            case DirectoryWatched(path) =>
              complete("Now watching directory " + path)
          }
        }
      } ~ path(LongNumber) { watchDirectoryId =>
        pathEnd {
          delete {
            onSuccess(dicomService.ask(UnWatchDirectory(watchDirectoryId))) {
              case DirectoryUnwatched(watchedDirectoryId) =>
                complete("Stopped watching directory " + watchedDirectoryId)
            }
          }
        }
      } ~ get {
        path("list") {
          onSuccess(dicomService.ask(GetWatchedDirectories)) {
            case WatchedDirectories(list) =>
              complete(list)
          }
        }
      }
    }

  def scpRoutes: Route =
    pathPrefix("scp") {
      post {
        pathEnd {
          entity(as[ScpData]) { scpData =>
            onSuccess(dicomService.ask(AddScp(scpData))) {
              case ScpAdded(scpData) =>
                complete("Added SCP " + scpData.name)
            }
          }
        }
      } ~ path(LongNumber) { scpDataId =>
        pathEnd {
          delete {
            pathEnd {
              onSuccess(dicomService.ask(RemoveScp(scpDataId))) {
                case ScpRemoved(scpDataId) =>
                  complete("Removed SCP " + scpDataId)
              }
            }
          }
        }
      } ~ get {
        path("list") {
          onSuccess(dicomService.ask(GetScpDataCollection)) {
            case ScpDataCollection(list) =>
              complete(list)
          }
        }
      }
    }

  def metaDataRoutes: Route = {
    pathPrefix("metadata") {
      get {
        path("patients") {
          onSuccess(dicomService.ask(GetPatients)) {
            case Patients(patients) =>
              complete(patients)
          }
        } ~ path("studies") {
          parameters('patientId.as[Long]) { patientId =>
            onSuccess(dicomService.ask(GetStudies(patientId))) {
              case Studies(studies) =>
                complete(studies)
            }
          }
        } ~ path("series") {
          parameters('studyId.as[Long]) { studyId =>
            onSuccess(dicomService.ask(GetSeries(studyId))) {
              case SeriesCollection(series) =>
                complete(series)
            }
          }
        } ~ path("images") {
          parameters('seriesId.as[Long]) { seriesId =>
            onSuccess(dicomService.ask(GetImages(seriesId))) {
              case Images(images) =>
                complete(images)
            }
          }
        } ~ path("allimages") {
          onSuccess(dicomService.ask(GetAllImages)) {
            case Images(images) =>
              complete(images)
          }
        }
      }
    }
  }

  def datasetRoutes: Route =
    pathPrefix("dataset") {
      post {
        pathEnd {
          // TODO allow with token
          formField('file.as[FormFile]) { file =>
            val dataset = DicomUtil.loadDataset(file.entity.data.toByteArray, true)
            onSuccess(dicomService.ask(AddDataset(dataset))) {
              case ImageAdded(image) =>
                complete("Dataset received, added image with id " + image.id)
            }
          }
        }
      } ~ get {
        path(LongNumber) { imageId =>

          onSuccess(dicomService.ask(GetImageFiles(imageId))) {
            case ImageFiles(imageFiles) =>
              imageFiles.headOption match {
                case Some(imageFile) =>
                  val file = storage.resolve(imageFile.fileName.value).toFile
                  if (file.isFile && file.canRead)
                    detach() {
                      complete(HttpEntity(ContentTypes.`application/octet-stream`, HttpData(file)))
                    }
                  else
                    complete((BadRequest, "Dataset could not be read"))
                case None =>
                  complete((BadRequest, "Dataset not found"))
              }
          }
        }
      }
    }

  def boxRoutes: Route =
    pathPrefix("box") {
      pathPrefix("server") {
        path("list") {
          get {
            onSuccess(boxService.ask(GetBoxServers)) {
              case BoxServers(servers) =>
                complete(servers)
            }
          }
        } ~ pathEnd {
          post {
            entity(as[BoxServerName]) { boxName =>
              onSuccess(boxService.ask(CreateBoxServer(boxName.name))) {
                case BoxServerCreated(server) =>
                  complete(s"Created box server ${server.name}")
              }
            }
          }
        } ~ path(LongNumber) { serverId =>
          delete {
            pathEnd {
              onSuccess(boxService.ask(RemoveBoxServer(serverId))) {
                case BoxServerRemoved(serverId) =>
                  complete(s"Removed box server with id $serverId")
              }
            }
          }
        }
      } ~ pathPrefix("client") {
        path("list") {
          get {
            onSuccess(boxService.ask(GetBoxClients)) {
              case BoxClients(clients) =>
                complete(clients)
            }
          }
        } ~ pathEnd {
          post {
            entity(as[BoxClientConfig]) { client =>
              onSuccess(boxService.ask(AddBoxClient(client))) {
                case BoxClientAdded(client) =>
                  complete(s"Added box client ${client.name}")
              }
            }
          }
        } ~ path(LongNumber) { clientId =>
          delete {
            pathEnd {
              onSuccess(boxService.ask(RemoveBoxClient(clientId))) {
                case BoxClientRemoved(clientId) =>
                  complete(s"Removed box client with id $clientId")
              }
            }
          }
        }
      } ~ tokenRoutes
    }

  def tokenRoutes: Route =
    pathPrefix(Segment) { token =>
      onSuccess(boxService.ask(ValidateToken(token))) {
        case InvalidToken(token) =>
          complete((Forbidden, "Invalid token"))
        case ValidToken(token) =>
          pathPrefix("dataset") {
            pathEnd {
              post {
                // TODO allow with token
                formField('file.as[FormFile]) { file =>
                  val dataset = DicomUtil.loadDataset(file.entity.data.toByteArray, true)
                  onSuccess(dicomService.ask(AddDataset(dataset))) {
                    case ImageAdded(image) =>
                      complete("Dataset received, added image with id " + image.id)
                  }
                }
              }
            } ~ path(LongNumber) { imageId =>
              get {
                onSuccess(dicomService.ask(GetImageFiles(imageId))) {
                  case ImageFiles(imageFiles) =>
                    imageFiles.headOption match {
                      case Some(imageFile) =>
                        val file = storage.resolve(imageFile.fileName.value).toFile
                        if (file.isFile && file.canRead)
                          detach() {
                            complete(HttpEntity(ContentTypes.`application/octet-stream`, HttpData(file)))
                          }
                        else
                          complete((BadRequest, "Dataset could not be read"))
                      case None =>
                        complete((BadRequest, "Dataset not found"))
                    }
                }
              }
            }
          }
      }
    }

  def userRoutes: Route =
    pathPrefix("user") {
      pathEnd {
        post {
          entity(as[ClearTextUser]) { user =>
            val apiUser = ApiUser(user.user, user.role).withPassword(user.password)
            onSuccess(userService.addUser(apiUser)) {
              _ match {
                case Some(newUser) =>
                  complete(s"Added user ${newUser.user}")
                case None =>
                  complete((BadRequest, s"User ${user.user} already exists."))
              }
            }
          }
        } ~ delete {
          entity(as[String]) { userName =>
            onSuccess(userService.deleteUser(userName)) {
              _ match {
                case Some(deletedUser) =>
                  complete(s"Deleted user ${deletedUser.user}")
                case None =>
                  complete((BadRequest, s"User ${userName} does not exist."))
              }
            }
          }
        }
      } ~ path("names") {
        get {
          onSuccess(userService.listUserNames()) { userNames =>
            complete(userNames)
          }
        }
      }
    } ~ pathPrefix("private") {
      authenticate(authenticator.basicUserAuthenticator) { authInfo =>
        path("test") {
          get {
            complete(s"Hi, ${authInfo.user.user}")
          }
        } ~ path("admin") {
          authorize(authInfo.hasPermission(Administrator)) {
            get {
              complete(s"Admin: ${authInfo.user.user}")
            }
          }
        }

      }
    }

  def systemRoutes: Route =
    path("stop") {
      (post | parameter('method ! "post")) {
        complete {
          var system = actorRefFactory.asInstanceOf[ActorContext].system
          system.scheduler.scheduleOnce(1.second)(system.shutdown())(system.dispatcher)
          "Shutting down in 1 second..."
        }
      }
    }

  def routes: Route =
    pathPrefix("api") {
      directoryRoutes ~ scpRoutes ~ metaDataRoutes ~ datasetRoutes ~ boxRoutes ~ userRoutes ~ systemRoutes
    } ~ staticResourcesRoutes ~ angularRoutes

}

