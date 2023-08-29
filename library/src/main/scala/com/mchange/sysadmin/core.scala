package com.mchange.sysadmin

import scala.collection.*
import com.mchange.codegenutil.*

class SysadminException( message : String, cause : Throwable = null ) extends Exception(message, cause)

def extractFullStackTrace(t:Throwable) : String =
  val sw = new java.io.StringWriter()
  t.printStackTrace(new java.io.PrintWriter(sw))
  sw.toString()

extension (t : Throwable)
  def fullStackTrace : String = extractFullStackTrace(t)

def defaultVerticalMessage( run : Task.Run ) =
  // SEQUENTIAL and CLEANUPS start with two newlines,
  // the output does not have those long sections on the same
  // lines as their tags
  s"""|==================================
      | TASK: ${run.task.name}
      |==================================
      |
      | Succeeded overall? ${run.success}
      |
      | SEQUENTIAL: ${defaultVerticalMessageSequential(run.sequential)}
      |
      |
      | CLEANUPS: ${defaultVerticalMessageBestAttemptCleanups(run.bestAttemptCleanUps)}
      |
      |==================================""".stripMargin.trim

def defaultVerticalMessageSequential( sequential : List[Step.Run] ) : String =
  val tups = immutable.LazyList.from(1).zip(sequential)
  tups.foldLeft(""): (accum, next) =>
    accum + (LineSep*2) + defaultVerticalMessage(next)

def defaultVerticalMessageBestAttemptCleanups( bestAttemptCleanups : List[Step.Run] ) : String =
  bestAttemptCleanups.foldLeft(""): (accum, next) =>
    accum + (LineSep*2) + defaultVerticalMessage(next)

def defaultVerticalMessage( run : Step.Run ) : String = defaultVerticalMessage(None, run)

def defaultVerticalMessage( tup : Tuple2[Int,Step.Run]) : String = defaultVerticalMessage(Some(tup(0)),tup(1))

def defaultVerticalMessage( index : Option[Int], run : Step.Run ) : String =
  def action( step : Step ) : String =
    step match
      case exec : Step.Exec => s"Parsed command: ${exec.parsedCommand}"
      case internal : Step.Internal => "Action: <internal function>"
  val body = run match
    case completed : Step.Run.Completed => defaultVerticalBody(completed)
    case skipped   : Step.Run.Skipped   => defaultVerticalBody(skipped)
  val header =
    s"""|----------------------------------
        | ${index.fold(run.step.name)(i => i.toString + ". " + run.step.name)}
        |----------------------------------
        | ${action(run.step)}
        | Succeeded? ${run.success}""".stripMargin.trim
  header + LineSep + body

def defaultVerticalBody(completed : Step.Run.Completed) : String =
  def afterExitCode( step : Step ) : String =
    step match
      case exec : Step.Exec => ""
      case internal : Step.Internal => "(notional)"
  val stdOutContent =
    if completed.result.stepOut.nonEmpty then completed.result.stepOut else "<EMPTY>"
  val stdErrContent =
    if completed.result.stepErr.nonEmpty then completed.result.stepErr else "<EMPTY>"
  s"""| Exit Code: ${completed.result.exitCode} ${afterExitCode(completed.step)}
      |
      | stdout:
      |${increaseIndent(5)(stdOutContent)}
      |
      | stderr:
      |${increaseIndent(5)(stdErrContent)}""".stripMargin.trim

// Leave this stuff out
// We end up mailing sensitive stuff from the environment
//
//      |
//      | Working directory:
//      |${increaseIndent(5)(completed.step.workingDirectory.toString)}
//      |
//      | Environment:
//      |${increaseIndent(5)(pprint.PPrinter.BlackWhite(completed.step.environment).plainText)}"""

def defaultVerticalBody(skipped : Step.Run.Skipped) : String =
  s"""|
      | SKIPPED!""".stripMargin.trim




