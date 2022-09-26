package uob_hpc.rtb

import better.files.File
import uob_hpc.rtb.Pickler

import java.math.BigInteger
import java.net.URI
import java.net.http.HttpRequest.BodyPublishers
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.{Executors, ThreadFactory}
import scala.concurrent.duration.{Duration, FiniteDuration}
import scala.concurrent.{Await, ExecutionContext, Future}
import scala.util.Using

object Rest {

  private val BufferSize = 8192

  def fetch[T: Pickler.Reader](client: HttpClient, uri: String): Option[T] = {
    println(s"[fetch] $uri")
    val response = client
      .send[String](
        HttpRequest
          .newBuilder()
          .uri(URI(uri))
          .version(HttpClient.Version.HTTP_2)
          .GET()
          .build(),
        HttpResponse.BodyHandlers.ofString()
      )
    response.statusCode() match {
      case 200 => Some(Pickler.read[T](response.body()))
      case code =>
        Console.err.println(s"Cannot fetch $uri: $code")
        None
    }
  }

  private def retryN[T](f: => Future[T], retries: Int)(implicit ec: ExecutionContext): Future[T] =
    f.recoverWith { case _ if retries > 0 => retryN(f, retries - 1) }

  // Scala version of http://stackoverflow.com/questions/3758606/how-to-convert-byte-size-into-human-readable-format-in-java/3758880#3758880
  private inline def byteCount(inline bytes: Long, inline si: Boolean = false): String = {
    val unit = if (si) 1000 else 1024
    if (bytes < unit) bytes.toString + "B"
    else {
      val prefixes = if (si) "kMGTPE" else "KMGTPE"
      val exp      = (math.log(bytes.toDouble) / math.log(unit.toDouble)).toInt.min(prefixes.length)
      val pre      = prefixes.charAt(exp - 1).toString + (if (si) "" else "i")
      f"${bytes / math.pow(unit.toDouble, exp.toDouble)}%.1f ${pre}B"
    }
  }

  private inline def mkFixedThreadPoolEC(inline n: Int) = ExecutionContext.fromExecutorService(
    Executors.newFixedThreadPool(
      n,
      new ThreadFactory {
        val id = AtomicLong(0)
        override def newThread(r: Runnable) = {
          val t = Thread(r); t.setName(s"download-${id.getAndIncrement()}"); t.setDaemon(true); t
        }
      }
    )
  )

  def visualDownload[A](xs: Vector[(URI, File, A)], parallel: Int): Vector[Either[Throwable, (File, A)]] = {
    val totalPieces     = xs.size
    val completedBytes  = AtomicLong(0)
    val completedPieces = AtomicLong(0)
    import java.net.http.HttpClient.Redirect
    import java.net.http.HttpResponse.BodyHandlers
    import java.net.http.{HttpClient, HttpRequest, HttpResponse}
    import scala.jdk.FutureConverters.*
    given ExecutionContext = mkFixedThreadPoolEC(parallel)

    def mkClient = HttpClient
      .newBuilder()
      .followRedirects(Redirect.ALWAYS)
      .build

    print("Resolving total size... ")
    val sizeResolved = Await.result(
      {
        given ExecutionContext = mkFixedThreadPoolEC(64) // make sure we don't exceed JDK's stream limit
        val client             = mkClient
        Future.traverse(xs) { case (uri, path, x) =>
          Future {
            val response = client.send(
              HttpRequest.newBuilder(uri).method("HEAD", BodyPublishers.noBody()).build,
              BodyHandlers.discarding()
            )
            val size = response.headers().firstValueAsLong("Content-Length").orElse(0)
            (uri, path, size, x)
          }
        }
      },
      Duration.Inf
    )
    val totalBytes = sizeResolved.map(_._3).sum
    println(byteCount(totalBytes))
    val tasks = Future.traverse(sizeResolved) { case (uri, path, expected, x) =>
      Future {
        if (path.isRegularFile && path.size == expected) { // cache hit!
          completedBytes.getAndAdd(expected)
          completedPieces.getAndIncrement()
          println(s"Cache hit: $path")
          Future.successful(path -> x)
        } else {
          retryN(
            Future {
              val response = mkClient.send(HttpRequest.newBuilder(uri).build, BodyHandlers.ofInputStream)
              val tmp      = path.sibling(s".${path.name}.tmp")
              println(s"$response => $tmp")
              Using.resource(tmp.newOutputStream) { os =>
                val is     = response.body()
                val buffer = Array.ofDim[Byte](BufferSize)
                var read   = 0
                var abort  = false
                while (                                                //
                  { abort = Thread.interrupted(); !abort } &&          //
                  { read = is.read(buffer, 0, BufferSize); read >= 0 } //
                ) { os.write(buffer, 0, read); completedBytes.addAndGet(read) }

                if (abort) { // user cancel, clean up
                  tmp.delete(swallowIOExceptions = true)
                  throw RuntimeException(s"Thread interrupted ($response)")
                } else {
                  completedPieces.getAndIncrement()
                  val actual = tmp.size
                  if (path.exists) {
                    println(s"$path already exists, deleting...")
                    path.delete(swallowIOExceptions = true)
                  }
                  if (actual == expected) tmp.moveTo(path) -> x
                  else {
                    println(s"Expecting size $expected but was $actual ($response)")
                    throw RuntimeException(s"Expecting size $expected but was $actual ($response)")
                  }
                }
              }
            },
            2
          )
        }
      }.flatten.transformWith(v => Future.successful(v.toEither))
    }
    val start         = System.nanoTime()
    var lastCompleted = 0L
    while (!tasks.isCompleted) {
      val pct        = completedBytes.get().toDouble / totalBytes
      val progressN  = 25
      val progress   = List.tabulate(progressN)(i => if ((i.toDouble / progressN) < pct) "#" else " ").mkString
      val pieces     = s"$completedPieces/$totalPieces"
      val total      = s"${byteCount(completedBytes.get())}/${byteCount(totalBytes)}"
      val elapsedSec = ((System.nanoTime() - start) / 1e9).round
      val rate       = byteCount(completedBytes.get() - lastCompleted)
      print(s"[$progress] ${(pct * 100).round}% ($total@$rate/s; #=$pieces; T+${elapsedSec}s)\r")
      lastCompleted = completedBytes.get()
      Thread.sleep(1000)
    }
    println("Done")
    Await.result(tasks, Duration.Inf)
  }

}
