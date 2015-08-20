package se.nimsa.sbx.dicom

case class ImageAttribute(
  tag: Int,
  group: Int,
  element: Int,
  name: String,
  vr: String,
  multiplicity: Int,
  length: Int,
  depth: Int,
  tagPath: List[Int],
  namePath: List[String],
  values: List[String])
  
