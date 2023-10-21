package com.mchange.sysadmin

import scala.jdk.CollectionConverters.*

import org.jsoup.Jsoup // to verify and pretty-print HTML mail
import org.jsoup.parser.*

def prettyPrintHtml( rawHtmlText : String ) : String = Jsoup.parse( rawHtmlText ).html()

def debugPrettyPrintHtml( rawHtmlText : String ) =
  val parser = new Parser(new HtmlTreeBuilder())
  parser.setTrackErrors(100)
  parser.setTrackPosition(true)
  val doc = parser.parseInput(rawHtmlText,"./")
  parser.getErrors().asScala.foreach( err => System.err.println("[debug] Output HTML Parse Error: " + err) )
  doc.html()
