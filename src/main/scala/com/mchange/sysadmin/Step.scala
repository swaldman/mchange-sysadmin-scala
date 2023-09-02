package com.mchange.sysadmin

import scala.collection.*
import scala.util.control.NonFatal

object Step:
  object Carrier:
    def noCarryForward[T] : Carrier[T] = (_,_,_,_) => (None : Option[T])
  type Carrier[T] = (Option[Step.Run.Completed[T]], Int, String, String) => Option[T]
  def arbitraryExec[T]( prior : Option[Step.Run.Completed[T]], thisStep : Arbitrary[T], command : os.Shellable, carryForward : Carrier[T] ) : Step.Result[T] =
    val tmp = os.proc(command).call( cwd = thisStep.workingDirectory, env = thisStep.environment, check = false, stdin = os.Pipe, stdout = os.Pipe, stderr = os.Pipe )
    val exitCode = tmp.exitCode
    val stepOut = tmp.out.trim()
    val stepErr = tmp.err.trim()
    Step.Result( Some(exitCode), stepOut, stepErr, carryForward( prior, tmp.exitCode, tmp.out.trim(), tmp.err.trim() ) )
  def arbitraryExec[T]( prior : Option[Step.Run.Completed[T]], thisStep : Arbitrary[T], command : os.Shellable ) : Step.Result[T] = arbitraryExec( prior, thisStep, command, Carrier.noCarryForward )
  object Result:
    def empty[T] : Result[T] = Result(None,"","",None)
  case class Result[T](exitCode: Option[Int], stepOut : String, stepErr : String, carryForward : Option[T])
  def exitCodeIsZero[T](run : Step.Run.Completed[T]) : Boolean = run.result.exitCode.fold(false)( _ == 0 )
  def carryForwardNonEmpty[T](run : Step.Run.Completed[T]) : Boolean = run.result.carryForward.nonEmpty
  def stepErrIsEmpty[T](run : Step.Run.Completed[T]) : Boolean = run.result.stepErr.isEmpty
  def exitCodeIsZeroAndCarryForwardNonEmpty[T](run : Step.Run.Completed[T]) = exitCodeIsZero(run) && carryForwardNonEmpty(run)
  case class Arbitrary[T] (
    name : String,
    action : (Option[Step.Run.Completed[T]], Arbitrary[T]) => Result[T],
    isSuccess : Step.Run.Completed[T] => Boolean,
    workingDirectory : os.Path = os.pwd,
    environment : immutable.Map[String,String] = sys.env,
  ) extends Step[T]:
    override def toString() = s"Step.Arbitrary(name=${name}, workingDirectory=${workingDirectory}, environment=********)"
  case class Exec[T] (
    name : String,
    parsedCommand : List[String],
    carrier : Carrier[T],
    isSuccess : Step.Run.Completed[T] => Boolean = exitCodeIsZero,
    workingDirectory : os.Path = os.pwd,
    environment : immutable.Map[String,String] = sys.env,
  ) extends Step[T]:
    override def toString() = s"Step.Exec(name=${name}, parsedCommand=${parsedCommand}, workingDirectory=${workingDirectory}, environment=********)"
  object Simple:
    def apply(
      name : String,
      parsedCommand : List[String],
      isSuccess : Step.Run.Completed[Unit] => Boolean = exitCodeIsZero,
      workingDirectory : os.Path = os.pwd,
      environment : immutable.Map[String,String] = sys.env,
    ) : Simple = Exec(name, parsedCommand, Carrier.noCarryForward[Unit], isSuccess, workingDirectory, environment)
  type Simple = Exec[Unit]
  object Run:
    object Completed:
      def apply[T]( prior : Option[Completed[T]], step : Step[T] ) : Completed[T] =
        val result =
          try
            step match
              case exec : Step.Exec[T] =>
                val tmp = os.proc(exec.parsedCommand).call( cwd = exec.workingDirectory, env = exec.environment, check = false, stdin = os.Pipe, stdout = os.Pipe, stderr = os.Pipe )
                Step.Result( Some(tmp.exitCode), tmp.out.trim(), tmp.err.trim(), None )
              case arbitrary : Step.Arbitrary[T] =>
                arbitrary.action( prior, arbitrary )
          catch
            case NonFatal(t) => Step.Result(None,"",t.fullStackTrace,None)
        Completed.apply( step, result )
    case class Completed[T]( step : Step[T], result : Step.Result[T] ) extends Run[T]:
      def success : Boolean = step.isSuccess(this)
    case class Skipped[T]( step : Step[T] ) extends Run[T]:
      val success : Boolean = false
  sealed trait Run[T]:
    def step         : Step[T]
    def success      : Boolean
sealed trait Step[T]:
  def name : String
  def isSuccess : Step.Run.Completed[T] => Boolean
  def environment : Map[String,String]
  def workingDirectory : os.Path
