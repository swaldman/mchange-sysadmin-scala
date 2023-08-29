package com.mchange.sysadmin

import scala.collection.*
import scala.util.control.NonFatal

object Step:
  case class Result(exitCode: Int, stepOut : String, stepErr : String)
  def exitCodeIsZero(run : Step.Run.Completed) : Boolean = run.result.exitCode == 0
  case class Internal (
    name : String,
    action : () => Result,
    workingDirectory : os.Path = os.pwd,
    environment : immutable.Map[String,String] = sys.env,
    isSuccess : Step.Run.Completed => Boolean = exitCodeIsZero
  ) extends Step
  case class Exec (
    name : String,
    parsedCommand : List[String],
    workingDirectory : os.Path = os.pwd,
    environment : immutable.Map[String,String] = sys.env,
    isSuccess : Step.Run.Completed => Boolean = exitCodeIsZero
  ) extends Step
  object Run:
    object Completed:
      def apply( step : Step ) : Completed =
        val result =
          try
            step match
              case exec : Step.Exec =>
                val tmp = os.proc(exec.parsedCommand).call( cwd = exec.workingDirectory, env = exec.environment, check = false, stdin = os.Pipe, stderr = os.Pipe )
                Step.Result( tmp.exitCode, tmp.out.trim(), tmp.err.trim() )
              case internal : Step.Internal =>
                internal.action()
          catch
            case NonFatal(t) => Step.Result(-1,"",t.fullStackTrace)
        Completed.apply( step, result )
    case class Completed ( step : Step, result : Step.Result ) extends Run:
      def success : Boolean = step.isSuccess(this)
    case class Skipped( step : Step ) extends Run:
      val success : Boolean = false
  sealed trait Run:
    def step : Step
    def success : Boolean
sealed trait Step:
  def name : String
  def environment : Map[String,String]
  def workingDirectory : os.Path
  def isSuccess : Step.Run.Completed => Boolean
