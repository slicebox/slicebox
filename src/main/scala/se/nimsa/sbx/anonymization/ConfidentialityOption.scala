package se.nimsa.sbx.anonymization

sealed trait ConfidentialityOption {
  val name: String = toString
  val description: String
  val title: String
  val supported: Boolean
  val rank: Int
}

object ConfidentialityOption {

  def withName(name: String): ConfidentialityOption = name match {
    case "BASIC_PROFILE" => BASIC_PROFILE
    case "CLEAN_PIXEL_DATA" => CLEAN_PIXEL_DATA
    case "CLEAN_RECOGNIZABLE_VISUAL_FEATURES" => CLEAN_RECOGNIZABLE_VISUAL_FEATURES
    case "CLEAN_GRAPHICS" => CLEAN_GRAPHICS
    case "CLEAN_STRUCTURED_CONTENT" => CLEAN_STRUCTURED_CONTENT
    case "CLEAN_DESCRIPTORS" => CLEAN_DESCRIPTORS
    case "RETAIN_LONGITUDINAL_TEMPORAL_INFORMATION" => RETAIN_LONGITUDINAL_TEMPORAL_INFORMATION
    case "RETAIN_LONGITUDINAL_TEMPORAL_INFORMATION_MODIFIED_DATES" => RETAIN_LONGITUDINAL_TEMPORAL_INFORMATION_MODIFIED_DATES
    case "RETAIN_PATIENT_CHARACTERISTICS" => RETAIN_PATIENT_CHARACTERISTICS
    case "RETAIN_DEVICE_IDENTITY" => RETAIN_DEVICE_IDENTITY
    case "RETAIN_INSTITUTION_IDENTITY" => RETAIN_INSTITUTION_IDENTITY
    case "RETAIN_UIDS" => RETAIN_UIDS
    case "RETAIN_SAFE_PRIVATE" => RETAIN_SAFE_PRIVATE
    case _ => throw new IllegalArgumentException(s"No such option: $name")
  }

  lazy val options: Seq[ConfidentialityOption] = Seq(
    BASIC_PROFILE, CLEAN_PIXEL_DATA, CLEAN_RECOGNIZABLE_VISUAL_FEATURES, CLEAN_GRAPHICS, CLEAN_STRUCTURED_CONTENT, CLEAN_DESCRIPTORS,
    RETAIN_LONGITUDINAL_TEMPORAL_INFORMATION, RETAIN_LONGITUDINAL_TEMPORAL_INFORMATION_MODIFIED_DATES, RETAIN_PATIENT_CHARACTERISTICS, RETAIN_DEVICE_IDENTITY, RETAIN_INSTITUTION_IDENTITY, RETAIN_UIDS, RETAIN_SAFE_PRIVATE)

  case object BASIC_PROFILE extends ConfidentialityOption {
    override val description: String = "Basic Profile"
    override val title: String = description
    override val supported: Boolean = true
    override val rank: Int = 10
  }

  case object CLEAN_PIXEL_DATA extends ConfidentialityOption {
    override val description: String = "Clean Pixel Data"
    override val title: String = description
    override val supported: Boolean = false
    override val rank: Int = 20
  }

  case object CLEAN_RECOGNIZABLE_VISUAL_FEATURES extends ConfidentialityOption {
    override val description: String = "Clean Recognizable Visual Features"
    override val title: String = "Clean Visual Features"
    override val supported: Boolean = false
    override val rank: Int = 30
  }

  case object CLEAN_GRAPHICS extends ConfidentialityOption {
    override val description: String = "Clean Graphics"
    override val title: String = description
    override val supported: Boolean = false
    override val rank: Int = 40
  }

  case object CLEAN_STRUCTURED_CONTENT extends ConfidentialityOption {
    override val description: String = "Clean Structured Content"
    override val title: String = description
    override val supported: Boolean = false
    override val rank: Int = 50
  }

  case object CLEAN_DESCRIPTORS extends ConfidentialityOption {
    override val description: String = "Clean Descriptors"
    override val title: String = description
    override val supported: Boolean = false
    override val rank: Int = 60
  }

  case object RETAIN_LONGITUDINAL_TEMPORAL_INFORMATION extends ConfidentialityOption {
    override val description: String = "Retain Longitudinal Temporal Information"
    override val title: String = "Retain Temporal Info"
    override val supported: Boolean = true
    override val rank: Int = 70
  }

  case object RETAIN_LONGITUDINAL_TEMPORAL_INFORMATION_MODIFIED_DATES extends ConfidentialityOption {
    override val description: String = "Retain Longitudinal Temporal Information with Modified Dates"
    override val title: String = "Retain Modified Temporal Info"
    override val supported: Boolean = false
    override val rank: Int = 80
  }

  case object RETAIN_PATIENT_CHARACTERISTICS extends ConfidentialityOption {
    override val description: String = "Retain Patient Characteristics"
    override val title: String = "Retain Patient Characteristics"
    override val supported: Boolean = true
    override val rank: Int = 90
  }

  case object RETAIN_DEVICE_IDENTITY extends ConfidentialityOption {
    override val description: String = "Retain Device Identity"
    override val title: String = "Retain Device Identity"
    override val supported: Boolean = true
    override val rank: Int = 100
  }

  case object RETAIN_INSTITUTION_IDENTITY extends ConfidentialityOption {
    override val description: String = "Retain Institution Identity"
    override val title: String = "Retain Institution Identity"
    override val supported: Boolean = true
    override val rank: Int = 110
  }

  case object RETAIN_UIDS extends ConfidentialityOption {
    override val description: String = "Retain UIDs"
    override val title: String = "Retain UIDs"
    override val supported: Boolean = true
    override val rank: Int = 120
  }

  case object RETAIN_SAFE_PRIVATE extends ConfidentialityOption {
    override val description: String = "Retain Safe Private Attributes"
    override val title: String = "Retain Safe Private Attributes"
    override val supported: Boolean = true
    override val rank: Int = 130
  }

}
