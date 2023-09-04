package com.mchange.sysadmin

import scala.collection.*
import scala.util.control.NonFatal
import com.mchange.codegenutil.*
import java.util.Date
import javax.mail.Message
import javax.mail.internet.*

object TaskRunner:

  object AbstractTask:
    trait Run:
      def task : AbstractTask
      def sequential : List[AbstractStep.Run]
      def bestAttemptCleanUps : List[AbstractStep.Run]
      def success : Boolean

  trait AbstractTask:
    def name                : String
    def sequential          : List[AbstractStep]
    def bestAttemptCleanups : List[AbstractStep]

  object AbstractStep:
    trait Result:
      def exitCode: Option[Int]
      def stepOut : String
      def stepErr : String
      def carryForwardDescription : Option[String]
      def notes   : Option[String]

    object Run:
      trait Completed extends AbstractStep.Run:
        def result : AbstractStep.Result
      trait Skipped extends AbstractStep.Run
    trait Run:
      def step         : AbstractStep
      def success      : Boolean
  trait AbstractStep:
    def name              : String
    def environment       : Map[String,String]
    def workingDirectory  : os.Path
    def actionDescription : String

  object Reporters:
    def stdOutOnly(formatter : AbstractTask.Run => String = Reporting.defaultVerticalMessage) : List[AbstractTask.Run => Unit] = List(
      ( run : AbstractTask.Run ) => Console.out.println(formatter(run))
    )
    def stdErrOnly(formatter : AbstractTask.Run => String = Reporting.defaultVerticalMessage) : List[AbstractTask.Run => Unit] = List(
      ( run : AbstractTask.Run ) => Console.err.println(formatter(run))
    )
    def smtpOnly(
      from : String,
      to : String,
      subject : AbstractTask.Run => String = Reporting.defaultTitle,
      text : AbstractTask.Run => String = Reporting.defaultVerticalMessage
    )( using context : Smtp.Context ) : List[AbstractTask.Run => Unit] = List(
      ( run : AbstractTask.Run ) =>
        val msg = new MimeMessage(context.session)
        msg.setText(text(run))
        msg.setSubject(subject(run))
        msg.setFrom(new InternetAddress(from))
        msg.addRecipient(Message.RecipientType.TO, new InternetAddress(to))
        msg.setSentDate(new Date())
        msg.saveChanges()
        context.sendMessage(msg)
    )
    def smtpAndStdOut(
      from : String,
      to : String,
      subject : AbstractTask.Run => String = Reporting.defaultTitle,
      text : AbstractTask.Run => String = Reporting.defaultVerticalMessage
    )( using context : Smtp.Context ) : List[AbstractTask.Run => Unit] =
      smtpOnly(from,to,subject,text) ++ stdOutOnly(text)

    def default( from : String, to : String )( using context : Smtp.Context ) = smtpAndStdOut(from,to)

  end Reporters

  object Reporting:
    def defaultTitle( run : AbstractTask.Run ) =
      hostname.fold("TASK")(hn => "[" + hn + "]") + ": " + run.task.name + " -- " + (if run.success then "SUCCEEDED" else "FAILED")

    def defaultVerticalMessage( run : AbstractTask.Run ) =
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

    def defaultVerticalMessageSequential( sequential : List[AbstractStep.Run] ) : String =
      val tups = immutable.LazyList.from(1).zip(sequential)
      val untrimmed = tups.foldLeft(""): (accum, next) =>
        accum + (LineSep*2) + defaultVerticalMessage(next)
      untrimmed.trim

    def defaultVerticalMessageBestAttemptCleanups( bestAttemptCleanups : List[AbstractStep.Run] ) : String =
      val untrimmed = bestAttemptCleanups.foldLeft(""): (accum, next) =>
        accum + (LineSep*2) + defaultVerticalMessage(next)
      untrimmed.trim

    def defaultVerticalMessage( run : AbstractStep.Run ) : String = defaultVerticalMessage(None, run)

    def defaultVerticalMessage( tup : Tuple2[Int,AbstractStep.Run]) : String = defaultVerticalMessage(Some(tup(0)),tup(1))

    def defaultVerticalMessage( index : Option[Int], run : AbstractStep.Run ) : String =
