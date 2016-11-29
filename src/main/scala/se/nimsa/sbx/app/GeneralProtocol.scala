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

package se.nimsa.sbx.app

import se.nimsa.sbx.dicom.DicomHierarchy.Image

object GeneralProtocol {
  
  sealed trait SourceType {
    override def toString: String = this match {
      case SourceType.SCP => "scp"
      case SourceType.DIRECTORY => "directory"
      case SourceType.BOX => "box"
      case SourceType.USER => "user"
      case SourceType.IMPORT => "import"
      case _ => "unknown"
    }
  }

  object SourceType {
    case object SCP extends SourceType
    case object DIRECTORY extends SourceType
    case object BOX extends SourceType
    case object USER extends SourceType
    case object UNKNOWN extends SourceType
    case object IMPORT extends SourceType

    def withName(string: String): SourceType = string match {
      case "scp" => SCP
      case "directory" => DIRECTORY
      case "box" => BOX
      case "user" => USER
      case "import" => IMPORT
      case _ => UNKNOWN
    }    
  }
      
  sealed trait DestinationType {
    override def toString: String = this match {
      case DestinationType.SCU => "scu"
      case DestinationType.BOX => "box"
      case _ => "unknown"
    }
  }

  object DestinationType {
    case object SCU extends DestinationType
    case object BOX extends DestinationType
    case object UNKNOWN extends DestinationType

    def withName(string: String): DestinationType = string match {
      case "scu" => SCU
      case "box" => BOX
      case _ => UNKNOWN
    }    
  }
      
  case class Source(sourceType: SourceType, sourceName: String, sourceId: Long)
  
  case class SourceRef(sourceType: SourceType, sourceId: Long)
  
  case class Destination(destinationType: DestinationType, destinationName: String, destinationId: Long)

  case class ImageAdded(image: Image, source: Source, overwrite: Boolean)

  case class ImageDeleted(imageId: Long)

  case class ImagesSent(destination: Destination, imageIds: Seq[Long])

}
