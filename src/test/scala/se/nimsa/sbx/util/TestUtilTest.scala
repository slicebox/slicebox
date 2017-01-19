package se.nimsa.sbx.util

import org.scalatest.{FlatSpec, Matchers}

class TestUtilTest extends FlatSpec with Matchers {

  "Creating a test database" should "create an in-memory database with the specified name" in {
    val name = "testname"
    val dbConfig = TestUtil.createTestDb(name)
    dbConfig.config.getString("db.url") shouldBe s"jdbc:h2:mem:./$name"
  }
}
