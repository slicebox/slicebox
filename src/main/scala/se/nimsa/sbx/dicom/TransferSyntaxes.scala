package se.nimsa.sbx.dicom

object TransferSyntaxes {

  case class TransferSyntax(uid: String, name: String)

  val ImplicitVrLittleEndian = TransferSyntax("1.2.840.10008.1.2", "Implicit VR Little Endian")
  val ExplicitVrLittleEndian = TransferSyntax("1.2.840.10008.1.2.1", "Explicit VR Little Endian")
  val DeflatedExplicitVrLittleEndian = TransferSyntax("1.2.840.10008.1.2.1.99", "Deflated Explicit VR Little Endian")
  val ExplicitVrBigEndian = TransferSyntax("1.2.840.10008.1.2.2", "Explicit VR Big Endian")
  val JpegBaselineProcess1 = TransferSyntax("1.2.840.10008.1.2.4.50", "JPEG Baseline (Process 1)")
  val JoegBaselineProcesses2and4 = TransferSyntax("1.2.840.10008.1.2.4.51", "JPEG Baseline (Processes 2 & 4)")
  val JpegLosslessProcess14 = TransferSyntax("1.2.840.10008.1.2.4.57", "JPEG Lossless, Nonhierarchical (Processes 14)")
  val JpegLosslessNonhierarchical = TransferSyntax("1.2.840.10008.1.2.4.70", "JPEG Lossless, Nonhierarchical, First- Order Prediction (Processes 14 [Selection Value 1])")
  val JpegLsLossless = TransferSyntax("1.2.840.10008.1.2.4.80", "JPEG-LS Lossless Image Compression")
  val JpegLsLossy = TransferSyntax("1.2.840.10008.1.2.4.81", "JPEG-LS Lossy (Near- Lossless) Image Compression")
  val Jpeg2000Lossless = TransferSyntax("1.2.840.10008.1.2.4.90", "JPEG 2000 Image Compression (Lossless Only)")
  val Jpeg2000 = TransferSyntax("1.2.840.10008.1.2.4.91", "JPEG 2000 Image Compression")
  val Jpeg2000MulticomponentLossless = TransferSyntax("1.2.840.10008.1.2.4.92", "JPEG 2000 Part 2 Multicomponent Image Compression (Lossless Only)")
  val Jpeg2000Multicomponent = TransferSyntax("1.2.840.10008.1.2.4.93", "JPEG 2000 Part 2 Multicomponent Image Compression")
  val JpipReferenced = TransferSyntax("1.2.840.10008.1.2.4.94", "JPIP Referenced")
  val JpipReferencedDeflate = TransferSyntax("1.2.840.10008.1.2.4.95", "JPIP Referenced Deflate")
  val RleLossless = TransferSyntax("1.2.840.10008.1.2.5", "RLE Lossless")
  val Rfc2557MimeEncapsulation = TransferSyntax("1.2.840.10008.1.2.6.1", "RFC 2557 MIME Encapsulation")
  val Mpeg2 = TransferSyntax("1.2.840.10008.1.2.4.100", "MPEG2 Main Profile Main Level")
  val Mpeg4 = TransferSyntax("1.2.840.10008.1.2.4.102", "MPEG-4 AVC/H.264 High Profile / Level 4.1")
  val Mpeg4BdCompatible = TransferSyntax("1.2.840.10008.1.2.4.103", "MPEG-4 AVC/H.264 BD-compatible High Profile / Level 4.1")

}
