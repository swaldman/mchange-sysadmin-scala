package com.mchange.sysadmin

case class StepRunMaybeIndexed( run : TaskRunner.AbstractStep.Run, mbIndex : Option[Int])

def colorClass( run : TaskRunner.AbstractStep.Run ) = run match
  case completed : TaskRunner.AbstractStep.Run.Completed =>
    if completed.success then "success" else "failure"
  case skipped : TaskRunner.AbstractStep.Run.Skipped =>
    "skipped"

def mbLabeledText( mlt : Option[Tuple2[String,String]]) : String =
  mlt match
    case Some( tup ) => labeledText( tup ).text
    case None        => ""

def labeledTextOrNA( label : String, mbText : String ) : String =
  if mbText.nonEmpty then
    labeledText( Tuple2(label,mbText) ).text
  else
    s"""<div class="labeled-no-text"><span class="label">${label}:</span> N/A</div>"""
