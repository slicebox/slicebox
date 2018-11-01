package se.nimsa.sbx.app

import org.scalatest.{FlatSpec, Matchers}
import play.api.libs.json.Json
import se.nimsa.dicom.data.{Tag, TagPath}
import se.nimsa.dicom.data.TagPath.{TagPathSequenceAny, TagPathSequenceItem, TagPathTag}

class JsonFormatsTest extends FlatSpec with Matchers with JsonFormats {

  "JSON formatting of a tag path" should "be valid" in {
    val tagPath = TagPath.fromSequence(Tag.PatientName, 1).thenSequence(Tag.PatientName).thenTag(Tag.PatientID)

    Json.prettyPrint(Json.toJson(tagPath)) shouldBe
      """{
        |  "tag" : 1048608,
        |  "name" : "PatientID",
        |  "previous" : {
        |    "tag" : 1048592,
        |    "name" : "PatientName",
        |    "item" : "*",
        |    "previous" : {
        |      "tag" : 1048592,
        |      "name" : "PatientName",
        |      "item" : "1"
        |    }
        |  }
        |}""".stripMargin
  }

  it should "work also for paths ending with a sequence" in {
    val tagPath = TagPath.fromSequence(4).thenSequence(5, 6)
    Json.prettyPrint(Json.toJson(tagPath)) shouldBe
      """{
        |  "tag" : 5,
        |  "name" : "",
        |  "item" : "6",
        |  "previous" : {
        |    "tag" : 4,
        |    "name" : "",
        |    "item" : "*"
        |  }
        |}""".stripMargin
  }

  "JSON parsing of a tag path" should "work for tag paths pointing to a tag" in {
    val tagPath = TagPath.fromSequence(1, 1).thenSequence(2).thenTag(3)

    Json.fromJson[TagPathTag](Json.toJson(tagPath)).get shouldBe tagPath
  }

  it should "work for tag paths pointing to a sequence" in {
    val tagPath = TagPath.fromSequence(1, 1).thenSequence(2, 1)

    Json.fromJson[TagPathSequenceItem](Json.toJson(tagPath)).get shouldBe tagPath
  }

  it should "work for tag paths pointing to all items in a sequence" in {
    val tagPath = TagPath.fromSequence(1, 1).thenSequence(2)

    Json.fromJson[TagPathSequenceAny](Json.toJson(tagPath)).get shouldBe tagPath
  }

  it should "work for general tag paths" in {
    val tagPath = TagPath.fromSequence(1, 1).thenSequence(2).thenTag(3)

    Json.fromJson[TagPath](Json.toJson(tagPath)).map { tp =>
      tp shouldBe tagPath
      tp shouldBe a[TagPathTag]
    }
  }

  it should "support missing keywords" in {
    Json.fromJson[TagPath](Json.parse(
      """{
        |  "tag" : 1048608,
        |  "name" : "PatientID",
        |  "item" : "6",
        |  "previous" : {
        |    "tag" : 561685,
        |    "name" : "DerivationCodeSequence",
        |    "item" : "*"
        |  }
        |}""".stripMargin))
      .get shouldBe TagPath.fromSequence(Tag.DerivationCodeSequence).thenSequence(Tag.PatientID, 6)
  }

  it should "support keywords instead of tag numbers" in {
    Json.fromJson[TagPath](Json.parse(
      """{
        |  "name" : "PatientID",
        |  "item" : "6",
        |  "previous" : {
        |    "name" : "DerivationCodeSequence",
        |    "item" : "*"
        |  }
        |}""".stripMargin))
      .get shouldBe TagPath.fromSequence(Tag.DerivationCodeSequence).thenSequence(Tag.PatientID, 6)
  }

  it should "throw error for unknown keyword and tag is not specified" in {
    Json.fromJson[TagPathTag](Json.parse(
      """{
        |  "name" : "NotAKeyword"
        |}""".stripMargin))
      .isError shouldBe true
  }

  it should "not throw error for unknown keyword when tag is specified" in {
    Json.fromJson[TagPathTag](Json.parse(
      """{
        |  "tag" : 1048608,
        |  "name" : "NotAKeyword"
        |}""".stripMargin))
      .isError shouldBe false
  }
}
