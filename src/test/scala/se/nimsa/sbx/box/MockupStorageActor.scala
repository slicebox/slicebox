package se.nimsa.sbx.box

import akka.actor.Actor
import akka.actor.Status.Failure
import se.nimsa.sbx.dicom.DicomHierarchy.Image
import se.nimsa.sbx.dicom.DicomPropertyValue.ImageType
import se.nimsa.sbx.dicom.DicomPropertyValue.InstanceNumber
import se.nimsa.sbx.dicom.DicomPropertyValue.SOPInstanceUID
import se.nimsa.sbx.storage.StorageProtocol.AddDataset
import se.nimsa.sbx.storage.StorageProtocol.DatasetAdded
import se.nimsa.sbx.storage.StorageProtocol.GetDataset
import se.nimsa.sbx.util.TestUtil

class MockupStorageActor extends Actor {

  import MockupStorageActor._
  
  var nStoredDatasets = 3

  var badBehavior = false
  var exception: Exception = null

  def receive = {
    case ShowBadBehavior(e) =>
      badBehavior = true
      exception = e

    case ShowGoodBehavior(n) =>
      badBehavior = false
      nStoredDatasets = n

    case AddDataset(dataset, source) =>
      if (badBehavior) {
        sender ! Failure(exception)
      } else {
        sender ! DatasetAdded(Image((math.random * 1000).toLong, (math.random * 1000).toLong, SOPInstanceUID("sop uid"), ImageType("image type"), InstanceNumber("instance number")), source, false)
      }

    case GetDataset(imageId, withPixelData) =>
      if (badBehavior) {
        sender ! Failure(exception)
      } else {
        val datasetMaybe = imageId match {
          case id if id <= nStoredDatasets => Some(TestUtil.createDataset())
          case _ =>
            None
        }
        sender ! datasetMaybe
      }
  }
}

object MockupStorageActor {
  case class ShowGoodBehavior(nStoredDatasets: Int)
  case class ShowBadBehavior(e: Exception)
}