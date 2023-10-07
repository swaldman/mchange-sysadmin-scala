package com.mchange.sysadmin.taskrunner

import jakarta.mail.internet.MimeMessage

import com.mchange.sysadmin.Smtp

object Reporters:
  def stdOutOnly(formatter : AnyTaskRun => String = Reporting.defaultVerticalMessage) : List[Reporter] = List(
    ( run : AnyTaskRun ) => Console.out.println(formatter(run))
  )
  def stdErrOnly(formatter : AnyTaskRun => String = Reporting.defaultVerticalMessage) : List[Reporter] = List(
    ( run : AnyTaskRun ) => Console.err.println(formatter(run))
  )
  def smtpOnly(
    from : String = Env.Required.mailFrom,
    to : String = Env.Required.mailTo,
    compose : (String, String, AnyTaskRun, Smtp.Context) => MimeMessage = Reporting.defaultCompose,
    onlyMailFailures : Boolean = false
  )( using context : Smtp.Context ) : List[Reporter] = List(
    ( run : AnyTaskRun ) =>
      if !onlyMailFailures || !run.success then
        val msg = compose( from, to, run, context )
        context.sendMessage(msg)
  )
  def smtpAndStdOut(
    from : String = Env.Required.mailFrom,
    to : String = Env.Required.mailTo,
    compose : (String, String, AnyTaskRun, Smtp.Context) => MimeMessage = Reporting.defaultCompose,
    text : AnyTaskRun => String = Reporting.defaultVerticalMessage,
    onlyMailFailures : Boolean = false
  )( using context : Smtp.Context ) : List[Reporter] =
    smtpOnly(from,to,compose,onlyMailFailures) ++ stdOutOnly(text)

  def default(
    from : String = Env.Required.mailFrom,
    to : String = Env.Required.mailTo
  )( using context : Smtp.Context ) : List[Reporter] = smtpAndStdOut(from,to)

end Reporters
