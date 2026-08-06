package dev.librecybernetics.noesis.gui

import fs2.io.file.Path

import dev.librecybernetics.noesis.app.{OwnerProblem, Workspace}

/** Shared desktop boundary: both launchers accept exactly the same owner arguments. */
object DesktopArguments:
  def parse(args: List[String], executable: String): Either[OwnerProblem, Path] =
    args match
      case Nil => Right(Workspace.defaultRoot)
      case "--workspace" :: value :: Nil if value.trim.nonEmpty => Right(Path(value))
      case _ =>
        Left(
          OwnerProblem(
            "Noesis did not start",
            "the desktop accepts only --workspace PATH",
            s"run $executable --workspace /path/to/workspace"
          )
        )
