package se.vgregion.dicom

import akka.actor.ActorSystem
import akka.actor.Actor
import akka.actor.Props
import akka.testkit.{ TestKit, ImplicitSender }
import org.scalatest.WordSpecLike
import org.scalatest.Matchers
import org.scalatest.BeforeAndAfterAll
import se.vgregion.dicom.MetaDataProtocol._
import se.vgregion.db.DbProtocol._

case class SetImages(images: Seq[Image])

class MockDbActor extends Actor {

  var images = Seq.empty[Image]
  
  def receive = {
    case SetImages(data) =>
      images = data
    case GetImageEntries =>
      sender ! Images(images)
  }
}

class MetaDataActorTest(_system: ActorSystem) extends TestKit(_system) with ImplicitSender
  with WordSpecLike with Matchers with BeforeAndAfterAll {

  def this() = this(ActorSystem("MetaDataTestSystem"))

  override def afterAll {
    TestKit.shutdownActorSystem(system)
  }

  val dbActor = system.actorOf(Props[MockDbActor])
  
  val metaDataActor = system.actorOf(Props(classOf[MetaDataActor], dbActor))

  val pat1 = Patient("p1", "s1")
  val pat2 = Patient("p2", "s2")
  
  val study1 = Study(pat1, "19990101", "stuid1")
  val study2 = Study(pat2, "20000101", "stuid2")

  val series1 = Series(study1, "19990101", "souid1")
  val series2 = Series(study2, "20000101", "souid2")
  val series3 = Series(study1, "19990102", "souid3")

  val image1 = Image(series1, "souid1", "file1")
  val image2 = Image(series2, "souid2", "file2")
  val image3 = Image(series3, "souid3", "file3")
   
  "A MetaDataActor" must {

    "return an empty list when no metadata exists" in {
      metaDataActor ! GetImages
      expectMsg(Images(Seq()))
    }

    "return a list of three objects when three entries exist" in {
      val images = Seq(image1, image2, image3)
      dbActor ! SetImages(images)
      metaDataActor ! GetImages
      expectMsgPF() {
        case Images(data) if data.size == 3 => true
      }
    }

  }

}