//      def action( step : Step ) : String =
//        step match
//          case exec : Step.Exec => s"Parsed command: ${exec.parsedCommand}"
//          case arbitrary : Step.Arbitrary => "Action: <internal function>"
      val body = run match
        case completed : AbstractStep.Run.Completed => defaultVerticalBody(completed)
        case skipped   : AbstractStep.Run.Skipped   => defaultVerticalBody(skipped)
      val header =
        s"""|---------------------------------------------------------------------
            | ${index.fold(run.step.name)(i => i.toString + ". " + run.step.name)}
            |---------------------------------------------------------------------
            | ${run.step.actionDescription}
            | Succeeded? ${if run.success then "Yes" else "No"}""".stripMargin.trim
      header + LineSep + body

    def defaultVerticalBody(completed : AbstractStep.Run.Completed) : String =
      val stdOutContent =
        if completed.result.stepOut.nonEmpty then completed.result.stepOut else "<EMPTY>"
      val stdErrContent =
        if completed.result.stepErr.nonEmpty then completed.result.stepErr else "<EMPTY>"
      val mbExitCode = completed.result.exitCode.fold(""): code =>
        s"""| Exit code: ${code}
            |
            |""".stripMargin // don't trim, we want the initial space
      val stdOutStdErr =
        s"""| out:
            |${increaseIndent(5)(stdOutContent)}
            |
            | err:
            |${increaseIndent(5)(stdErrContent)}
            |""".stripMargin // don't trim, we want the initial space
      val mbNotes = completed.result.notes.fold(""): notes =>
        s"""|
            | notes:
            |${increaseIndent(5)(notes)}""".stripMargin
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

    def defaultVerticalBody(skipped : AbstractStep.Run.Skipped) : String =
      s"""|
          | SKIPPED!""".stripMargin // don't trip, we want the linefeed and initial space

  end Reporting
end TaskRunner

