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

package se.nimsa.sbx.scu

import se.nimsa.sbx.model.Entity

object ScuProtocol {
  
  case class ScuData(id: Long, name: String, aeTitle: String, host: String, port: Int) extends Entity


  sealed trait ScuRequest
  
  case class AddScu(scuData: ScuData) extends ScuRequest

  case class RemoveScu(id: Long) extends ScuRequest 

  case class GetScus(startIndex: Long, count: Long) extends ScuRequest

  case class SendImagesToScp(imageIds: Seq[Long], scuId: Long) extends ScuRequest
  
  case class Scus(scus: Seq[ScuData]) 


  case class ScuRemoved(scuDataId: Long)

  case class ImagesSentToScp(scuId: Long, imageIds: Seq[Long])
  
}
