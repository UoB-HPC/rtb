package uob_hpc.rtb

import better.files.File

import java.net.InetAddress
import java.time.format.DateTimeFormatter
import java.time.temporal.IsoFields
import java.time.{LocalDate, ZoneOffset}
import scala.collection.immutable.{ArraySeq, TreeMap}
import scala.collection.mutable
import scala.sys.process.{Process, ProcessLogger}
import scala.util.Try

object Reactor {

  import org.rogach.scallop.*

  given ValueConverter[Runner] = singleArgConverter {
    case s"local:$template" => Runner.Local(Some(File(template)))
    case "local"            => Runner.Local(None)
    case s"pbs:$template"   => Runner.PbsTorque(File(template))
    case bad                => throw new IllegalArgumentException(s"Unsupported runner: $bad")
  }
  given ValueConverter[File] = singleArgConverter(File(_))

  given ValueConverter[Vector[String]] = singleArgConverter(_.split(",").toVector)

  private val Hostname = InetAddress.getLocalHost.getHostName

  private class Config(arguments: Seq[String]) extends ScallopConf(arguments) {
    val input: ScallopOption[File] = opt( //
      default = Some(File("./input")),
      descr = "Input dir for job scripts"
    )
    val output: ScallopOption[File] = opt( //
      default = Some(File(s"./dataset-$Hostname")),
      descr = "Output dir for job results"
    )
    val scratch: ScallopOption[File] = opt( //
      default = Some(File(s"/dev/shm/scratch-$Hostname")),
      descr = "Work dir for running the jobs on"
    )
    val cache: ScallopOption[File] = opt( //
      default = Some(File(s"./cache/${sys.props("os.arch")}")),
      descr = "Cache dir for storing providers"
    )
    val repeat: ScallopOption[Int] = opt( //
      default = Some(4),
      descr = "How many iterations of the timed section in a job is executed"
    )
    val runner: ScallopOption[Runner] = opt( //
      default = Some(Runner.Local(None)),
      descr = s"Runner for executing the job, either local or pbs:<path_to_job_template>"
    )
    val providers: ScallopOption[Vector[String]] = opt( //
      default = Some(SnapshotProviders.All.map(_.name)),
      descr = s"CSV list of providers to use, available providers: ${SnapshotProviders.All.map(_.name).mkString(",")}"
    )
    val limit: ScallopOption[Int] = opt( //
      default = Some(-1),
      descr = s"Limit the amount of keys to use for all providers, use -1 for unlimited"
    )
    verify()
  }

  private case class JobSink(dir: File) {
    def jobFile: File    = (dir / "input.job.sh").createFileIfNotExists()
    def execFile: File   = (dir / "exec.job").createFileIfNotExists()
    def logFile: File    = (dir / "log.txt").createFileIfNotExists()
    def fixtureDir: File = (dir / "fixture").createDirectoryIfNotExists()
    def item(k: Key)     = JobItem((dir / k.formatted).createDirectoryIfNotExists())
  }

  private case class JobItem(dir: File) {
    def timeFile(n: String): File = dir / s"time.$n.json"
    def execFile: File            = (dir / "exec.job").createFileIfNotExists()
    def logFile: File             = (dir / "log.txt").createFileIfNotExists()
    def errorFile: File           = (dir / "log.err.txt").createFileIfNotExists()
  }

  private def mkRunScripts(
      sink: JobSink,
      scratchDir: File,
      N: Int,
      keyAndPrelude: ArraySeq[((Key, Int), String)]
  ): ArraySeq[((Key, Int), JobItem, String)] = keyAndPrelude.map { case (keyAndIdx @ (key, _), prelude) =>
    // Don't create the workdir now because the script might be executed on a different node.
    val jobWorkDir = scratchDir / key.formatted
    val jobItem    = sink.item(key)
    (
      keyAndIdx,
      jobItem,
      s"""
          |set -eu
          |TIMEFORMAT='{"realS":%R,"userS":%U,"systemS":%S}'
          |(
          |
          |  rm -rf "$scratchDir"
          |  mkdir -p "$jobWorkDir"
          |  cd "$jobWorkDir" || (echo "Cannot enter $jobWorkDir" && exit 1)
          |
          |  echo "# restore fixture" >"${jobItem.logFile}"
          |  cp -a "${sink.fixtureDir}"/. .  &>>"${jobItem.logFile}"
          |
          |  echo "# prime" >>"${jobItem.logFile}"
          |  {
          |    ${prelude.linesIterator.map(" " * 4 + _).mkString("\n")}
          |  } &>>"${jobItem.logFile}"
          |
          |  if [[ $$(type -t _rtb_extra_flags) == function ]]; then
          |    export RTB_EXTRA_FLAGS=$$(_rtb_extra_flags)
          |  else
          |    export RTB_EXTRA_FLAGS=""
          |  fi
          |  echo "# RTB_CXX        =$$RTB_CXX"         &>>"${jobItem.logFile}"
          |  echo "# RTB_CC         =$$RTB_CC"          &>>"${jobItem.logFile}"
          |  echo "# RTB_EXTRA_FLAGS=$$RTB_EXTRA_FLAGS" &>>"${jobItem.logFile}"
          |
          |
          |  source "${sink.jobFile}"
          |
          |  echo "# setup" >>"${jobItem.logFile}"
          |  _setup        &>>"${jobItem.logFile}"
          |
          |  sync
          |
          |  for i in {1..$N}; do
          |    echo "# run $$i" >>"${jobItem.logFile}"
          |    (
          |      (time _test &>>"${jobItem.logFile}") &>"${jobItem.timeFile("$i")}"
          |    )
          |  done
          |
          |  echo "# check" >>"${jobItem.logFile}"
          |  _check        &>>"${jobItem.logFile}"
          |
          |  rm -rf "$jobWorkDir"
          |
          |)
          |""".stripMargin
    )
  }

