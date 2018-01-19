package se.nimsa.sbx.util

import akka.actor.{ActorSystem, Scheduler}
import akka.testkit.{ImplicitSender, TestKit}
import akka.util.Timeout
import org.scalatest._
import se.nimsa.sbx.util.FutureUtil._

import scala.concurrent.duration.DurationInt
import scala.concurrent.{ExecutionContextExecutor, Future}
import scala.util.Success

class FutureUtilAkkaTest(_system: ActorSystem) extends TestKit(_system) with ImplicitSender
  with AsyncFlatSpecLike with Matchers with BeforeAndAfterAll with BeforeAndAfterEach {

  def this() = this(ActorSystem("FutureUtilAkkaTestSystem"))

  implicit val ec: ExecutionContextExecutor = system.dispatcher
  implicit val timeout: Timeout = Timeout(30.seconds)
  implicit val scheduler: Scheduler = system.scheduler

  override def afterAll(): Unit = TestKit.shutdownActorSystem(system)

  def retryThrice[T]: PartialFunction[Throwable, Boolean] => (=> Future[T]) => Future[T] =
    retry[T](Seq(100.millis, 200.millis, 1.second), randomFactor = 0.0)

  def retryAlways[T]: (=> Future[T]) => Future[T] =
    retryThrice[T] {
      case _ => true
    }

  "A future with limited retry" should "try the future four times with three retries" in {
    var i = 0

    val f = retryAlways {
      i += 1
      Future.failed(new IllegalArgumentException("oups"))
    }

    f.transform { _ =>
      Success(i shouldBe 4)
    }
  }

  it should "not perform retries if immediately successful" in {
    var i = 0

    val f = retryAlways {
      i += 1
      Future.successful("result")
    }

    f.transform { _ =>
      Success(i shouldBe 1)
    }
  }

  it should "only retry if exception handler allows it" in {
    var i = 0
    val exceptions = new IllegalStateException("state") :: new IllegalArgumentException("argument") :: Nil

    val f = retryThrice {
      case _: IllegalStateException => true
    } {
      val exception = exceptions(i)
      i += 1
      Future.failed(exception)
    }

    f.transform { _ =>
      Success(i shouldBe 2)
    }
  }

  "A future with continuous retry" should "try the future until the shouldRetry function fails" in {
    var i = 0

    val f = retry(10.millis, 20.millis, 0.2){
      case _: Throwable => i != 10
    } {
      i += 1
      Future.failed(new IllegalArgumentException("oups"))
    }

    f.transform { _ =>
      Success(i shouldBe 10)
    }
  }

  it should "return the result of the first successful attempt" in {
    var i = 0
    val n = 10

    val f = retry(10.millis, 20.millis, 0.2){
      case _: Throwable => true
    } {
      i += 1
      if (i < n)
        Future.failed(new IllegalArgumentException("oups"))
      else
        Future.successful(i)
    }

    f.map { result =>
      result shouldBe n
    }
  }

  "Creating 3 backoff delays with minimum delay 100 milliseconds" should "produce the delays 100, 200 and 400 milliseconds" in {
    backoffDelays(3, 100.millis) shouldBe Seq(100.millis, 200.millis, 400.millis)
  }

  "Creating a single backoff delay" should "return the minimum delay on first attempt" in {
    backoffDelay(1, 10.millis, 100.millis, 0.0) shouldBe 10.millis
  }

  it should "return the double the delay on the second attempt" in {
    backoffDelay(2, 10.millis, 100.millis, 0.0) shouldBe 20.millis
  }

  it should "return the maximum delay after enough attempts" in {
    backoffDelay(25, 10.millis, 100.millis, 0.0) shouldBe 100.millis
  }

}
