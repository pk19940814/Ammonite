package ammonite.runtime

import java.io.File

import ammonite.ops.{read, _}
import ammonite.runtime.tools.IvyThing
import ammonite.util.Util.CodeSource
import ammonite.util._

/**
  * An extensible hook into the Ammonite REPL's import system; allows the end
  * user to hook into `import $foo.bar.{baz, qux => qua}` syntax, and in
  * response load jars or process source files before the "current" compilation
  * unit is run. Can be used to load script files, ivy dependencies, jars, or
  * files from the web.
  */
trait ImportHook{
  /**
    * Handle a parsed import that this import hook was registered to be interested in
    *
    * Note that `source` is optional; not every piece of code has a source. Most *user*
    * code does, e.g. a repl session is based in their CWD, a script has a path, but
    * some things like hardcoded builtin predefs don't
    */
  def handle(source: CodeSource,
             tree: ImportTree,
             interp: ImportHook.InterpreterInterface)
            : Either[String, Seq[ImportHook.Result]]
}

object ImportHook{

  /**
    * The minimal interface that is exposed to the import hooks from the
    * Interpreter. Open for extension, if someone needs more stuff, but by
    * default this is what is available.
    */
  trait InterpreterInterface{
    def loadIvy(coordinates: coursier.Dependency*): Either[String, Set[File]]
    def watch(p: Path): Unit
  }

  /**
    * The result of processing an [[ImportHook]]. Can be either a source-file
    * to evaluate, or additional files/folders/jars to put on the classpath
    */
  sealed trait Result
  object Result{
    case class Source(code: String,
                      codeSource: CodeSource,
                      hookImports: Imports,
                      exec: Boolean) extends Result
    case class ClassPath(file: Path, plugin: Boolean) extends Result
  }

  
  object File extends SourceHook(false)
  object Exec extends SourceHook(true)

  def resolveFiles(tree: ImportTree, currentScriptPath: Path, extensions: Seq[String])
                  : (Seq[(RelPath, Option[String])], Seq[Path], Seq[Path]) = {
    val relative =
      tree.prefix
        .map{case ammonite.util.Util.upPathSegment => up; case x => ammonite.ops.empty/x}
        .reduce(_/_)

    val relativeModules = tree.mappings match{
      case None => Seq(relative -> None)
      case Some(mappings) => for((k, v) <- mappings) yield relative/k -> v
    }

    def relToFile(relative: RelPath) = {
      val base = currentScriptPath/up/relative
      extensions.find(ext => exists! base/up/(relative.last + ext)) match{
        case Some(p) => Right(base/up/(relative.last + p): Path)
        case None => Left(base)
      }
    }

    val resolved = relativeModules.map(x => relToFile(x._1))
    val missing = resolved.collect{case Left(p) => p}
    val files = resolved.collect{case Right(p) => p}

    (relativeModules, files, missing)
  }
  class SourceHook(exec: Boolean) extends ImportHook {
    // import $file.foo.Bar, to import the file `foo/Bar.sc`
    def handle(source: CodeSource,
               tree: ImportTree,
               interp: InterpreterInterface) = {

      source.path match{
        case None => Left("Cannot resolve $file import in code without source")
        case Some(currentScriptPath) =>

          val (relativeModules, files, missing) = resolveFiles(
            tree, currentScriptPath, Seq(".sc")
          )

          files.foreach(interp.watch)
          missing.foreach(x => interp.watch(x/up/(x.last + ".sc")))
          if (missing.nonEmpty) {
            Left("Cannot resolve $file import: " + missing.map(_ + ".sc").mkString(", "))
          } else {
            Right(
              for(((relativeModule, rename), filePath) <- relativeModules.zip(files)) yield {

                val (flexiblePkg, wrapper) = Util.pathToPackageWrapper(
                  source.flexiblePkgName, filePath relativeTo currentScriptPath/up
                )

                val fullPrefix = source.pkgRoot ++ flexiblePkg ++ Seq(wrapper)

                val importData = Seq(ImportData(
                  fullPrefix.last, Name(rename.getOrElse(relativeModule.last)),
                  fullPrefix.dropRight(1), ImportData.TermType
                ))

                val codeSrc = CodeSource(
                  wrapper,
                  flexiblePkg,
                  source.pkgRoot,
                  Some(filePath)
                )

                Result.Source(
                  Util.normalizeNewlines(read(filePath)),
                  codeSrc,
                  Imports(importData),
                  exec
                )
              }
            )
          }
      }

    }
  }

  object Ivy extends BaseIvy(plugin = false)
  object PluginIvy extends BaseIvy(plugin = true)
  class BaseIvy(plugin: Boolean) extends ImportHook{
    def splitImportTree(tree: ImportTree): Either[String, Seq[String]] = {
      tree match{
        case ImportTree(Seq(part), None, _, _) => Right(Seq(part))
        case ImportTree(Nil, Some(mapping), _, _) if mapping.map(_._2).forall(_.isEmpty) =>
          Right(mapping.map(_._1))
        case _ => Left("Invalid $ivy import " + tree)
      }
    }
    def resolve(interp: InterpreterInterface, signatures: Seq[String]) = {
      val splitted = for (signature <- signatures) yield {
        signature.split(':') match{
          case Array(a, b, c) =>
            Right(coursier.Dependency(coursier.Module(a, b), c))
          case Array(a, "", b, c) =>
            Right(coursier.Dependency(coursier.Module(a, b + "_" + IvyThing.scalaBinaryVersion), c))
          case _ => Left(signature)
        }
      }
      val errors = splitted.collect{case Left(error) => error}
      val successes = splitted.collect{case Right(v) => v}
      if (errors.nonEmpty)
        Left("Invalid $ivy imports: " + errors.map(Util.newLine + "  " + _).mkString)
      else
        interp.loadIvy(successes: _*)
    }


    def handle(source: CodeSource,
               tree: ImportTree, 
               interp: InterpreterInterface) = for{
      signatures <- splitImportTree(tree).right
      resolved <- resolve(interp, signatures).right
    } yield resolved.map(Path(_)).map(Result.ClassPath(_, plugin)).toSeq
  }
  object Classpath extends BaseClasspath(plugin = false)
  object PluginClasspath extends BaseClasspath(plugin = true)
  class BaseClasspath(plugin: Boolean) extends ImportHook{
    def handle(source: CodeSource,
               tree: ImportTree, 
               interp: InterpreterInterface) = {
      source.path match{
        case None => Left("Cannot resolve $cp import in code without source")
        case Some(currentScriptPath) =>
          val (relativeModules, files, missing) = resolveFiles(
            tree, currentScriptPath, Seq(".jar", "")
          )

          if (missing.nonEmpty) Left("Cannot resolve $cp import: " + missing.mkString(", "))
          else Right(
            for(((relativeModule, rename), filePath) <- relativeModules.zip(files))
              yield Result.ClassPath(filePath, plugin)
          )
      }

    }
  }
}