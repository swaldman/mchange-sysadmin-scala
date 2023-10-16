package com.mchange.sysadmin.taskrunner

object Env:
  val MailTo   = "SYSADMIN_MAIL_TO"
  val MailFrom = "SYSADMIN_MAIL_FROM"

  object Optional:
    def mailTo   = sys.env.get(Env.MailTo)
    def mailFrom = sys.env.get(Env.MailFrom)

  object Required:
    private def required(envVar : String) : String = sys.env.get(envVar).getOrElse(throw new MissingEnvironmentVariable(envVar))

    def mailTo   = required(Env.MailTo)
    def mailFrom = required(Env.MailFrom)
