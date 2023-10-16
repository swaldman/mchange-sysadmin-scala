package com.mchange.sysadmin

class SysadminException( message : String, cause : Throwable = null ) extends Exception(message, cause)

class SmtpInitializationFailed( message : String, cause : Throwable = null ) extends SysadminException(message, cause) 

def extractFullStackTrace(t:Throwable) : String =
  val sw = new java.io.StringWriter()
  t.printStackTrace(new java.io.PrintWriter(sw))
  sw.toString()

extension (t : Throwable)
  def fullStackTrace : String = extractFullStackTrace(t)
