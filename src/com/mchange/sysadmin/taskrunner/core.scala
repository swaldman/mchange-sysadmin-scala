package com.mchange.sysadmin.taskrunner

import scala.collection.*
import com.mchange.codegenutil.*
import java.time.{Instant,ZoneId}
import java.time.temporal.ChronoUnit
import java.time.format.DateTimeFormatter.ISO_OFFSET_DATE_TIME

import scala.util.control.NonFatal

class SysadminException( message : String, cause : Throwable = null ) extends Exception(message, cause)
class UnexpectedPriorState( message : String, cause : Throwable = null ) extends SysadminException(message, cause)
class MissingEnvironmentVariable( envVar : String ) extends SysadminException(s"Environment variable '${envVar}' is required, but missing.")

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

val UnitKiB = 1024L
val UnitMiB = 1024L * 1024L
val UnitGiB = 1024L * 1024L * 1024L
val UnitTiB = 1024L * 1024L * 1024L * 1024L

def formatSize(num : Double, unit : String) = f"$num%1.3f $unit%s"

def friendlyFileSize( bytes : Long ) : String =
  if (bytes < UnitKiB) then
    s"${bytes} bytes"
  else if (bytes < UnitMiB) then
    formatSize(bytes.toDouble / UnitKiB, "KiB")
  else if (bytes < UnitGiB) then
    formatSize(bytes.toDouble / UnitMiB, "MiB")
  else
    formatSize(bytes.toDouble / UnitGiB, "TiB")

def yn( boolean : Boolean ) = if (boolean) then "Yes" else "No"

    
