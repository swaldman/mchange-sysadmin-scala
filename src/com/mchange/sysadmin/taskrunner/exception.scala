package com.mchange.sysadmin.taskrunner

import com.mchange.sysadmin.SysadminException

class UnexpectedPriorState( message : String, cause : Throwable = null ) extends SysadminException(message, cause)
class MissingEnvironmentVariable( envVar : String ) extends SysadminException(s"Environment variable '${envVar}' is required, but missing.")

def abortUnexpectedPriorState( message : String ) : Nothing = throw new UnexpectedPriorState(message)

def extractFullStackTrace(t:Throwable) : String =
  val sw = new java.io.StringWriter()
  t.printStackTrace(new java.io.PrintWriter(sw))
  sw.toString()

extension (t : Throwable)
  def fullStackTrace : String = extractFullStackTrace(t)
