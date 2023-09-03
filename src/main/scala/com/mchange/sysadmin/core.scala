package com.mchange.sysadmin

import scala.collection.*
import com.mchange.codegenutil.*
import java.time.{Instant,ZoneId}
import java.time.temporal.ChronoUnit
import java.time.format.DateTimeFormatter.ISO_OFFSET_DATE_TIME

import scala.util.control.NonFatal

class SysadminException( message : String, cause : Throwable = null ) extends Exception(message, cause)
class UnexpectedPriorState( message : String, cause : Throwable = null ) extends Exception(message, cause)

def abortUnexpectedPriorState( message : String ) : Nothing = throw new UnexpectedPriorState(message)

def extractFullStackTrace(t:Throwable) : String =
  val sw = new java.io.StringWriter()
  t.printStackTrace(new java.io.PrintWriter(sw))
  sw.toString()

extension (t : Throwable)
  def fullStackTrace : String = extractFullStackTrace(t)

lazy val hostname : Option[String] =
  try
    Some(os.proc("hostname").call( check=false, stdout = os.Pipe, stderr = os.Pipe ).out.trim())
  catch
    case NonFatal(t) =>  None

def timestamp =
  val now = Instant.now.truncatedTo( ChronoUnit.SECONDS ).atZone(ZoneId.systemDefault())
  ISO_OFFSET_DATE_TIME.format(now)




