import org.scalajs.linker.interface.{ESFeatures, ESVersion}

import java.nio.file.Files
import java.nio.file.StandardCopyOption.REPLACE_EXISTING

Global / onChangedBuildSource := ReloadOnSourceChanges

lazy val start = TaskKey[Unit]("start")
lazy val dist  = TaskKey[File]("dist")

lazy val scala3Version  = "3.2.0"
lazy val catsVersion    = "2.8.0"
lazy val upickleVersion = "2.0.0"
lazy val munitVersion   = "1.0.0-M5"

lazy val commonSettings = Seq(
  scalaVersion     := scala3Version,
  version          := "0.0.1-SNAPSHOT",
  organization     := "uk.ac.bristol.uob-hpc",
  organizationName := "University of Bristol",
//  scalacOptions ~= filterConsoleScalacOptions,
  javacOptions ++=
    Seq(
      "-parameters",
      "-Xlint:all"
    ) ++
      Seq("-source", "1.8") ++
      Seq("-target", "1.8"),
  scalacOptions ++= Seq(
//    "-explain",                              //
    "-no-indent",                                //
    "-Wconf:cat=unchecked:error",                //
    "-Wconf:name=MatchCaseUnreachable:error",    //
    "-Wconf:name=PatternMatchExhaustivity:error" //
    // "-language:strictEquality"
  ),
  scalafmtDetailedError := true,
  scalafmtFailOnErrors  := true
)

lazy val model = crossProject(JSPlatform, JVMPlatform)
  .settings(
    commonSettings,
    name := "model",
    libraryDependencies ++= Seq("com.lihaoyi" %%% "upickle" % upickleVersion)
  )

lazy val reactor = project
  .settings(
    commonSettings,
    name := "reactor",
    assemblyMergeStrategy := {
      case PathList("META-INF", "versions", "9", "module-info.class") => MergeStrategy.discard
      case x =>
        val oldStrategy = (ThisBuild / assemblyMergeStrategy).value
        oldStrategy(x)
    },
    libraryDependencies ++=
      Seq(
        "org.slf4j"               % "slf4j-simple"               % "2.0.2",
        "org.eclipse.jgit"        % "org.eclipse.jgit"           % "6.3.0.202209071007-r",
        ("com.github.pathikrit"  %% "better-files"               % "3.9.1").cross(CrossVersion.for3Use2_13),
        "org.scala-lang.modules" %% "scala-parallel-collections" % "1.0.4",
        "org.rogach"             %% "scallop"                    % "4.1.0",
        "com.lihaoyi"            %% "upickle"                    % upickleVersion,
        "org.scalameta"          %% "munit"                      % "0.7.29" % Test
      )
  )
  .dependsOn(model.jvm)

lazy val webapp = project
  .enablePlugins(ScalaJSPlugin, ScalablyTypedConverterPlugin)
  .settings(
    commonSettings,
    name                            := "webapp",
    scalaJSUseMainModuleInitializer := true,
    Compile / watchTriggers += (baseDirectory.value / "src/main/js/public").toGlob / "*.*",
    scalaJSLinkerConfig ~= ( //
      _.withSourceMap(false)
        .withModuleKind(ModuleKind.CommonJSModule)
        .withESFeatures(ESFeatures.Defaults.withESVersion(ESVersion.ES2015))
        .withParallel(true)
    ),
    useYarn                         := true,
    webpackDevServerPort            := 8001,
    stUseScalaJsDom                 := true,
    webpack / version               := "5.73.0",
    webpackCliVersion               := "4.10.0",
    startWebpackDevServer / version := "4.9.3",
    Compile / fastOptJS / webpackExtraArgs += "--mode=development",
    Compile / fullOptJS / webpackExtraArgs += "--mode=production",
    Compile / fastOptJS / webpackDevServerExtraArgs += "--mode=development",
    Compile / fullOptJS / webpackDevServerExtraArgs += "--mode=production",
    webpackConfigFile := Some((ThisBuild / baseDirectory).value / "webpack.config.mjs"),
    libraryDependencies ++= Seq(
      "org.scala-js"      %%% "scalajs-dom"       % "2.3.0",
      "io.github.cquiroz" %%% "scala-java-time"   % "2.4.0", // ignore timezones
      "com.raquo"         %%% "laminar"           % "0.14.2",
      "com.raquo"         %%% "waypoint"          % "0.5.0",
      "io.github.pityka"  %%% "nspl-scalatags-js" % "0.9.0"
    ),
    stIgnore ++= List(
      "node",
      "bulma",
      "@fortawesome/fontawesome-free"
    ),
    Compile / npmDependencies ++= Seq(
      // CSS and layout
      "@fortawesome/fontawesome-free" -> "5.15.4",
      "bulma"                         -> "0.9.4",
      "@types/prismjs"                -> "1.26.0",
      "prismjs"                       -> "1.29.0"
    ),
    Compile / npmDevDependencies ++= Seq(
      "webpack-merge"            -> "5.8.0",
      "css-loader"               -> "6.7.1",
      "style-loader"             -> "3.3.1",
      "file-loader"              -> "6.2.0",
      "url-loader"               -> "1.1.2",
      "html-loader"              -> "4.1.0",
      "remark"                   -> "14.0.2",
      "remark-loader"            -> "5.0.0",
      "remark-toc"               -> "8.0.1",
      "remark-parse"             -> "10.0.1",
      "remark-rehype"            -> "10.1.0",
      "rehype-autolink-headings" -> "6.1.1",
      "rehype-slug"              -> "5.0.1",
      "rehype-stringify"         -> "9.0.3"
    )
  )
  .settings(
    start := {
      (Compile / fastOptJS / startWebpackDevServer).value
    },
    dist := {
      val artifacts      = (Compile / fullOptJS / webpack).value
      val artifactFolder = (Compile / fullOptJS / crossTarget).value
      val distFolder     = (ThisBuild / baseDirectory).value / "docs"

      distFolder.mkdirs()
      IO.deleteFilesEmptyDirs(Seq(distFolder.file))

      artifacts.foreach { artifact =>
        val target = artifact.data.relativeTo(artifactFolder) match {
          case None          => distFolder / artifact.data.name
          case Some(relFile) => distFolder / relFile.toString
        }
        IO.copy(
          Seq(artifact.data.file -> target),
          overwrite = true,
          preserveLastModified = true,
          preserveExecutable = false
        )
      }

      val index           = "index.html"
      val publicResources = baseDirectory.value / "src/main/js/public/"

      Files.list(publicResources.toPath).filter(_.getFileName.toString != index).forEach { p =>
        Files.copy(p, (distFolder / p.getFileName.toString).toPath, REPLACE_EXISTING)
      }

      val indexFrom = publicResources / index
      val indexTo   = distFolder / index

      val indexPatchedContent = {
        import collection.JavaConverters._
        Files
          .readAllLines(indexFrom.toPath, IO.utf8)
          .asScala
          .map(_.replaceAllLiterally("-fastopt-", "-opt-"))
          .mkString("\n")
      }

      Files.write(indexTo.toPath, indexPatchedContent.getBytes(IO.utf8))
      distFolder
    }
  )
  .dependsOn(model.js)

lazy val root = project
  .in(file("."))
  .settings(commonSettings)
  .aggregate(model.jvm, reactor, model.js, webapp)
