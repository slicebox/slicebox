package se.nimsa.sbx.box

import java.util.zip.Deflater
import java.io.ByteArrayOutputStream
import java.util.zip.Inflater

object CompressionUtil {
  
 def compress(data: Array[Byte]): Array[Byte] = {  
   val deflater = new Deflater  
   deflater.setInput(data)  
   
   val outputStream = new ByteArrayOutputStream(data.length)
       
   deflater.finish
   val buffer = new Array[Byte](1024)   
   while (!deflater.finished) {  
    val count = deflater.deflate(buffer)
    outputStream.write(buffer, 0, count)   
   }  
   outputStream.close
   val output = outputStream.toByteArray
   
   deflater.end

   output
  }  
   
  def decompress(data: Array[Byte]): Array[Byte] = {
   val inflater = new Inflater   
   inflater.setInput(data)
   
   val outputStream = new ByteArrayOutputStream(data.length)
   val buffer = new Array[Byte](1024)   
   while (!inflater.finished) {  
    val count = inflater.inflate(buffer)
    outputStream.write(buffer, 0, count)
   }  
   outputStream.close
   val output = outputStream.toByteArray
   
   inflater.end
   
   output
  }   
}