package com.mchange.sysadmin.taskrunner

case class StepRunMaybeIndexed( run : AnyStepRun, mbIndex : Option[Int])

def colorClass( run : AnyStepRun ) = run match
  case completed : AnyStepRunCompleted =>
    if completed.success then "success" else "failure"
  case skipped : AnyStepRunSkipped =>
    "skipped"

def mbLabeledText( mlt : Option[Tuple2[HtmlSafeText,HtmlSafeText]]) : String =
  mlt match
    case Some( tup ) => labeledText( tup ).text
    case None        => ""

def labeledTextOrNA( label : HtmlSafeText, mbText : HtmlSafeText ) : String =
  if mbText.nonEmpty then
    labeledText( Tuple2(label,mbText) ).text
  else
    s"""<div class="labeled-no-text"><span class="label">${label}:</span> N/A</div>"""

