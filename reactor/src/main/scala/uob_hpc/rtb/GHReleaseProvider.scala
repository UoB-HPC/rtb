package uob_hpc.rtb

import  uob_hpc.rtb.{given}

import better.files.File
import org.eclipse.jgit.api.Git

import java.net.URI
import java.time.LocalDate
import scala.jdk.CollectionConverters.*
import scala.util.{Failure, Success, Try}

class GHReleaseProvider(
    val name: String,
    repoAndOwner: String,
    tagsToKeys: Vector[String] => Vector[(String, Key)],
    tagAndKeyToAssetUri: (String, Key) => String,
    pathAndKeyToPrelude: (File, Key) => String
) extends CompilerProvider {

  type Manifest = Vector[(Key, CacheEntry)]

  override def cacheKeys(wd: File, limit: Int): Vector[(Key, String, Long)] = {
    val allTags = Git
      .lsRemoteRepository()
      .setTags(true)
      .setHeads(false)
      .setRemote(s"https://github.com/$repoAndOwner.git")
      .call()
      .asScala
      .map(_.getName.stripPrefix("refs/tags/"))
      .toVector

    val keyWithAsset = tagsToKeys(allTags).take(limit)
      .map { case (tag, key) =>
        key -> URI(s"https://github.com/$repoAndOwner/releases/download/$tag/${tagAndKeyToAssetUri(tag, key)}")
      }

    val cacheDir = (wd / name).createDirectoryIfNotExists(createParents = true)
    println(s"Total tags:   ${allTags.size} ")
    println(s"Release tags: ${keyWithAsset.size} ")
    println(s"Cache dir:    $cacheDir")

    val manifestFile = cacheDir / "manifest.json"
    val (validated, pending) = if (manifestFile.isRegularFile) {
      Try(Pickler.read[Manifest](manifestFile.path).toMap) match {
        case Failure(exception) =>
          Console.err.println(s"Skipping corrupted manifest: $exception")
          Vector.empty -> keyWithAsset
        case Success(manifest) =>
          println(s"Found valid manifest with ${manifest.size} entries, validating...")
          // If asset is in manifest, check size, else prepare for fetch
          val result @ (validated, pending) = keyWithAsset.partitionMap { case p @ (key, _) =>
            manifest.get(key) match {
              case Some(CacheEntry(size, filename)) =>
                val file = cacheDir / filename
                if (file.isRegularFile && file.size == size) Left(key -> file)
                else Right(p)
              case None => Right(p)
            }
          }
          println(s"Validated ${validated.size} entries, ${pending.size} requires caching")
          result
      }
    } else Vector.empty -> keyWithAsset

    val fetched =
      if (pending.isEmpty) Vector.empty
      else {
        println(s"Starting download...")
        val data = pending.map { case (key, uri) =>
          (uri, cacheDir / s"${key.formatted}.${File(uri.getPath).name}", key)
        }
        Rest.visualDownload[Key](data, 8).partitionMap(identity) match {
          case (Vector(), xs) =>
            // Save the manifest first
            val manifest = xs.map { case (p, key) => key -> CacheEntry(p.size, p.name) }.toMap
            manifestFile.createFileIfNotExists().writeText(Pickler.write(manifest))
            println(s"Storing manifest with ${manifest.size} entries...")
            xs.map { case (p, key) => (key, pathAndKeyToPrelude(p, key), p.size) }
          case (errors, _) =>
            throw new RuntimeException(s"Some keys failed to download: \n${errors.map(e => s" * $e\n")}")
        }
      }

    validated.map { case (tag, p) => (tag, pathAndKeyToPrelude(p, tag), p.size) } ++ fetched
  }
}
