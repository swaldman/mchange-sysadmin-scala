package com.mchange.sysadmin

import scala.collection.*

object Task:
  val UsualSuccessCriterion = (run : Task.Run) => run.sequential.nonEmpty && run.sequential.last.success
  case class Run( task : Task, sequential : List[Step.Run], bestAttemptCleanUps : List[Step.Run], isSuccess : Task.Run => Boolean = UsualSuccessCriterion ):
    def success = isSuccess( this )
trait Task:
  def name                : String
  def sequential          : List[Step]
  def bestAttemptCleanups : List[Step]


