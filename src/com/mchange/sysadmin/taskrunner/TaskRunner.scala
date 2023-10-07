// this class includes an unfortunate workaround to problems with the
// Scala compiler's inability to traverse singleton types in projection
// paths. The bifurcation of what should just be singleton object Step
// with its Step.type into a Step object that implements and explicit
// StepType trait is due to this. Hopefully this issue will eventually
// be addressed, and this code can become cleaner.
//
// See https://github.com/lampepfl/dotty/issues/18655
//
// Currently, as a consequence of this workaround, we also hit a bug
// where spurious unreachable warnings are generated by the compiler
// when the expected cases do in fact reach the "unreachable" code.
//
// See https://github.com/lampepfl/dotty/issues/18661

package com.mchange.sysadmin.taskrunner

import scala.collection.{immutable,mutable}

import scala.util.control.NonFatal

import com.mchange.sysadmin.*

object TaskRunner:
  def apply[T]( parallelize : Parallelize ) : TaskRunner[T] = new TaskRunner[T]( parallelize )
  def apply[T]                              : TaskRunner[T] = new TaskRunner[T]( Parallelize.Never )
class TaskRunner[T](parallelize : Parallelize = Parallelize.Never):
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

  sealed trait StepType:
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
    ):
      def carryForwardDescription : Option[String]= carryForwardDescriber(carryForward)
      def withNotes( genNotes : => String ) : Result =
        try
          val notes = genNotes
          this.copy( notes = Some( notes ) )
        catch
          case NonFatal(t) =>
            val msg =
              s"""|Exception while generating notes:
                  |  ${t.toString}""".stripMargin
            this.copy( notes = Some( msg ) )
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
      actionDescription : Option[String] = None,
      essential : Option[Boolean] = None
    ) extends Step:
      override def toString() = s"Step.Arbitrary(name=${name}, workingDirectory=${workingDirectory}, environment=********)"
    case class Exec (
      name : String,
      parsedCommand : List[String],
      workingDirectory : os.Path = os.pwd,
      environment : immutable.Map[String,String] = sys.env,
      carrier : Carrier = Carrier.carryPrior,
      isSuccess : Step.Run.Completed => Boolean = defaultIsSuccess,
      essential : Option[Boolean] = None
    ) extends Step:
      def actionDescription = Some(s"Parsed command: ${parsedCommand}")
      override def toString() = s"Step.Exec(name=${name}, parsedCommand=${parsedCommand}, workingDirectory=${workingDirectory}, environment=********)"
    sealed trait RunType:
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
      case class Completed( step : Step, result : Step.Result ) extends Step.Run:
        def success : Boolean = step.isSuccess(this.asInstanceOf[Step.Run.Completed]) // this cast is only necessary due to our projection type workaround
      case class Skipped( step : Step ) extends Step.Run:
        val success : Boolean = false
    object Run extends RunType  
    sealed trait Run:
      def step         : Step
      def success      : Boolean
  object Step extends StepType    
  sealed trait Step:
    def name              : String
    def environment       : Map[String,String]
    def workingDirectory  : os.Path
    def actionDescription : Option[String]
    def essential         : Option[Boolean]
    def isSuccess         : Step.Run.Completed => Boolean
  end Step

  def arbitrary(
    name : String,
    action : (T, Step.Arbitrary) => Step.Result,
    isSuccess : Step.Run.Completed => Boolean = Step.stepErrIsEmpty,
    workingDirectory : os.Path = os.pwd,
    environment : immutable.Map[String,String] = sys.env,
    actionDescription : Option[String] = None
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

  def nonsequentialsOkay( stepRuns : Set[? <: Step.Run] ) = stepRuns.forall( stepRun => !stepRun.step.essential.getOrElse(false) || stepRun.success )

  def silentRun(task : Task) : Task.Run =
    val bestEffortSetups =
      val init = task.init
      val actions = task.bestEffortSetups.map( step => () => Step.Run.Completed(init, step) )
      parallelize.execute[Step.Run.Completed]( Parallelizable.Setups, actions )

    val seqRunsReversed =
      val setupOk = nonsequentialsOkay(bestEffortSetups)
      if setupOk then
        task.sequential.foldLeft( Nil : List[Step.Run] ): ( accum, next ) =>
          accum match
            case Nil => Step.Run.Completed(task.init, next) :: accum
            case (head : Step.Run.Completed) :: tail if head.success || !head.step.essential.getOrElse(true) => Step.Run.Completed(head.result.carryForward, next) :: accum
            case other => Step.Run.Skipped(next) :: accum
      else
        task.sequential.foldLeft( Nil : List[Step.Run] )( ( accum, next ) => Step.Run.Skipped(next) :: accum )

    val bestEffortFollowups =
      val lastCompleted = seqRunsReversed.collectFirst { case completed : Step.Run.Completed => completed }
      val commonCarryforward = lastCompleted.fold(task.init)(_.result.carryForward) 
      val actions = task.bestEffortFollowups.map( step => () => Step.Run.Completed(commonCarryforward, step) )
      parallelize.execute( Parallelizable.Followups, actions )

    Task.Run(task, bestEffortSetups, seqRunsReversed.reverse, bestEffortFollowups)
  end silentRun

  def runAndReport(task : Task, reporters : Set[Reporter]) : Unit =
    val run = this.silentRun(task)
    val actions = reporters.map: report =>
      () =>
        try
          report(run)
        catch
          case NonFatal(t) => t.printStackTrace
    parallelize.execute( Parallelizable.Reporting, actions )

  sealed trait TaskType:
    object Run:
      def usualSuccessCriterion(run : Run) =
        def setupsOkay = nonsequentialsOkay(run.bestEffortSetups)
        def sequentialsOkay = run.sequential.forall( sr => (sr.success || !sr.step.essential.getOrElse(true)) )
        def followupsOkay = nonsequentialsOkay(run.bestEffortFollowups)
        setupsOkay && sequentialsOkay && followupsOkay
    case class Run(
      task : Task,
      bestEffortSetups : Set[Step.Run.Completed],
      sequential : List[Step.Run], // not necessarily completed, might be skipped
      bestEffortFollowups : Set[Step.Run.Completed],
      isSuccess : Run => Boolean = Run.usualSuccessCriterion
    ):
      def success = isSuccess( this )
  object Task extends TaskType    
  trait Task:
    def name                : String
    def init                : T
    def bestEffortSetups    : Set[Step]
    def sequential          : List[Step]
    def bestEffortFollowups : Set[Step]
  end Task
end TaskRunner
