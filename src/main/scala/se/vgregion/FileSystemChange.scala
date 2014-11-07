package se.vgregion

import java.io.File
import java.nio.file.Path

sealed trait FileSystemChange

case class Created(fileOrDir: File) extends FileSystemChange

case class Deleted(fileOrDir: File) extends FileSystemChange

case class MonitorDir(path: Path)