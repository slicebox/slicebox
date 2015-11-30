package se.nimsa.sbx.util

import org.scalatest.FlatSpec
import org.scalatest.Matchers

class CompressionUtilTest extends FlatSpec with Matchers {

  "Compressing a byte array with redundant data" should "decrease the size of the array" in {
    val data = new Array[Byte](10000)
    for (i <- 0 until data.length)
      data(i) = (i / 10).toByte
    val compressedData = CompressionUtil.compress(data)
    compressedData.length should be < (data.length)
  }

  "Compressing and the decompressing a byte array" should "return the original array" in {
    val data = new Array[Byte](10000)
    for (i <- 0 until data.length)
      data(i) = (i / 10).toByte
    val compressedData = CompressionUtil.compress(data)
    val restoredData = CompressionUtil.decompress(compressedData)
    restoredData should equal (data)
  }

}