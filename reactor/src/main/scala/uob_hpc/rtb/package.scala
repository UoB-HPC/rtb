package uob_hpc.rtb

import better.files.File

case class CacheEntry(size: Long, filename: String)
object CacheEntry {
  import Pickler.*
  given ReadWriter[CacheEntry] = macroRW
}

trait CompilerProvider {
  def name: String
  def cacheKeys(wd: File, limit: Int): Vector[(Key, String, Long)]
}

enum Runner {
  case Local(template: Option[File]) extends Runner
  case PbsTorque(template: File) extends Runner
}
