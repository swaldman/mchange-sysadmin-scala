package com.mchange.sysadmin

import org.jsoup.Jsoup // to verify and pretty-print HTML mail
import org.jsoup.parser.*

import scala.jdk.CollectionConverters.*

case class StepRunMaybeIndexed( run : TaskRunner.AbstractStep.Run, mbIndex : Option[Int])

def colorClass( run : TaskRunner.AbstractStep.Run ) = run match
  case completed : TaskRunner.AbstractStep.Run.Completed =>
    if completed.success then "success" else "failure"
  case skipped : TaskRunner.AbstractStep.Run.Skipped =>
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

def prettyPrintHtml( rawHtmlText : String ) : String = Jsoup.parse( rawHtmlText ).html()

def debugPrettyPrintHtml( rawHtmlText : String ) =
  val parser = new Parser(new HtmlTreeBuilder())
  parser.setTrackErrors(100)
  parser.setTrackPosition(true)
  val doc = parser.parseInput(rawHtmlText,"./")
  parser.getErrors().asScala.foreach( err => System.err.println("[debug] Output HTML Parse Error: " + err) )
  doc.html()
