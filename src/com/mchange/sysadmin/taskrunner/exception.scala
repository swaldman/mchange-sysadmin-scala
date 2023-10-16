package com.mchange.sysadmin.taskrunner

import com.mchange.sysadmin.SysadminException

class UnexpectedPriorState( message : String, cause : Throwable = null ) extends SysadminException(message, cause)
class MissingEnvironmentVariable( envVar : String ) extends SysadminException(s"Environment variable '${envVar}' is required, but missing.")
class NoReportersInitialized( message : String, cause : Throwable = null ) extends SysadminException(message, cause)

def abortUnexpectedPriorState( message : String ) : Nothing = throw new UnexpectedPriorState(message)

