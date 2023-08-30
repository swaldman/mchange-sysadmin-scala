package com.mchange.sysadmin

import scala.collection.*
import com.mchange.codegenutil.*
import java.time.{Instant,ZoneId}
import java.time.temporal.ChronoUnit
import java.time.format.DateTimeFormatter.ISO_OFFSET_DATE_TIME

import scala.util.control.NonFatal

class SysadminException( message : String, cause : Throwable = null ) extends Exception(message, cause)

def extractFullStackTrace(t:Throwable) : String =
  val sw = new java.io.StringWriter()
  t.printStackTrace(new java.io.PrintWriter(sw))
  sw.toString()

extension (t : Throwable)
  def fullStackTrace : String = extractFullStackTrace(t)

lazy val hostname : Option[String] =
  try
    Some(os.proc("hostname").call( check=false, stdout = os.Pipe, stderr = os.Pipe ).out.trim())
  catch
    case NonFatal(t) =>  None

def timestamp =
  val now = Instant.now.truncatedTo( ChronoUnit.SECONDS ).atZone(ZoneId.systemDefault())
  ISO_OFFSET_DATE_TIME.format(now)

def defaultTitle( run : Task.Run ) =
  hostname.fold("TASK")(hn => "[" + hn + "]") + ": " + run.task.name + " -- " + (if run.success then "SUCCEEDED" else "FAILED")

def defaultVerticalMessage( run : Task.Run ) =
  val mainSection =
    s"""|=====================================================================
        | ${defaultTitle(run)}
        |=====================================================================
        | Timestamp: ${timestamp}
        | Succeeded overall? ${if run.success then "Yes" else "No"}
        |
        | SEQUENTIAL:
        |${defaultVerticalMessageSequential(run.sequential)}""".stripMargin.trim + LineSep + LineSep

  def cleanupsSectionIfNecessary =
    s"""|-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-
        |
        | BEST-ATTEMPT CLEANUPS:
        |${defaultVerticalMessageBestAttemptCleanups(run.bestAttemptCleanUps)}""".stripMargin.trim

  val midsection = if run.bestAttemptCleanUps.isEmpty then "" else (cleanupsSectionIfNecessary + LineSep + LineSep)

  val footer =
    s"""|
        |=====================================================================
        |.   .   .   .   .   .   .   .   .   .   .   .   .   .   .   .   .   .""".stripMargin.trim + LineSep

  mainSection + midsection + footer


def defaultVerticalMessageSequential( sequential : List[Step.Run] ) : String =
  val tups = immutable.LazyList.from(1).zip(sequential)
  val untrimmed = tups.foldLeft(""): (accum, next) =>
    accum + (LineSep*2) + defaultVerticalMessage(next)
  untrimmed.trim

def defaultVerticalMessageBestAttemptCleanups( bestAttemptCleanups : List[Step.Run] ) : String =
  val untrimmed = bestAttemptCleanups.foldLeft(""): (accum, next) =>
    accum + (LineSep*2) + defaultVerticalMessage(next)
  untrimmed.trim

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
    s"""|---------------------------------------------------------------------
        | ${index.fold(run.step.name)(i => i.toString + ". " + run.step.name)}
        |---------------------------------------------------------------------
        | ${action(run.step)}
        | Succeeded? ${if run.success then "Yes" else "No"}""".stripMargin.trim
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
      |${increaseIndent(5)(stdErrContent)}""".stripMargin // don't trim, we want the initial space

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
      | SKIPPED!""".stripMargin // don't trip, we want the linefeed and initial space




