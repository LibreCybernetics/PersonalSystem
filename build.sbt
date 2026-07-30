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

// The formal semantic language (SPEC §3.1): identifiers, literals, OWL-style axioms, annotations,
// temporal statements, and their stable serialized representation.
lazy val logic = project
  .in(file("modules/logic"))
  .settings(commonSettings)
  .settings(
    name := "noesis-logic",
    libraryDependencies ++= Seq(
      "org.typelevel" %% "cats-core" % "2.13.0",
      "org.typelevel" %% "cats-effect" % catsEffectV,
      "io.circe" %% "circe-core" % circeV,
      "io.circe" %% "circe-parser" % circeV,
      "io.circe" %% "circe-generic" % circeV
    )
  )

// The append-only source of truth (SPEC §3.2): operation protocol, sequencing, and JSONL backends.
lazy val journal = project
  .in(file("modules/journal"))
  .dependsOn(logic)
  .settings(commonSettings)
  .settings(
    name := "noesis-journal",
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

// Pure inference and query services (SPEC §3.4), including load-bearing justification tracking.
lazy val reasoner = project
  .in(file("modules/reasoner"))
  .dependsOn(logic)
  .settings(commonSettings)
  .settings(
    name := "noesis-reasoner",
    libraryDependencies ++= Seq(
      "org.typelevel" %% "cats-core" % "2.13.0",
      "io.circe" %% "circe-core" % circeV,
      "io.circe" %% "circe-generic" % circeV
    )
  )

// The Knowledge Core (SPEC §3): composes the foundational modules with projections, capture,
// policy, events, and verbalization. Knows nothing about learning or vocabulary modules.
lazy val core = project
  .in(file("modules/core"))
  .dependsOn(logic, journal, reasoner)
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
  .dependsOn(logic, reasoner, core % "compile->compile;test->test")
  .settings(commonSettings)
  .settings(name := "noesis-lms")

// Vocabulary modules (SPEC §5–§8): core upper ontology, crm:, ll:, vf:.
lazy val vocab = project
  .in(file("modules/vocab"))
  .dependsOn(logic, reasoner, core % "compile->compile;test->test", lms)
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
  .dependsOn(logic, journal, reasoner, core, lms, vocab)
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

/** Conformance to the normative references, as opposed to conformance to our own intentions.
  *
  * A separate module for two reasons, both about the mutation gate. Dropping external corpora into
  * a gated module's own suite would inflate its coverage: broad conformance cases kill mutants
  * incidentally, and a 100% score would stop meaning "the unit suite pins this behavior" — an
  * erosion that is silent and not recoverable once it starts. And the corpus infrastructure here
  * (manifest loading, the I-JSON checker) is test scaffolding with a large mutation surface and
  * no product contract to justify holding it at 100%.
  *
  * So this module is deliberately absent from the Stryker matrix in `.github/workflows/mutation.yml`
  * and is gated on `modules/conformance/DEVIATIONS.md` instead: every case that does not pass must
  * be a recorded, justified deviation rather than a quietly skipped test.
  */
lazy val conformance = project
  .in(file("modules/conformance"))
  // `vocab` is here for one reason: ISO/IEC 11179-5 conformance is a claim about the names the
  // shipped vocabularies actually declare, so the corpus has to reach the modules themselves.
  .dependsOn(logic, journal, reasoner, core, vocab)
  .settings(commonSettings)
  .settings(
    name := "noesis-conformance",
    publish / skip := true,
    libraryDependencies ++= Seq(
      "org.typelevel" %% "cats-core" % "2.13.0",
      "io.circe" %% "circe-core" % circeV,
      "io.circe" %% "circe-parser" % circeV,
      "io.circe" %% "circe-generic" % circeV
    )
  )

lazy val root = project
  .in(file("."))
  .aggregate(logic, journal, reasoner, core, lms, vocab, cli, conformance)
  .settings(
    name := "noesis",
    publish / skip := true
  )
