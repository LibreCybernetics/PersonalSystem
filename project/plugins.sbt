// 1.1.0 crashes while splicing mutations into this project's Scala 3.8 sources.
addSbtPlugin("io.stryker-mutator" % "sbt-stryker4s" % "1.0.3")

// Generates per-module and aggregate statement/branch coverage reports for the full test gate.
addSbtPlugin("org.scoverage" % "sbt-scoverage" % "2.4.4")
