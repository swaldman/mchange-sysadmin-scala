package com.mchange.sysadmin.taskrunner

import scala.collection.*
import com.mchange.codegenutil.*
import java.time.{Instant,ZoneId}
import java.time.temporal.ChronoUnit
import java.time.format.DateTimeFormatter.ISO_OFFSET_DATE_TIME

import scala.util.control.NonFatal

type AnyTaskRunner       = TaskRunner[?]
type AnyTask             = AnyTaskRunner#Task
type AnyTaskRun          = AnyTaskRunner#TaskType#Run
type AnyStep             = AnyTaskRunner#Step
type AnyStepRun          = AnyTaskRunner#StepType#Run
type AnyStepRunCompleted = AnyTaskRunner#StepType#RunType#Completed
type AnyStepRunSkipped   = AnyTaskRunner#StepType#RunType#Skipped

type Reporter = AnyTaskRun => Unit

lazy val hostname : Option[String] =
  try
    Some(os.proc("hostname").call( check=false, stdout = os.Pipe, stderr = os.Pipe ).out.trim())
  catch
    case NonFatal(t) =>  None

lazy val hostnameSimple : Option[String] = hostname.map: hn =>
  val dot = hn.indexOf('.')
  if dot > 0 then hn.substring(0, dot) else hn

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

    
