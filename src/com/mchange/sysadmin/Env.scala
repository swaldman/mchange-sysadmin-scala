package com.mchange.sysadmin

object Env:
  val MailTo   = "SYSADMIN_MAIL_TO"
  val MailFrom = "SYSADMIN_MAIL_FROM"

  object Required:
    private def required(envVar : String) : String = sys.env.get(envVar).getOrElse(throw new MissingEnvironmentVariable(envVar))

    def mailTo   = required(Env.MailTo)
    def mailFrom = required(Env.MailFrom)
