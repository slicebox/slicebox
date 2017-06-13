/*
 * Copyright 2014 Lars Edenbrandt
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

package se.nimsa.sbx.scp

import se.nimsa.sbx.dicom.DicomData
import se.nimsa.sbx.model.Entity

object ScpProtocol {
  
  case class ScpData(id: Long, name: String, aeTitle: String, port: Int) extends Entity

  
  sealed trait ScpRequest
  
  case class AddScp(scpData: ScpData) extends ScpRequest

  case class RemoveScp(id: Long) extends ScpRequest 

  case class GetScps(startIndex: Long, count: Long) extends ScpRequest

  case class GetScpById(scpId: Long) extends ScpRequest
  
  case class Scps(scps: Seq[ScpData]) 


  case class ScpRemoved(scpDataId: Long)

  case class DicomDataReceivedByScp(dicomData: DicomData)

}