  private def readEntry(keyIdx: Int, item: JobItem, N: Int) = {
    val times = item.dir
      .list(_.name match {
        case s"time.${_}.json" => true
        case _                 => false
      })
      .flatMap(f => Try(Pickler.read[Time](f.path)).toOption)
      .to(ArraySeq)
    if (!item.logFile.isRegularFile) {
      item.errorFile.writeText(s"Log file ${item.logFile} does not exist or is not a regular file.")
      Left(keyIdx)
    } else if (times.size != N) {
      item.errorFile.writeText(
        s"Expecting $N time entries, but only ${times.size} parsed correctly, the script likely terminated early."
      )
      Left(keyIdx)
    } else Right(Entry[Int](key = keyIdx, times = times))
  }

  private def pbsPollJobsCompleted(jobs: Seq[String]): Int = {
    val outputLns = mutable.ArrayBuffer[String]()
    // exit code isn't important here
    Process(s"qstat -a ${jobs.mkString(" ")}") ! ProcessLogger(outputLns += _)
    jobs.count { job =>
      !outputLns.exists(_.contains(job)) ||
      outputLns.exists(_.contains(s"$job Job has finished")) ||
      outputLns.exists(_.contains(s"Unknown Job Id $job"))
    }
  }

  private def pbsAwaitJobsCompleted(pbsJobIds: Seq[String], delayMs: Int = 1000): Unit = {
    var completedJobs = 0
    while (completedJobs != pbsJobIds.size) {
      completedJobs = pbsPollJobsCompleted(pbsJobIds)
      print(s"Waiting for jobs to complete ($completedJobs/${pbsJobIds.size})\r")
      Thread.sleep(delayMs)
    }
  }

  private def appendToAndDeleteIfExists(from: File, prefix: => String, to: File) = if (from.isRegularFile) {
    to.appendLines(prefix)
    from.lineIterator.foreach(to.appendLines(_))
    from.delete()
  }

