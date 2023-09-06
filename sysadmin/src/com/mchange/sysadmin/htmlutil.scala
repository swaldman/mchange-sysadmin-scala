package com.mchange.sysadmin

def colorClass( boolean : Boolean ) = if boolean then "success" else "failure"

def mbLabeledText( mlt : Option[Tuple2[String,String]]) : String =
  mlt match
    case Some( tup ) => labeledText( tup ).text
    case None        => ""

def labeledTextOrNA( label : String, mbText : String ) : String =
  if mbText.nonEmpty then
    labeledText( Tuple2(label,mbText) ).text
  else
    s"""<div class="labeled-no-text"><span class="label">${label}:</span> N/A</div>"""
