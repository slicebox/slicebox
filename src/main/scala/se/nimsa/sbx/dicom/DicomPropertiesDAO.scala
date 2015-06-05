/*
 * Copyright 2015 Lars Edenbrandt
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

package se.nimsa.sbx.dicom

import scala.slick.driver.JdbcProfile
import scala.slick.jdbc.{ GetResult, StaticQuery => Q }
import DicomProtocol._
import DicomHierarchy._
import DicomPropertyValue._
import scala.slick.jdbc.meta.MTable

class DicomPropertiesDAO(val driver: JdbcProfile) {
  import driver.simple._

  val metaDataDao = new DicomMetaDataDAO(driver)
  import metaDataDao._

  // *** Files ***

  private val toImageFile = (id: Long, fileName: String) => ImageFile(id, FileName(fileName))

  private val fromImageFile = (imageFile: ImageFile) => Option((imageFile.id, imageFile.fileName.value))

  private class ImageFiles(tag: Tag) extends Table[ImageFile](tag, "ImageFiles") {
    def id = column[Long]("id", O.PrimaryKey)
    def fileName = column[String]("fileName")
    def * = (id, fileName) <> (toImageFile.tupled, fromImageFile)

    def imageFKey = foreignKey("imageFKey", id, imagesQuery)(_.id, onUpdate = ForeignKeyAction.Cascade, onDelete = ForeignKeyAction.Cascade)
    def imageIdJoin = imagesQuery.filter(_.id === id)
  }

  private val imageFilesQuery = TableQuery[ImageFiles]

  def create(implicit session: Session) =
    if (MTable.getTables("ImageFiles").list.isEmpty)
      (imageFilesQuery.ddl).create

  def drop(implicit session: Session) =
    if (MTable.getTables("ImageFiles").list.size > 0)
      (imageFilesQuery.ddl).drop

  def imageFileById(imageId: Long)(implicit session: Session): Option[ImageFile] =
    imageFilesQuery.filter(_.id === imageId).list.headOption

  def insert(imageFile: ImageFile)(implicit session: Session): ImageFile = {
    imageFilesQuery += imageFile
    imageFile
  }

  def imageFiles(implicit session: Session): List[ImageFile] = imageFilesQuery.list

  def imageFileForImage(imageId: Long)(implicit session: Session): Option[ImageFile] =
    imageFilesQuery
      .filter(_.id === imageId)
      .list.headOption

  def imageFilesForSeries(seriesId: Long)(implicit session: Session): List[ImageFile] =
    imagesForSeries(0, 100000, seriesId)
      .map(image => imageFileForImage(image.id)).flatten.toList

  def imageFilesForStudy(studyId: Long)(implicit session: Session): List[ImageFile] =
    seriesForStudy(0, Integer.MAX_VALUE, studyId)
      .map(series => imagesForSeries(0, 100000, series.id)
        .map(image => imageFileForImage(image.id)).flatten).flatten

  def imageFilesForPatient(patientId: Long)(implicit session: Session): List[ImageFile] =
    studiesForPatient(0, Integer.MAX_VALUE, patientId)
      .map(study => seriesForStudy(0, Integer.MAX_VALUE, study.id)
        .map(series => imagesForSeries(0, 100000, series.id)
          .map(image => imageFileForImage(image.id)).flatten).flatten).flatten

  def imageFileByFileName(imageFile: ImageFile)(implicit session: Session): Option[ImageFile] =
    imageFilesQuery
      .filter(_.fileName === imageFile.fileName.value)
      .list.headOption

  def deleteImageFile(imageId: Long)(implicit session: Session): Int = {
    imageFilesQuery
      .filter(_.id === imageId)
      .delete
  }

}
