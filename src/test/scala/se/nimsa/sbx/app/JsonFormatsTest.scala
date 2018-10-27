package se.nimsa.sbx.app

import org.scalatest.{FlatSpec, Matchers}
import play.api.libs.json.Json
import se.nimsa.dicom.data.{Tag, TagPath}
import se.nimsa.dicom.data.TagPath.{TagPathSequenceAny, TagPathSequenceItem, TagPathTag}

class JsonFormatsTest extends FlatSpec with Matchers with JsonFormats {

  "JSON formatting of a tag path" should "be valid" in {
    val tagPath = TagPath.fromSequence(1, 1).thenSequence(2).thenTag(3)

    Json.prettyPrint(Json.toJson(tagPath)) shouldBe
      """{
        |  "tag" : 3,
        |  "previous" : {
        |    "tag" : 2,
        |    "item" : "*",
        |    "previous" : {
        |      "tag" : 1,
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
        |  "item" : "6",
        |  "previous" : {
        |    "tag" : 4,
        |    "item" : "*"
        |  }
        |}""".stripMargin
  }

  "JSON parsing of a tag path" should "work for tag paths pointing to a tag" in {
    val tagPath = TagPath.fromSequence(1, 1).thenSequence(2).thenTag(3)

    Json.fromJson[TagPathTag](Json.toJson(tagPath))(tagPathTagReads).map {
      _ shouldBe tagPath
    }
  }

  it should "work for tag paths pointing to a sequence" in {
    val tagPath = TagPath.fromSequence(1, 1).thenSequence(2, 1)

    Json.fromJson[TagPathSequenceItem](Json.toJson(tagPath))(tagPathSequenceItemReads).map {
      _ shouldBe tagPath
    }
  }

  it should "work for tag paths pointing to all items in a sequence" in {
    val tagPath = TagPath.fromSequence(1, 1).thenSequence(2)

    Json.fromJson[TagPathSequenceAny](Json.toJson(tagPath))(tagPathSequenceAnyReads).map {
      _ shouldBe tagPath
    }
  }

  it should "work for general tag paths" in {
    val tagPath = TagPath.fromSequence(1, 1).thenSequence(2).thenTag(3)

    Json.fromJson[TagPath](Json.toJson(tagPath))(tagPathReads).map { tp =>
      tp shouldBe tagPath
      tp shouldBe a[TagPathTag]
    }
  }

  it should "support keywords instead of tag numbers" in {
    Json.fromJson[TagPathTag](Json.parse(
      """{
        |  "tag" : "PatientID",
        |  "item" : "6",
        |  "previous" : {
        |    "tag" : "DerivationCodeSequence",
        |    "item" : "*"
        |  }
        |}""".stripMargin))
      .map {
        _ shouldBe TagPath.fromSequence(Tag.DerivationCodeSequence).thenTag(Tag.PatientID)
      }
  }

  it should "throw error for unknown keywords" in {
    Json.fromJson[TagPathTag](Json.parse(
      """{
        |  "tag" : "NotAKeyword"
        |}""".stripMargin))
      .isError shouldBe true
  }
}
