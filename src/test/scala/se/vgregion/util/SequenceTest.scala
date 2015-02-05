package se.vgregion.util

import org.scalatest.Matchers
import org.scalatest.FlatSpec
import scala.concurrent.Future
import scala.concurrent.ExecutionContext.Implicits.global

class SequenceTest extends FlatSpec with Matchers {

  "A something" should "something" in {
    purgeOutbox(Some(0))
    1 should be(1)
  }

  def purgeOutbox(entryOption: Option[Int]): Unit =
    entryOption foreach (entry =>
      Future {
        "Response for entry " + entry
      }.map(response => {
        println(response)
        purgeOutbox(nextEntry(entry))
      }).recover {
        case e: Exception => println("got excetpion")
      })

  def nextEntry(i: Int): Option[Int] =
    if (i == 7) throw new Exception("oups")
    else if (i < 10) Some(i + 1) else None
}