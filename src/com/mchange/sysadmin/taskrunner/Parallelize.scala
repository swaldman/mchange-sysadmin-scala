package com.mchange.sysadmin.taskrunner

import scala.concurrent.{Await,ExecutionContext,Future}
import scala.concurrent.duration.Duration

enum Parallelizable:
  case Setups, Followups, Reporting

object Parallelize:
  private def sequentialExecute[T](actions : Set[() => T]) : Set[T] = actions.map( fcn => fcn() )
  
  case class Sometimes private[Parallelize] ( parallelizables : Set[Parallelizable], ec : ExecutionContext ) extends Parallelize:
    private [taskrunner]
    def execute[T]( what : Parallelizable, actions : Set[() => T] ) : Set[T] =
      if parallelizables( what ) then
        actions
          .map( fcn => Future( fcn() )( ec ) )
          .map( fut => Await.result( fut, Duration.Inf ) ) // Exceptions should have been caught internally. We'll throw if not!
      else
        sequentialExecute( actions )
  
  
  case object Never extends Parallelize:
    private [taskrunner]
    def execute[T]( what : Parallelizable, actions : Set[() => T] ) : Set[T] = sequentialExecute(actions)

  def apply( first : Parallelizable, others : Parallelizable* )( using ec : ExecutionContext ) : Parallelize = new Sometimes( Set(others*) + first, ec )
  def apply() : Parallelize = Never
sealed trait Parallelize:
  private [taskrunner]
  def execute[T]( what : Parallelizable, actions : Set[() => T] ) : Set[T] // actions should all handle any NonFatal Exceptions internally

  
