package se.nimsa.sbx.util

import akka.actor.ActorSystem
import akka.stream.ActorMaterializer
import akka.testkit.TestKit
import org.scalatest.{BeforeAndAfterAll, FlatSpecLike, Matchers}
import se.nimsa.dicom.data.{Elements, Tag}

import scala.concurrent.ExecutionContext

class TestUtilTest extends TestKit(ActorSystem("TestUtilSpec")) with FlatSpecLike with Matchers with BeforeAndAfterAll {

  implicit val materializer: ActorMaterializer = ActorMaterializer()
  implicit val ec: ExecutionContext = system.dispatcher

  override def afterAll: Unit = TestKit.shutdownActorSystem(system)

  "Creating a test database" should "create an in-memory database with the specified name" in {
    val name = "testname"
    val dbConfig = TestUtil.createTestDb(name)
    dbConfig.config.getString("db.url") shouldBe s"jdbc:h2:mem:./$name"
  }

  "Loading a attributes" should "return an Elements" in {
    val dicomData = TestUtil.testImageDicomData()
    dicomData.isInstanceOf[Elements] should be(true)
  }

  "Loading a attributes" should "return the same attributes, disregarding pixelData, when loading with and without pixelData" in {
    val dicomData1 = TestUtil.testImageDicomData(withPixelData = false)
    val dicomData2 = TestUtil.testImageDicomData()
    dicomData1 should not equal dicomData2
    dicomData1 shouldBe dicomData2.remove(Tag.PixelData)
  }

}
