package com.mchange.sysadmin

import scala.collection.*
import scala.util.control.NonFatal

object Step:
  def arbitraryExec( prior : Option[Step.Run.Completed], thisStep : Arbitrary, command : os.Shellable, carryForward : (Option[Step.Run.Completed], Int, String, String) => Option[Any] ) : Step.Result =
    val tmp = os.proc(command).call( cwd = thisStep.workingDirectory, env = thisStep.environment, check = false, stdin = os.Pipe, stdout = os.Pipe, stderr = os.Pipe )
    val exitCode = tmp.exitCode
    val stepOut = tmp.out.trim()
    val stepErr = tmp.err.trim()
    Step.Result( Some(exitCode), stepOut, stepErr, carryForward( prior, tmp.exitCode, tmp.out.trim(), tmp.err.trim() ) )
  def arbitraryExec( prior : Option[Step.Run.Completed], thisStep : Arbitrary, command : os.Shellable ) : Step.Result = arbitraryExec( prior, thisStep, command, (_,_,_,_) => None )
  object Result:
    val empty = Result(None,"","",None)
  case class Result(exitCode: Option[Int], stepOut : String, stepErr : String, carryForward : Option[Any])
  def exitCodeIsZero(run : Step.Run.Completed) : Boolean = run.result.exitCode.fold(false)( _ == 0 )
  def carryForwardNonEmpty(run : Step.Run.Completed) : Boolean = run.result.carryForward.nonEmpty
  def stepErrIsEmpty(run : Step.Run.Completed) : Boolean = run.result.stepErr.isEmpty
  def exitCodeIsZeroAndCarryForwardNonEmpty(run : Step.Run.Completed) = exitCodeIsZero(run) && carryForwardNonEmpty(run)
  case class Arbitrary (
    name : String,
    action : (Option[Step.Run.Completed], Arbitrary) => Result,
    isSuccess : Step.Run.Completed => Boolean,
    workingDirectory : os.Path = os.pwd,
    environment : immutable.Map[String,String] = sys.env,
  ) extends Step:
    override def toString() = s"Step.Arbitrary(name=${name}, workingDirectory=${workingDirectory}, environment=********)"
  case class Exec (
    name : String,
    parsedCommand : List[String],
    isSuccess : Step.Run.Completed => Boolean = exitCodeIsZero,
    workingDirectory : os.Path = os.pwd,
    environment : immutable.Map[String,String] = sys.env,
  ) extends Step:
    override def toString() = s"Step.Exec(name=${name}, parsedCommand=${parsedCommand}, workingDirectory=${workingDirectory}, environment=********)"
  object Run:
    object Completed:
      def apply( prior : Option[Completed], step : Step ) : Completed =
        val result =
          try
            step match
              case exec : Step.Exec =>
                val tmp = os.proc(exec.parsedCommand).call( cwd = exec.workingDirectory, env = exec.environment, check = false, stdin = os.Pipe, stdout = os.Pipe, stderr = os.Pipe )
                Step.Result( Some(tmp.exitCode), tmp.out.trim(), tmp.err.trim(), None )
              case arbitrary : Step.Arbitrary =>
                arbitrary.action( prior, arbitrary )
          catch
            case NonFatal(t) => Step.Result(None,"",t.fullStackTrace,None)
        Completed.apply( step, result )
    case class Completed( step : Step, result : Step.Result ) extends Run:
      def success : Boolean = step.isSuccess(this)
    case class Skipped( step : Step ) extends Run:
      val success : Boolean = false
  sealed trait Run:
    def step         : Step
    def success      : Boolean
sealed trait Step:
  def name : String
  def isSuccess : Step.Run.Completed => Boolean
  def environment : Map[String,String]
  def workingDirectory : os.Path
