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
    case "local"          => Runner.Local
    case s"pbs:$template" => Runner.PbsTorque(File(template))
    case bad              => throw new IllegalArgumentException(s"Unsupported runner: $bad")
  }
  given ValueConverter[File] = singleArgConverter(File(_))

  given ValueConverter[Vector[String]] = singleArgConverter(_.split(",").toVector)

  private val Hostname  = InetAddress.getLocalHost.getHostName
  private val TodayDate = LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE)

  private class Config(arguments: Seq[String]) extends ScallopConf(arguments) {
    val input: ScallopOption[File] = opt( //
      default = Some(File("./input")),
      descr = "Input dir for job scripts"
    )
    val output: ScallopOption[File] = opt( //
      default = Some(File(s"./dataset-$Hostname-$TodayDate")),
      descr = "Output dir for job results"
    )
    val scratch: ScallopOption[File] = opt( //
      default = Some(File(s"/dev/shm/scratch-$Hostname-$TodayDate")),
      descr = "Work dir for running the jobs on"
    )
    val cache: ScallopOption[File] = opt( //
      default = Some(File(s"./cache/${sys.props("os.arch")}")),
      descr = "Cache dir for storing providers"
    )
    val repeat: ScallopOption[Int] = opt( //
      default = Some(5),
      descr = "How many iterations of the timed section in a job is executed"
    )
    val runner: ScallopOption[Runner] = opt( //
      default = Some(Runner.Local),
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
    lazy val execFile: File  = (dir / "exec.job").createFileIfNotExists()
    lazy val logFile: File   = (dir / "log.txt").createFileIfNotExists()
    lazy val errorFile: File = (dir / "log.err.txt").createFileIfNotExists()
  }

  private def mkRunScripts(
      jobFile: File,
      scratchDir: File,
      fixtureDir: File,
      outputDir: File,
      N: Int,
      keyAndPrelude: ArraySeq[((Key, Int), String)]
  ): ArraySeq[((Key, Int), JobSink, String)] = keyAndPrelude.map { case (keyAndIdx @ (key, _), prelude) =>
    val jobOutputDir = (outputDir / key.formatted).createDirectoryIfNotExists(createParents = true)
    // Don't create the workdir now because the script might be executed in a different node.
    val jobWorkDir = scratchDir / key.formatted

    (
      keyAndIdx,
      JobSink(jobOutputDir),
      s"""
          |set -eu
          |TIMEFORMAT='{"realS":%R,"userS":%U,"systemS":%S}'
          |(
          |
          |  rm -rf $jobWorkDir
          |  mkdir -p $jobWorkDir
          |  cd "$jobWorkDir" || (echo "Cannot enter $jobWorkDir" && exit 1)
          |
          |  echo "# restore fixture" >"$jobOutputDir/log.txt"
          |  cp -a $fixtureDir/. .  &>>"$jobOutputDir/log.txt"
          |
          |  echo "# prime" >>"$jobOutputDir/log.txt"
          |  {
          |    ${prelude.linesIterator.map(" " * 4 + _).mkString("\n")}
          |  } &>>"$jobOutputDir/log.txt"
		  |
		  |  if [[ $$(type -t _rtb_extra_flags) == function ]]; then
          |    export RTB_EXTRA_FLAGS=$$(_rtb_extra_flags)
          |  else
          |    export RTB_EXTRA_FLAGS=""
          |  fi
		  |  echo "# RTB_CXX        =$$RTB_CXX"         &>>"$jobOutputDir/log.txt"
		  |  echo "# RTB_CC         =$$RTB_CC"          &>>"$jobOutputDir/log.txt"
		  |  echo "# RTB_EXTRA_FLAGS=$$RTB_EXTRA_FLAGS" &>>"$jobOutputDir/log.txt"
		  |  
          |
          |  source $jobFile
          |
          |  echo "# setup" >>"$jobOutputDir/log.txt"
          |  _setup        &>>"$jobOutputDir/log.txt"
          |
          |  sync
          |
          |  for i in {1..$N}; do
          |    echo "# run $$i" >>"$jobOutputDir/log.txt"
          |    (
          |      (time _test &>>"$jobOutputDir/log.txt") &>"$jobOutputDir/time.$$i.json"
          |    )
          |  done
          |
          |  echo "# check" >>"$jobOutputDir/log.txt"
          |  _check        &>>"$jobOutputDir/log.txt"
          |
          |  rm -rf $jobWorkDir
          |
          |)
          |""".stripMargin
    )
  }

  private def readEntry(keyIdx: Int, sink: JobSink, N: Int) = {
    val times = sink.dir
      .list(_.name match {
        case s"time.${_}.json" => true
        case _                 => false
      })
      .flatMap(f => Try(Pickler.read[Time](f.path)).toOption)
      .to(ArraySeq)
    if (!sink.logFile.isRegularFile) {
      sink.errorFile.writeText(s"Log file ${sink.logFile} does not exist or is not a regular file.")
      Left(keyIdx)
    } else if (times.size != N) {
      sink.errorFile.writeText(
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

    println(s"Running $jobFile")

    jobFile.copyToDirectory(output)

    val jobOutputDir = (output / jobFile.nameWithoutExtension)
      .createDirectoryIfNotExists()
      .clear()

    val fixtureDir = jobOutputDir / "fixture"

    val fixtureJobFile = (jobOutputDir / "exec.job")
      .createFileIfNotExists(createParents = true)
      .writeText(
        s"""
           |
           |mkdir -p $fixtureDir
           |cd "$fixtureDir" || (echo "Cannot enter $fixtureDir" && exit 1)
           |
           |source $jobFile
           |echo "# fixture" >>"$jobOutputDir/log.txt"
           |_fixture &>>"$jobOutputDir/log.txt"
           |""".stripMargin
      )

    println("Setting up fixture...")
    Process(s"bash $fixtureJobFile").!

    val runScripts = mkRunScripts(
      jobFile = jobFile,
      scratchDir = scratch,
      fixtureDir = fixtureDir,
      outputDir = jobOutputDir,
      N = N,
      keyAndPrelude = keyAndPreludeFns.collect {
        case ((k, prelude, _), i) if jobPredicates.exists(_(k.name, k.version)) => (k, i) -> prelude
      }
    )

    runner match {
      case Runner.Local =>
        val (failures, results) = runScripts.partitionMap { case ((key, keyIdx), sink, content) =>
          val outputLns = mutable.ArrayBuffer[String]()
          val exitCode  = Process(s"bash ${sink.execFile.writeText(content)}") ! ProcessLogger(outputLns += _)
          println(s"\t${key.formatted} => $exitCode")
          if (exitCode != 0) {
            outputLns += s"# Process finished with exit code $exitCode"
            sink.errorFile.writeText(outputLns.mkString("\n"))
            Left(keyIdx)
          } else readEntry(keyIdx, sink, N)
        }
        println(s"\nAll jobs completed: failures=${failures.size}, successes=${results.size}")
        Series[Int](jobFile.nameWithoutExtension, jobFile.name, jobFile.sha256, results, failures)
      case Runner.PbsTorque(template) =>
        val pbsPrelude = template.contentAsString
        val pbsJobIds = runScripts.map { case (_, output, content) =>
          val jobFile = output.execFile.writeText(
            s"""
               |$pbsPrelude
               |
               |$content
               |""".stripMargin
          )
          Process(
            s"qsub -o ${output.dir / "log.pbs.txt"} -e ${output.dir / "log.pbs.err.txt"} -V $jobFile"
          ).lazyLines.head // id
        }
        println(s"Submitted jobs (${pbsJobIds.size}): ${pbsJobIds.mkString(",")}")
        pbsAwaitJobsCompleted(pbsJobIds) // wait for all tasks to complete by polling
        val (failures, results) = runScripts.partitionMap { case ((_, idx), sink, _) =>
          // concat pbs outputs with the main one
          appendToAndDeleteIfExists(sink.dir / "log.pbs.txt", "# PBS output", sink.logFile)
          appendToAndDeleteIfExists(sink.dir / "log.pbs.err.txt", "# PBS error", sink.errorFile)
          readEntry(idx, sink, N)
        }

        println(s"\nAll jobs completed: failure=${failures.size}, success=${results.size}")
        Series[Int](jobFile.nameWithoutExtension, jobFile.name, jobFile.sha256, results, failures)
    }
  }

  // dataset structure:
  // runnerInfo.txt
  // dataset.json
  // $job/$key
  //   - exec.job
  //   - log.txt
  //   - time.$N.json
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
    val keysWithIndex = keys.zipWithIndex

    val jobFilesGroups = config
      .input()
      .listRecursively
      .filter(_.extension(includeDot = false, includeAll = true).contains("job.sh"))
      .to(ArraySeq)
      .groupBy(x => config.input().relativize(x.parent))
      .map { case (p, n) => p.toString -> n.sortBy(_.name) }
      .to(TreeMap)

    if (config.output().isRegularFile) {
      Console.err.println(s"Output path ${config.output()} already exists and is not a directory")
      sys.exit(1)
    }

    config.output().createDirectoryIfNotExists(createParents = true).clear()
    config.scratch().createDirectoryIfNotExists(createParents = true).clear()

    println(s"Collecting runnerInfo...")
    val runnerInfoFile = (config.output() / "runnerInfo.txt").createFileIfNotExists(createParents = true)
    val runnerInfoScript =
      """
        |uname -a
        |lscpu
        |df -h
        |""".stripMargin
    val runnerInfoJobFile = (config.output() / "runnerInfo.job").createFileIfNotExists(createParents = true)
    config.runner() match {
      case Runner.Local =>
        runnerInfoJobFile.writeText(runnerInfoScript)
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

//    (Process("uname -a") #>> runnerInfoFile.toJava).!
//    (Process("lscpu") #>> runnerInfoFile.toJava).!
//    (Process("df -h") #>> runnerInfoFile.toJava).!

    println(s"Using job files:\n${jobFilesGroups.map("\t" + _).mkString("\n")}")

    // For each job file, create a run script and then execute them in serial
    val series = jobFilesGroups.map { case (group, jobFiles) =>
      group ->
        jobFiles.map(f =>
          execJob(
            jobFile = f,
            keyAndPreludeFns = keysWithIndex,
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
