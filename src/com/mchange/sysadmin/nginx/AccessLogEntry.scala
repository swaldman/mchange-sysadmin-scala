package com.mchange.sysadmin.nginx

import java.time.Instant
import java.time.format.DateTimeFormatter

object AccessLogEntry:
  val LineRegex = """^(\S+) - (\S+) \[(.*)\] \"([^\"]*)\" (\d+) (\d+) \"([^\"]*)\" \"([^\"]*)\"$""".r
  val TimestampFormatter = DateTimeFormatter.ofPattern("dd/MMM/yyyy:HH:mm:ss Z")
  val RequestRegex = """^(\S+) (\S+)(.*)$""".r

  def apply( line : String ) : AccessLogEntry =
    line.trim match
      case LineRegex( remoteAddress, rawRemoteUser, rawTimeLocal, request, rawHttpCode, rawBytesReturned, rawReferrer, userAgent ) =>
        val remoteUser = if rawRemoteUser == "-" then None else Some(rawRemoteUser)
        val timeLocal = Instant.from( TimestampFormatter.parse( rawTimeLocal ) )
        val httpCode = rawHttpCode.toInt
        val bytesReturned = rawBytesReturned.toLong
        val referrer = if rawReferrer == "-" || rawReferrer.trim.isEmpty then None else Some(rawReferrer)
        AccessLogEntry( remoteAddress, remoteUser, timeLocal, request, httpCode, bytesReturned, referrer, userAgent )
      case other =>
        throw new Exception(s"Unexpected entry format: ${other}")
case class AccessLogEntry(remoteAddress : String, remoteUser : Option[String], timeLocal : Instant, request : String, httpCode : Int, bytesReturned : Long, referrer : Option[String], userAgent : String):
  lazy val (requestMethod : String, requestPath : String, httpVersionDeclaration : Option[String]) =
    request match
      case AccessLogEntry.RequestRegex( rm, rp, mbhv ) =>
        val hv =
          val raw = mbhv.trim
          if raw.nonEmpty then Some(raw) else None
        ( rm, rp, hv )

