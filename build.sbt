ThisBuild / organization := "ws.librecybernetics"
ThisBuild / version := "0.1.0-SNAPSHOT"
ThisBuild / scalaVersion := "3.8.4"

val catsEffectV = "3.6.3"
val fs2V = "3.12.2"
val circeV = "0.14.10"
val declineV = "2.5.0"
val munitV = "1.1.1"
val munitCatsEffectV = "2.1.0"
val scapegoatV = "3.3.6"
val wartremoverV = "3.6.1"

// Warts.unsafe includes inference/style checks whose Scala 3 implementations flag opaque-type
// interpolation and intentional API defaults. Keep the gate on concrete correctness hazards.
val wartremoverChecks = Seq(
  "ArrayEquals",
  "ArrayToString",
  "AsInstanceOf",
  "EitherProjectionPartial",
  "GlobalExecutionContext",
  "IsInstanceOf",
  "IterableOps",
  "JavaNetURLConstructors",
  "LeakingSealed",
  "MapContains",
  "MapUnit",
  "Null",
  "OptionPartial",
  "PlatformDefault",
  "Return",
  "StringPlusAny",
  "ThreadSleep",
  "Throw",
  "TripleQuestionMark",
  "TryPartial"
)

lazy val commonSettings = Seq(
  scalacOptions ++= Seq(
    "-deprecation",
    "-feature",
    "-unchecked",
    "-Werror",
    "-Wnonunit-statement",
    "-Wsafe-init",
    "-Wunused:all",
    "-Wvalue-discard",
    "-source:3.8"
  ),
  libraryDependencies ++= Seq(
    compilerPlugin(
      ("com.sksamuel.scapegoat" %% "scalac-scapegoat-plugin" % scapegoatV)
        .cross(CrossVersion.full)
    ),
    compilerPlugin(
      ("org.wartremover" %% "wartremover" % wartremoverV)
        .cross(CrossVersion.full)
    )
  ),
  Compile / scalacOptions ++= {
    val reportDir = (Compile / target).value / "scapegoat"
    Seq(
      "-Xplugin-require:scapegoat",
      "-Xplugin-require:wartremover",
      s"-P:scapegoat:dataDir:${reportDir.getAbsolutePath}",
      "-P:scapegoat:reports:xml",
      "-P:scapegoat:consoleOutput:true",
      "-P:scapegoat:minimalLevel:warning",
      // Scala 3 reports compiler-generated case-class equality for every Double field as source.
      "-P:scapegoat:disabledInspections:ComparingFloatingPointTypes"
    ) ++ wartremoverChecks.map(wart =>
      s"-P:wartremover:traverser:org.wartremover.warts.$wart"
    )
  },
  libraryDependencies ++= Seq(
    "org.scalameta" %% "munit" % munitV % Test,
    "org.typelevel" %% "munit-cats-effect" % munitCatsEffectV % Test
  ),
)

// The Knowledge Core (SPEC §3): journal, projections, reasoning, query,
// policy cascade, verbalization. Knows nothing about learning or modules.
lazy val core = project
  .in(file("modules/core"))
  .settings(commonSettings)
  .settings(
    name := "noesis-core",
    libraryDependencies ++= Seq(
      "org.typelevel" %% "cats-core" % "2.13.0",
      "org.typelevel" %% "cats-effect" % catsEffectV,
      "co.fs2" %% "fs2-core" % fs2V,
      "co.fs2" %% "fs2-io" % fs2V,
      "io.circe" %% "circe-core" % circeV,
      "io.circe" %% "circe-parser" % circeV,
      "io.circe" %% "circe-generic" % circeV
    )
  )

// The Learning Engine (SPEC §4): items, belief, scheduling, derived belief.
lazy val lms = project
  .in(file("modules/lms"))
  .dependsOn(core % "compile->compile;test->test")
  .settings(commonSettings)
  .settings(name := "noesis-lms")

// Vocabulary modules (SPEC §5–§8): core upper ontology, crm:, ll:, vf:.
lazy val vocab = project
  .in(file("modules/vocab"))
  .dependsOn(core % "compile->compile;test->test", lms)
  .settings(commonSettings)
  .settings(name := "noesis-vocab")

/** Writes an executable launcher, so the CLI can be driven directly instead of through `sbt run`.
  *
  * `sbt run` merges the arguments of several `run` invocations in one session, which makes a
  * multi-command scenario impossible to script. A launcher also matches how the tool is actually
  * meant to be used.
  */
lazy val launcher = taskKey[String]("write an executable launcher script for the CLI, returning its path")

lazy val cli = project
  .in(file("modules/cli"))
  .dependsOn(core, lms, vocab)
  .settings(commonSettings)
  .settings(
    name := "noesis-cli",
    libraryDependencies ++= Seq(
      "com.monovore" %% "decline" % declineV,
      "com.monovore" %% "decline-effect" % declineV
    ),
    // Uncached: the task's product is a file on disk, and sbt 2 caches by inputs. A cache hit would
    // report success without writing anything, which is exactly what happens after a `clean` — so the
    // documented way to get a launcher would silently do nothing.
    launcher := Def.uncached {
      val converter = fileConverter.value
      val classpath = (Runtime / fullClasspath).value
        .map(entry => converter.toPath(entry.data).toAbsolutePath.toString)
        .mkString(":")
      val script = target.value / "noesis"
      IO.write(
        script,
        // UTF-8 is forced explicitly, for input as well as output. Names, Cyrillic lexemes and
        // transliteration (SPEC §6, §7.2) are not optional output, and the JVM decodes *arguments*
        // using the platform locale — so under a C locale "Lía" is mangled before it is ever stored.
        // JAVA_HOME is preferred over PATH so the launcher runs on the JDK the flake pins. Falling
        // through to an arbitrary `java` works, but a newer JDK reports scala3-library's use of
        // sun.misc.Unsafe on stderr for every invocation, which is unusable noise for a CLI.
        s"""|#!/usr/bin/env bash
            |export LC_ALL="$${LC_ALL:-C.UTF-8}"
            |java="$${JAVA_HOME:+$$JAVA_HOME/bin/}java"
            |exec "$$java" \\
            |  -Dfile.encoding=UTF-8 -Dsun.jnu.encoding=UTF-8 \\
            |  -Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8 \\
            |  -cp "$classpath" noesis.cli.Main "$$@"
            |""".stripMargin
      )
      val _ = script.setExecutable(true)
      streams.value.log.info(s"launcher written to $script")
      script.getAbsolutePath
    }
  )

lazy val root = project
  .in(file("."))
  .aggregate(core, lms, vocab, cli)
  .settings(
    name := "noesis",
    publish / skip := true
  )