  private def execJob(
      jobFile: File,
      keyAndPreludeFns: ArraySeq[((Key, String, Long), Int)],
      output: File,
      scratch: File,
      N: Int,
      runner: Runner
  ) = {
    val jobPredicates: Vector[(String, String) => Boolean] = jobFile.lineIterator
      .collect { case s"${_}#${_}RTB ${requirements}" => requirements.split(" ") }
      .flatten
      .distinct
      .toVector
      .map { requirement => (actual: String, actualVer: String) =>
        import math.Ordered.orderingToOrdered
        requirement match {
          case s"$expected==$expectedVer" => expected == actual && actualVer.toIntOption == expectedVer.toIntOption
          case s"$expected!=$expectedVer" => expected == actual && actualVer.toIntOption != expectedVer.toIntOption
          case s"$expected>=$expectedVer" => expected == actual && actualVer.toIntOption >= expectedVer.toIntOption
          case s"$expected<=$expectedVer" => expected == actual && actualVer.toIntOption <= expectedVer.toIntOption
          case s"$expected>$expectedVer"  => expected == actual && actualVer.toIntOption > expectedVer.toIntOption
          case s"$expected<$expectedVer"  => expected == actual && actualVer.toIntOption < expectedVer.toIntOption
          case expected                   => expected == actual
        }
      }
    val sink = JobSink((output / jobFile.nameWithoutExtension).createDirectoryIfNotExists())
    println(s"Running $jobFile")

    // Job file invalidated, delete everything and start fresh.
    val jobFileContent = jobFile.contentAsString
    if (sink.jobFile.contentAsString != jobFileContent) {
      println(s"\tJob file changed, clearing existing results.")
      sink.dir.clear()
    } else println(s"\tJob file unchanged, continuing...")
    sink.jobFile.overwrite(jobFileContent)
    sink.execFile.writeText(
      s"""
           |
           |mkdir -p "${sink.fixtureDir}"
           |cd "${sink.fixtureDir}" || (echo "Cannot enter ${sink.fixtureDir}" && exit 1)
           |
           |source "${sink.jobFile}"
           |echo "# fixture" >>"${sink.logFile}"
           |_fixture &>>"${sink.logFile}"
           |""".stripMargin
    )
    val (exitCode, elapsed) = timed(Process(s"bash ${sink.execFile}").!)
    println(f"\t<fixture> => exit=$exitCode ($elapsed%.2f s)")
    val runScripts = mkRunScripts(
      sink = sink,
      scratchDir = scratch,
      N = N,
      keyAndPrelude = keyAndPreludeFns.collect {
        case ((k, prelude, _), i) if jobPredicates.exists(_(k.name, k.version)) => (k, i) -> prelude
      }
    )
    runner match {
      case Runner.Local(template) =>
        val (failures, results) = runScripts.partitionMap { case ((key, keyIdx), item, content) =>
          def executeJobItem() = {
            val outputLns = mutable.ArrayBuffer[String]()
            item.execFile.writeText(template.fold(content)(t => s"""
                 |${t.contentAsString}
                 |$content
                 |""".stripMargin))
            val (exitCode, elapsed) = timed(Process(s"bash ${item.execFile}") ! ProcessLogger(outputLns += _))
            println(
              f"\t${key.formatted} => exit=$exitCode ($elapsed%.2fs, $keyIdx/${runScripts.size}, ${keyIdx.toDouble / runScripts.size * 100}%.1f%%)"
            )
            if (exitCode != 0) {
              outputLns += s"# Process finished with exit code $exitCode"
              item.errorFile.writeText(outputLns.mkString("\n"))
              Left(keyIdx)
            } else readEntry(keyIdx, item, N)
          }
          if (item.execFile.contentAsString == content) {
            readEntry(keyIdx, item, N) match {
              case e @ Right(x) =>
                println(f"\t${key.formatted} => checkpoint: ok, #${x.key}"); e
              case Left(err) =>
                println(f"\t${key.formatted} => checkpoint: retrying, exit=$err")
                item.dir.clear() // Clean up first so we don't get leftover timings
                executeJobItem()
            }
          } else executeJobItem()
        }
        println(s"\nAll jobs completed: failures=${failures.size}, successes=${results.size}")
        Series[Int](sink.dir.name, sink.jobFile.name, sink.jobFile.sha256.toLowerCase, results, failures)
      case Runner.PbsTorque(template) =>
        val pbsPrelude = template.contentAsString
        val (skipped, pending) = runScripts.partition { case (_, item, content) =>
          item.execFile.contentAsString == content
        }
        val pbsJobIds = pending.map { case (_, item, content) =>
          val jobFile = item.execFile.writeText(
            s"""
               |$pbsPrelude
               |
               |$content
               |""".stripMargin
          )
          Process(
            s"qsub -o ${item.dir / "log.pbs.txt"} -e ${item.dir / "log.pbs.err.txt"} -V $jobFile"
          ).lazyLines.head // id
        }
        println(s"Skipping jobs (${skipped.size}): ${skipped.map(_._1._1).mkString(",")}")
        println(s"Submitted jobs (${pbsJobIds.size}): ${pbsJobIds.mkString(",")}")
        pbsAwaitJobsCompleted(pbsJobIds) // wait for all tasks to complete by polling
        val (executedFailures, executedResults) = runScripts.partitionMap { case ((_, idx), sink, _) =>
          // concat pbs outputs with the main one
          appendToAndDeleteIfExists(sink.dir / "log.pbs.txt", "# PBS output", sink.logFile)
          appendToAndDeleteIfExists(sink.dir / "log.pbs.err.txt", "# PBS error", sink.errorFile)
          readEntry(idx, sink, N)
        }
        val (skippedFailures, skippedResults) = skipped.partitionMap { case ((_, keyIdx), item, _) =>
          readEntry(keyIdx, item, N)
        }
        println(s"""
                 |All jobs completed: 
                 |\tExecuted: Failure=${executedFailures.size}, Success=${executedResults.size}
                 |\tSkipped : Failure=${skippedFailures.size}, Success=${skippedResults.size}""".stripMargin)
        Series[Int](
          sink.jobFile.nameWithoutExtension,
          sink.jobFile.name,
          sink.jobFile.sha256,
          executedResults ++ skippedResults,
          executedFailures ++ skippedFailures
        )
    }
  }

