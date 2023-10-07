package com.mchange.sysadmin.taskrunner

import java.util.Date

import scala.collection.*

import jakarta.mail.*
import jakarta.mail.internet.*

import com.mchange.codegenutil.*

import com.mchange.sysadmin.Smtp

object Reporting:
  def defaultCompose( from : String, to : String, run : AnyTaskRun, context : Smtp.Context ) : MimeMessage =
    val msg = new MimeMessage(context.session)
    //msg.setText(defaultVerticalMessage(run))
    val htmlAlternative =
      val tmp = new MimeBodyPart()
      def htmlText =
        //debugPrettyPrintHtml(task_result_html(run).text)
        prettyPrintHtml(task_result_html(run).text)
      tmp.setContent(htmlText, "text/html")
      tmp
    val plainTextAlternative =
      val tmp = new MimeBodyPart()
      tmp.setContent(defaultVerticalMessage(run), "text/plain")
      tmp
    // last entry is highest priority!
    val multipart = new MimeMultipart("alternative", plainTextAlternative, htmlAlternative)
    msg.setContent(multipart)
    msg.setSubject(defaultTitle(run))
    msg.setFrom(new InternetAddress(from))
    msg.addRecipient(Message.RecipientType.TO, new InternetAddress(to))
    msg.setSentDate(new Date())
    msg.saveChanges()
    msg

  def defaultTitle( run : AnyTaskRun ) =
    hostname.fold("TASK")(hn => "[" + hn + "]") + " " + (if run.success then "SUCCEEDED" else "FAILED") + ": " + run.task.name

  def defaultVerticalMessage( run : AnyTaskRun ) =
    val topSection =
      s"""|=====================================================================
          | ${defaultTitle(run)}
          |=====================================================================
          | Timestamp: ${timestamp}
          | Succeeded overall? ${yn( run.success )}""".stripMargin.trim + LineSep + LineSep

    def setupsSectionIfNecessary =
      s"""| BEST-EFFORT SETUPS:
          |${defaultVerticalMessageBestAttempts(run.bestEffortSetups)}
          |
          |-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-""".stripMargin.trim

    val setups = if run.bestEffortSetups.isEmpty then (setupsSectionIfNecessary + LineSep + LineSep) else ""

    val seqsection =
      s"""| SEQUENTIAL:
          |${defaultVerticalMessageSequential(run.sequential)}""".stripMargin.trim + LineSep + LineSep

    def followupsSectionIfNecessary =
      s"""|-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-
          |
          | BEST-EFFORT FOLLOWUPS:
          |${defaultVerticalMessageBestAttempts(run.bestEffortFollowups)}""".stripMargin.trim

    val followups = if run.bestEffortFollowups.isEmpty then "" else (followupsSectionIfNecessary + LineSep + LineSep)

    val footer =
      s"""|
          |=====================================================================
          |.   .   .   .   .   .   .   .   .   .   .   .   .   .   .   .   .   .""".stripMargin.trim + LineSep

    topSection + setups + seqsection + followups + footer

  def defaultVerticalMessageSequential( sequential : Iterable[AnyStepRun] ) : String =
    val tups = immutable.LazyList.from(1).zip(sequential)
    val untrimmed = tups.foldLeft(""): (accum, next) =>
      accum + (LineSep*2) + defaultVerticalMessage(next)
    untrimmed.trim

  def defaultVerticalMessageBestAttempts( bestAttempts : Iterable[? <: AnyStepRun] ) : String =
    val untrimmed = bestAttempts.foldLeft(""): (accum, next) =>
      accum + (LineSep*2) + defaultVerticalMessage(next)
    untrimmed.trim

  def defaultVerticalMessage( run : AnyStepRun ) : String = defaultVerticalMessage(None, run)

  def defaultVerticalMessage( tup : Tuple2[Int,AnyStepRun]) : String = defaultVerticalMessage(Some(tup(0)),tup(1))

  def defaultVerticalMessage( index : Option[Int], run : AnyStepRun ) : String =

    val sequential = index.nonEmpty
    val essentialAnnotation = run.step.essential.fold("")(ess => if ess then "(essential)" else "(nonessential)")

//      def action( step : Step ) : String =
//        step match
//          case exec : Step.Exec => s"Parsed command: ${exec.parsedCommand}"
//          case arbitrary : Step.Arbitrary => "Action: <internal function>"
    val body = run match
      case completed : AnyStepRunCompleted => defaultVerticalBody(completed)
      case skipped   : AnyStepRunSkipped   => defaultVerticalBody(skipped)
    val header =
      s"""|---------------------------------------------------------------------
          | ${index.fold(run.step.name)(i => i.toString + ". " + run.step.name)}
          |---------------------------------------------------------------------
          |""".stripMargin
    val mbActionDescription = run.step.actionDescription.fold(""): ad =>
      s"""| ${ad}
          |""".stripMargin
    val succeededSection =
      s"""| Succeeded? ${yn( run.success )} ${essentialAnnotation}
          |""".stripMargin
    header + mbActionDescription + succeededSection + body

  def defaultVerticalBody(completed : AnyStepRunCompleted) : String =
    val stdOutContent =
      if completed.result.stepOut.nonEmpty then completed.result.stepOut else "<empty>"
    val stdErrContent =
      if completed.result.stepErr.nonEmpty then completed.result.stepErr else "<empty>"
    val mbExitCode = completed.result.exitCode.fold(""): code =>
      s"""| Exit code: ${code}
          |""".stripMargin // don't trim, we want the initial space
    val stdOutStdErr =
      s"""|
          | out:
          |${increaseIndent(5)(stdOutContent)}
          |
          | err:
          |${increaseIndent(5)(stdErrContent)}
          |""".stripMargin // don't trim, we want the initial space
    val mbNotes = completed.result.notes.fold(""): notes =>
      s"""|
          | notes:
          |${increaseIndent(5)(notes)}
          |""".stripMargin
    val mbCarryForward = completed.result.carryForwardDescription.fold(""): cfd =>
      s"""|
          | carryforward:
          |${increaseIndent(5)(cfd)}""".stripMargin
    mbExitCode + stdOutStdErr + mbNotes + mbCarryForward

  // Leave this stuff out
  // We end up mailing sensitive stuff from the environment
  //
  //      |
  //      | Working directory:
  //      |${increaseIndent(5)(completed.step.workingDirectory.toString)}
  //      |
  //      | Environment:
  //      |${increaseIndent(5)(pprint.PPrinter.BlackWhite(completed.step.environment).plainText)}"""

  def defaultVerticalBody(skipped : AnyStepRunSkipped) : String =
    s"""|
        | SKIPPED!""".stripMargin // don't trip, we want the linefeed and initial space

end Reporting
