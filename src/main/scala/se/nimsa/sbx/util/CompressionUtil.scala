/*
 * Copyright 2016 Lars Edenbrandt
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package se.nimsa.sbx.util

import java.util.zip.Deflater
import java.io.ByteArrayOutputStream
import java.util.zip.Inflater

object CompressionUtil {
  
 def compress(data: Array[Byte]): Array[Byte] = {  
   val deflater = new Deflater  
   deflater.setInput(data)  
   
   val outputStream = new ByteArrayOutputStream(data.length)
       
   deflater.finish()
   val buffer = new Array[Byte](1024)   
   while (!deflater.finished) {  
    val count = deflater.deflate(buffer)
    outputStream.write(buffer, 0, count)   
   }  
   outputStream.close()
   val output = outputStream.toByteArray
   
   deflater.end()

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
   outputStream.close()
   val output = outputStream.toByteArray
   
   inflater.end()
   
   output
  }   
}
