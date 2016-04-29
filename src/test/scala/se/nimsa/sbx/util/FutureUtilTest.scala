package se.nimsa.sbx.util

import java.util.concurrent.Executors

import akka.dispatch.ExecutionContexts
import org.scalatest.{FlatSpec, Matchers}
import se.nimsa.sbx.util.FutureUtil._

import scala.concurrent.{Await, Future}
import scala.concurrent.duration.DurationInt

class FutureUtilTest extends FlatSpec with Matchers {

  implicit val ec = ExecutionContexts.fromExecutor(Executors.newFixedThreadPool(8))

  "traversing a collection with Future.traverse" should "complete futures in parallel" in {

    val integers = (0 until 8).toList
    var finishedFutures = Seq.empty[Int]

    val futureList = Future.traverse(integers)(index => Future {
      Thread.sleep((8 - index) * 200) // gradually faster tasks
      // println(s"Created result $index on thread ${Thread.currentThread.getName}")
      finishedFutures = finishedFutures :+ index
      index
    })

    val list = Await.result(futureList, 20.seconds)

    list shouldBe integers
    finishedFutures should not be integers
  }

  "traversing a collection with FutureUtil.traverseSequentially" should "complete futures one after the other" in {

    val integers = (0 until 8).toList
    var finishedFutures = Seq.empty[Int]

    val futureList = traverseSequentially(integers)(index => Future {
      Thread.sleep((8 - index) * 200) // gradually faster tasks
      // println(s"Created result $index on thread ${Thread.currentThread.getName}")
      finishedFutures = finishedFutures :+ index
      index
    })

    val list = Await.result(futureList, 20.seconds)

    integers.foreach { i =>
      finishedFutures(i) shouldBe i
      list(i) shouldBe i
    }
  }

  it should "return a failed Future when any one Future fails" in {
    def futureFun = (index: Int) => if (index == 3) Future.failed(new Exception("fail")) else Future(index)
    val integers = (0 until 8).toList
    val futureList = traverseSequentially(integers)(futureFun)
    intercept[Exception] {
      Await.result(futureList, 20.seconds)
    }
  }
}