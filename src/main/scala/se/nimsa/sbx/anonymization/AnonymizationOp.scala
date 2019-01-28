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

package se.nimsa.sbx.anonymization

sealed trait AnonymizationOp {
  def description: String
  def code: String
  def supported: Boolean
  override def toString: String = code
}

object AnonymizationOp {
  case object DUMMY extends AnonymizationOp {
    override val description: String = "replace with a non-zero length value that may be a dummy value and consistent with the VR"
    override val code: String = "D"
    override val supported: Boolean = true
  }
  case object ZERO extends AnonymizationOp {
    override val description: String = "replace with a zero length value, or a non-zero length value that may be a dummy value and consistent with the VR"
    override val code: String = "Z"
    override val supported: Boolean = true
  }
  case object REMOVE extends AnonymizationOp {
    override val description: String = "remove"
    override val code: String = "X"
    override val supported: Boolean = true
  }
  case object KEEP extends AnonymizationOp {
    override val description: String = "keep (unchanged for non-sequence attributes, cleaned for sequences)"
    override val code: String = "K"
    override val supported: Boolean = true
  }
  case object CLEAN extends AnonymizationOp {
    override val description: String = "clean, that is replace with values of similar meaning known not to contain identifying information and consistent with the VR"
    override val code: String = "C"
    override val supported: Boolean = false
  }
  case object REPLACE_UID extends AnonymizationOp {
    override val description: String = "replace with a non-zero length UID that is internally consistent within a set of Instances"
    override val code: String = "U"
    override val supported: Boolean = true
  }
  case object ZERO_OR_DUMMY extends AnonymizationOp {
    override val description: String = "Z unless D is required to maintain IOD conformance (Type 2 versus Type 1)"
    override val code: String = "Z/D"
    override val supported: Boolean = false
  }
  case object REMOVE_OR_ZERO extends AnonymizationOp {
    override val description: String = "X unless Z is required to maintain IOD conformance (Type 3 versus Type 2)"
    override val code: String = "X/Z"
    override val supported: Boolean = false
  }
  case object REMOVE_OR_DUMMY extends AnonymizationOp {
    override val description: String = "X unless D is required to maintain IOD conformance (Type 3 versus Type 1)"
    override val code: String = "X/D"
    override val supported: Boolean = false
  }
  case object REMOVE_OR_ZERO_OR_DUMMY extends AnonymizationOp {
    override val description: String = "X unless Z or D is required to maintain IOD conformance (Type 3 versus Type 2 versus Type 1)"
    override val code: String = "X/Z/D"
    override val supported: Boolean = false
  }
  case object REMOVE_OR_ZERO_OR_REPLACE_UID extends AnonymizationOp {
    override val description: String = "X unless Z or replacement of contained instance UIDs (U) is required to maintain IOD conformance (Type 3 versus Type 2 versus Type 1 sequences containing UID references)"
    override val code: String = "X/Z/U*"
    override val supported: Boolean = false
  }
  case object UNKNOWN extends AnonymizationOp {
    override val description: String = "unknown anonymization operation"
    override val code: String = ""
    override val supported: Boolean = false
  }

  def list = List(DUMMY, ZERO, REMOVE, CLEAN, REPLACE_UID, ZERO_OR_DUMMY, REMOVE_OR_ZERO, REMOVE_OR_DUMMY, REMOVE_OR_ZERO_OR_DUMMY, REMOVE_OR_ZERO_OR_REPLACE_UID)

  def forCode(code: String): AnonymizationOp = list.find(_.code == code).getOrElse(UNKNOWN)
}
