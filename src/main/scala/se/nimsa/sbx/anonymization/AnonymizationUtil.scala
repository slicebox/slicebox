package se.nimsa.sbx.anonymization

import java.util.UUID

import akka.util.ByteString
import org.dcm4che3.data.VR
import org.dcm4che3.util.UIDUtils
import se.nimsa.sbx.dicom.DicomUtil.toAsciiBytes

import scala.util.Random

object AnonymizationUtil {

  def createAnonymousPatientName(sex: Option[String], age: Option[String]): String = {
    val sexString = sex.filter(_.nonEmpty).getOrElse("<unknown sex>")
    val ageString = age.filter(_.nonEmpty).getOrElse("<unknown age>")
    s"Anonymous $sexString $ageString"
  }

  def createAccessionNumber(accessionNumberBytes: ByteString): ByteString = {
    val seed = UUID.nameUUIDFromBytes(accessionNumberBytes.toArray).getMostSignificantBits
    val rand = new Random(seed)
    val newNumber = (1 to 16).foldLeft("")((s, _) => s + rand.nextInt(10).toString)
    toAsciiBytes(newNumber, VR.SH)
  }

  def createUid(baseValue: String): String =
    if (baseValue == null || baseValue.isEmpty)
      UIDUtils.createUID()
    else
      UIDUtils.createNameBasedUID(baseValue.getBytes)

  def createUid(baseValue: ByteString): ByteString = toAsciiBytes(
    if (baseValue == null || baseValue.isEmpty)
      UIDUtils.createUID()
    else
      UIDUtils.createNameBasedUID(baseValue.toArray), VR.UI)
}