  // dataset structure:
  //   $job.sh[]...
  //   runnerInfo.txt
  //   dataset.json
  //   $job/
  //      input.job.sh
  //      exec.job
  //      log.txt
  //      fixture/
  //        - ...
  //      $key/
  //        - exec.job
  //        - log.txt
  //        - time.$N.json
  private def execute(config: Config): Unit = {

    val allowedProviders = config.providers().toSet
    val ps               = SnapshotProviders.All.filter(p => allowedProviders.contains(p.name))

    println(s"Allowed providers: [${allowedProviders.toVector.sorted.mkString(",")}]")
    println(s"Caching keys for providers: [${ps.map(_.name).mkString(", ")}]")
    val keys = ps
      .flatMap(
        _.cacheKeys(
          config.cache(),
          config.limit() match {
            case -1 => Int.MaxValue
            case n  => n
          }
        )
      )
      .to(ArraySeq)
    val jobFilesGroups = config
      .input()
      .listRecursively
      .filter(_.extension(includeDot = false, includeAll = true).contains("job.sh"))
      .to(ArraySeq)
      .groupBy(x => config.input().relativize(x.parent))
      .map { case (p, n) =>
        (if (p.getNameCount == 1 && p.getRoot == null) config.input().name else p.toString) ->
          n.sortBy(_.name)
      }
      .to(TreeMap)

    if (config.output().isRegularFile) {
      Console.err.println(s"Output path ${config.output()} already exists and is not a directory")
      sys.exit(1)
    }

    config.scratch().createDirectoryIfNotExists(createParents = true).clear()
    config.output().createDirectoryIfNotExists(createParents = true)

    println(s"Collecting runnerInfo...")
    val runnerInfoFile = (config.output() / "runnerInfo.txt").createFileIfNotExists(createParents = true)
    val runnerInfoScript =
      """
        |hostname
		|uname -a
        |lscpu
        |df -h
        |""".stripMargin
    val runnerInfoJobFile = (config.output() / "runnerInfo.job").createFileIfNotExists(createParents = true)
    config.runner() match {
      case Runner.Local(template) =>
        runnerInfoJobFile.writeText(template.fold(runnerInfoScript)(t => s"""
             |${t.contentAsString}
             |$runnerInfoScript
             |""".stripMargin))
        val outputLns = mutable.ArrayBuffer[String]()
        Process(s"bash $runnerInfoJobFile") ! ProcessLogger(outputLns += _) // ignore errors here
        runnerInfoFile.writeText(outputLns.mkString("\n"))
      case Runner.PbsTorque(template) =>
        runnerInfoJobFile.writeText(s"""
             |${template.contentAsString}
             |$runnerInfoScript
             |""".stripMargin)
        val pbsId = Process(
          s"qsub -o ${config.output() / "runnerInfo.pbs.txt"} -e ${config.output() / "runnerInfo.pbs.err.txt"} -V $runnerInfoJobFile"
        ).lazyLines.head // id
        pbsAwaitJobsCompleted(Seq(pbsId))
        appendToAndDeleteIfExists(config.output() / "runnerInfo.pbs.txt", "# PBS output", runnerInfoFile)
        appendToAndDeleteIfExists(config.output() / "runnerInfo.pbs.err.txt", "# PBS error", runnerInfoFile)
    }

    println(s"Using job files:\n${jobFilesGroups.map("\t" + _).mkString("\n")}")

    // For each job file, create a run script and then execute them in serial
    val series = jobFilesGroups.map { case (group, jobFiles) =>
      group ->
        jobFiles.map(f =>
          execJob(
            jobFile = f,
            keyAndPreludeFns = keys.zipWithIndex,
            output = (config.output() / group).createDirectoryIfNotExists(),
            scratch = config.scratch(),
            N = config.repeat(),
            runner = config.runner()
          )
        )
    }

    val datasetFile = (config.output() / "dataset.json")
      .createFileIfNotExists()
      .writeText(Pickler.write(Dataset(keys.map { case (k, _, l) => k -> l }, series)))

    println(s"Dataset written to $datasetFile (${datasetFile.size / 1024} KB)")
  }

  def main(args: Array[String]): Unit = {
    sys.props += ("org.slf4j.simpleLogger.logFile" -> "System.out")
    val conf = new Config(args.toList)
    println("Arguments:")
    println(conf.filteredSummary(Set.empty))
    execute(conf)
  }
}
