package se.nimsa.sbx.dicom

import akka.dispatch.PriorityGenerator
import akka.dispatch.UnboundedPriorityMailbox
import com.typesafe.config.Config
import se.nimsa.sbx.dicom.DicomProtocol.FileReceived
import se.nimsa.sbx.dicom.DicomProtocol.DatasetReceived
import akka.actor.ActorSystem

class DicomStorageMailbox(settings: ActorSystem.Settings, config: Config)
  extends UnboundedPriorityMailbox(
    PriorityGenerator {
      case msg: FileReceived    => 2 // low
      case msg: DatasetReceived => 1 // low but higher than FileReceived
      case _                    => 0 // normal
    })
