package se.vgregion

import akka.actor.{ PoisonPill, Actor }
import java.nio.file.FileSystems
import java.nio.file.Paths

class ImageStorage extends Actor {
  import StorageProtocol._

  var images = List[Image]()

  setupWatchService()
  
  def receive = {

    case GetImages => sender ! Images(images)

  }
  
  def setupWatchService() = {
    import java.nio.file.StandardWatchEventKinds._
    
    val dir = Paths.get("C:/users/karl/Desktop/temp")
    val watcher = FileSystems.getDefault().newWatchService();
    dir.register(watcher, ENTRY_CREATE, ENTRY_DELETE);

    while (true) {
      // wait for key to be signaled
      WatchKey key = watcher.take();

      for (WatchEvent<?> event : key.pollEvents()) {
        WatchEvent.Kind<?> kind = event.kind();

        System.out.println(kind);

        if (kind == OVERFLOW) {
          continue;
        }

      }

      updateBox(box, dir);

      boolean valid = key.reset();
      if (!valid) {
        break;
      }

    }    
  }
}

object StorageProtocol {
  import se.vgregion.StorageProtocol._
  import spray.json._
  import DefaultJsonProtocol._

  case class Image(fileName: String)

  case object GetImages

  case class Images(images: List[Image])

  //----------------------------------------------
  // JSON
  //----------------------------------------------

  object Image extends DefaultJsonProtocol {
    implicit val format = jsonFormat1(Image.apply)
  }

}


