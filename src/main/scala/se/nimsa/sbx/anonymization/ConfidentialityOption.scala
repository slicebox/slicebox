package se.nimsa.sbx.anonymization

sealed trait ConfidentialityOption {
  def description: String
  def supported: Boolean
  def rank: Int
  override def toString: String = description
}

object ConfidentialityOption {

  case object BASIC_PROFILE extends ConfidentialityOption {
    override val description: String = "Basic Profile"
    override val supported: Boolean = true
    override def rank: Int = 10
  }

  case object CLEAN_PIXEL_DATA extends ConfidentialityOption {
    override val description: String = "Clean Pixel Data"
    override val supported: Boolean = false
    override def rank: Int = 20
  }

  case object CLEAN_RECOGNIZABLE_VISUAL_FEATURES extends ConfidentialityOption {
    override val description: String = "Clean Recognizable Visual Features"
    override val supported: Boolean = false
    override def rank: Int = 30
  }

  case object CLEAN_GRAPHICS extends ConfidentialityOption {
    override val description: String = "Clean Graphics"
    override val supported: Boolean = false
    override def rank: Int = 40
  }

  case object CLEAN_STRUCTURED_CONTENT extends ConfidentialityOption {
    override val description: String = "Clean Structured Content"
    override val supported: Boolean = false
    override def rank: Int = 50
  }

  case object CLEAN_DESCRIPTORS extends ConfidentialityOption {
    override val description: String = "Clean Descriptors"
    override val supported: Boolean = false
    override def rank: Int = 60
  }

  case object RETAIN_LONGITUDINAL_TEMPORAL_INFORMATION extends ConfidentialityOption {
    override val description: String = "Retain Longitudinal Temporal Information"
    override val supported: Boolean = true
    override def rank: Int = 70
  }

  case object RETAIN_LONGITUDINAL_TEMPORAL_INFORMATION_MODIFIED_DATES extends ConfidentialityOption {
    override val description: String = "Retain Longitudinal Temporal Information with Modified Dates"
    override val supported: Boolean = false
    override def rank: Int = 80
  }

  case object RETAIN_PATIENT_CHARACTERISTICS extends ConfidentialityOption {
    override val description: String = "Retain Patient Characteristics"
    override val supported: Boolean = true
    override def rank: Int = 90
  }

  case object RETAIN_DEVICE_IDENTITY extends ConfidentialityOption {
    override val description: String = "Retain Device Identity"
    override val supported: Boolean = true
    override def rank: Int = 100
  }

  case object RETAIN_INSTITUTION_IDENTITY extends ConfidentialityOption {
    override val description: String = "Retain Institution Identity"
    override val supported: Boolean = true
    override def rank: Int = 110
  }

  case object RETAIN_UIDS extends ConfidentialityOption {
    override val description: String = "Retain UIDs"
    override val supported: Boolean = true
    override def rank: Int = 120
  }

  case object RETAIN_SAFE_PRIVATE extends ConfidentialityOption {
    override val description: String = "Retain Safe Private"
    override val supported: Boolean = true
    override def rank: Int = 130
  }

}
