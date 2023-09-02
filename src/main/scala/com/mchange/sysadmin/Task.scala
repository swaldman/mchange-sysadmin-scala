package com.mchange.sysadmin

import scala.collection.*

object Task:
  def usualSuccessCriterion[T] = (run : Task.Run[T]) => run.sequential.nonEmpty && run.sequential.last.success
  case class Run[T]( task : Task[T], sequential : List[Step.Run[T]], bestAttemptCleanUps : List[Step.Run[T]], isSuccess : Task.Run[T] => Boolean = usualSuccessCriterion[T] ):
    def success = isSuccess( this )
trait Task[T]:
  def name                : String
  def sequential          : List[Step[T]]
  def bestAttemptCleanups : List[Step[T]]