class TaskRunner[T]:
  import TaskRunner.*

  object Carrier:
    val carryPrior : Carrier = (prior,_,_,_) => prior
  type Carrier = (T, Int, String, String) => T

  def arbitraryExec( prior : T, thisStep : Step.Arbitrary, command : os.Shellable, carryForward : Carrier ) : Step.Result =
    val tmp = os.proc(command).call( cwd = thisStep.workingDirectory, env = thisStep.environment, check = false, stdin = os.Pipe, stdout = os.Pipe, stderr = os.Pipe )
    val exitCode = tmp.exitCode
    val stepOut = tmp.out.trim()
    val stepErr = tmp.err.trim()
    Step.Result( Some(exitCode), stepOut, stepErr, carryForward( prior, tmp.exitCode, tmp.out.trim(), tmp.err.trim() ) )

  def arbitraryExec( prior : T, thisStep : Step.Arbitrary, command : os.Shellable )(using ev : T =:= Unit) : Step.Result = arbitraryExec( prior, thisStep, command, (_,_,_,_) => ().asInstanceOf[T] )

  object Step:
    object Result:
      val defaultCarryForwardDescriber : T => Option[String] =
        case _ : Unit => None
        case other    => Some( pprint(other).plainText )
      def emptyWithCarryForward( t : T ) : Result = Result(None,"","",t)
      def zeroWithCarryForward( t : T ) : Result = Result(Some(0),"","",t)
    case class Result(
      exitCode: Option[Int],
      stepOut : String,
      stepErr : String,
      carryForward : T,
      notes   : Option[String] = None,
      carryForwardDescriber : T => Option[String] = defaultCarryForwardDescriber
    ) extends AbstractStep.Result:
      def carryForwardDescription : Option[String]= carryForwardDescriber(carryForward)
    def exitCodeIsZero(run : Step.Run.Completed) : Boolean = run.result.exitCode.fold(false)( _ == 0 )
    def stepErrIsEmpty(run : Step.Run.Completed) : Boolean = run.result.stepErr.isEmpty
    def defaultIsSuccess(run : Step.Run.Completed) : Boolean = run.result.exitCode match
      case Some( exitCode ) => exitCode == 0
      case None             => run.result.stepErr.isEmpty
    case class Arbitrary (
      name : String,
      action : (T, Step.Arbitrary) => Result,
      isSuccess : Step.Run.Completed => Boolean = defaultIsSuccess,
      workingDirectory : os.Path = os.pwd,
      environment : immutable.Map[String,String] = sys.env,
      actionDescription : String = "Action: <internal function>"
    ) extends Step:
      override def toString() = s"Step.Arbitrary(name=${name}, workingDirectory=${workingDirectory}, environment=********)"
    case class Exec (
      name : String,
      parsedCommand : List[String],
      workingDirectory : os.Path = os.pwd,
      environment : immutable.Map[String,String] = sys.env,
      carrier : Carrier = Carrier.carryPrior,
      isSuccess : Step.Run.Completed => Boolean = defaultIsSuccess,
    ) extends Step:
      def actionDescription = s"Parsed command: ${parsedCommand}"
      override def toString() = s"Step.Exec(name=${name}, parsedCommand=${parsedCommand}, workingDirectory=${workingDirectory}, environment=********)"
    object Run:
      object Completed:
        def apply( prior : T, step : Step ) : Step.Run.Completed =
          val result =
            try
              step match
                case exec : Step.Exec =>
                  val tmp = os.proc(exec.parsedCommand).call( cwd = exec.workingDirectory, env = exec.environment, check = false, stdin = os.Pipe, stdout = os.Pipe, stderr = os.Pipe )
                  Step.Result( Some(tmp.exitCode), tmp.out.trim(), tmp.err.trim(), prior )
                case arbitrary : Step.Arbitrary =>
                  arbitrary.action( prior, arbitrary )
            catch
              case NonFatal(t) => Step.Result(None,"",t.fullStackTrace, prior)
          Step.Run.Completed.apply( step, result )
      case class Completed( step : Step, result : Step.Result ) extends Step.Run, AbstractStep.Run.Completed:
        def success : Boolean = step.isSuccess(this)
      case class Skipped( step : Step ) extends Step.Run, AbstractStep.Run.Skipped:
        val success : Boolean = false
    sealed trait Run extends AbstractStep.Run:
      def step         : Step
      def success      : Boolean
  sealed trait Step extends TaskRunner.AbstractStep:
    def name              : String
    def environment       : Map[String,String]
    def workingDirectory  : os.Path
    def actionDescription : String
    def isSuccess : Step.Run.Completed => Boolean
  end Step

  def arbitrary(
    name : String,
    action : (T, Step.Arbitrary) => Step.Result,
    isSuccess : Step.Run.Completed => Boolean = Step.stepErrIsEmpty,
    workingDirectory : os.Path = os.pwd,
    environment : immutable.Map[String,String] = sys.env,
    actionDescription : String = "Action: <internal function>"
  ) : Step.Arbitrary =
    Step.Arbitrary(name,action,isSuccess,workingDirectory,environment,actionDescription)

  def exec(
    name : String,
    parsedCommand : List[String],
    workingDirectory : os.Path = os.pwd,
    environment : immutable.Map[String,String] = sys.env,
    carrier : Carrier = Carrier.carryPrior,
    isSuccess : Step.Run.Completed => Boolean = Step.exitCodeIsZero,
  ) : Step.Exec =
    Step.Exec(name, parsedCommand, workingDirectory, environment, carrier, isSuccess)

  def result(
    exitCode: Option[Int],
    stepOut : String,
    stepErr : String,
    carryForward : T,
    notes   : Option[String] = None,
    carryForwardDescriber : T => Option[String] = Step.Result.defaultCarryForwardDescriber
  ) = Step.Result(exitCode, stepOut, stepErr, carryForward,notes, carryForwardDescriber)

  type Arbitrary = this.Step.Arbitrary
  type Exec      = this.Step.Exec

  type Completed = this.Step.Run.Completed

  val Result = this.Step.Result
  type Result = this.Step.Result

  val carryPrior = Carrier.carryPrior

  def silentRun(task : Task) : Task.Run =
    val seqRunsReversed = task.sequential.foldLeft( Nil : List[Step.Run] ): ( accum, next ) =>
      accum match
        case Nil => Step.Run.Completed(task.init, next) :: accum
        case (head : Step.Run.Completed) :: tail if head.success => Step.Run.Completed(head.result.carryForward, next) :: accum
        case other => Step.Run.Skipped(next) :: accum

    val bestEffortReversed = task.bestAttemptCleanups.foldLeft( Nil : List[Step.Run] ): ( accum, next ) =>
      val lastCompleted = seqRunsReversed.collectFirst { case completed : Step.Run.Completed => completed }
      Step.Run.Completed(lastCompleted.fold(task.init)(_.result.carryForward),next) :: accum

    Task.Run(task, seqRunsReversed.reverse,bestEffortReversed.reverse)

  def runAndReport(task : Task, reporters : List[Task.Run => Unit]) : Unit =
    val run = this.silentRun(task)
    reporters.foreach: report =>
      try
        report(run)
      catch
        case NonFatal(t) => t.printStackTrace

  object Task:
    object Run:
      def usualSuccessCriterion(run : Run) = run.sequential.isEmpty || run.sequential.last.success
    case class Run(
      task : Task,
      sequential : List[Step.Run],
      bestAttemptCleanUps : List[Step.Run],
      isSuccess : Run => Boolean = Run.usualSuccessCriterion
    ) extends AbstractTask.Run:
      def success = isSuccess( this )
  trait Task extends TaskRunner.AbstractTask:
    def name                : String
    def init                : T
    def sequential          : List[Step]
    def bestAttemptCleanups : List[Step]
  end Task
end TaskRunner
