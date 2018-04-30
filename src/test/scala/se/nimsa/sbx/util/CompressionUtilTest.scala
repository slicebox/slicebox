package se.nimsa.sbx.util

import akka.util.ByteString
import org.scalatest.{FlatSpec, Matchers}

class CompressionUtilTest extends FlatSpec with Matchers {

  "Compressing a byte array with redundant data" should "decrease the size of the array" in {
    val data = ByteString((0 until 10000).map(i => (i / 10).toByte): _*)
    val compressedData = CompressionUtil.compress(data)
    compressedData.length should be < data.length
  }

  "Compressing and the decompressing a byte array" should "return the original array" in {
    val data = ByteString((0 until 10000).map(i => (i / 10).toByte): _*)
    val compressedData = CompressionUtil.compress(data)
    val restoredData = CompressionUtil.decompress(compressedData)
    restoredData shouldBe data
  }

}