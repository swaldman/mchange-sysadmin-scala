package com.mchange.sysadmin.taskrunner

import scala.util.{Failure,Success,Try}
import scala.util.control.NonFatal
import jakarta.mail.internet.MimeMessage

import com.mchange.sysadmin.{Smtp, fullStackTrace}

object Reporters:

  def stdOutOnly(formatter : AnyTaskRun => String = Reporting.defaultVerticalMessage) : Set[Reporter] = Set(
    ( run : AnyTaskRun ) => Console.out.println(formatter(run))
  )
  
  def stdErrOnly(formatter : AnyTaskRun => String = Reporting.defaultVerticalMessage) : Set[Reporter] = Set(
    ( run : AnyTaskRun ) => Console.err.println(formatter(run))
  )

  private def smtpReporter(
    from : Option[String] = Env.Optional.mailFrom,
    to : Option[String] = Env.Optional.mailTo,
    compose : (String, String, AnyTaskRun, Smtp.Context) => MimeMessage = Reporting.defaultCompose,
    onlyMailFailures : Boolean = false
  )( using trySmtp : Try[Smtp.Context] ) : Either[List[String],Reporter] =
    val oopsies =
      val builder = List.newBuilder[String]
      if from.isEmpty then
        builder += s"SMTP reporting disabled. No from address (usually set as environment variable '${Env.MailFrom}') has been provided."
      if to.isEmpty then  
        builder += s"SMTP reporting disabled. No to address (usually set as environment variable '${Env.MailTo}') has been provided."
      if trySmtp.isFailure then
        builder += s"SMTP reporting disable due to exception while initializing SMTP context:\n ${trySmtp.failed.get.fullStackTrace}"
      builder.result
    if oopsies.isEmpty then  
      Right(
        ( run : AnyTaskRun ) =>
          if !onlyMailFailures || !run.success then
            val context = trySmtp.get
            val msg = compose( from.get, to.get, run, context )
            context.sendMessage(msg)
      )
    else
      Left( oopsies )
  
  def smtpOnlyOrNone(
    from : Option[String] = Env.Optional.mailFrom,
    to : Option[String] = Env.Optional.mailTo,
    compose : (String, String, AnyTaskRun, Smtp.Context) => MimeMessage = Reporting.defaultCompose,
    onlyMailFailures : Boolean = false
  )( using trySmtp : Try[Smtp.Context] ) : Set[Reporter] =
    smtpReporter(from,to,compose,onlyMailFailures) match
      case Left( oopsies ) =>
        oopsies.foreach( System.err.println )
        Set.empty
      case Right( reporter ) =>
        Set( reporter )
  
  def smtpOrFail(
    from : Option[String] = Env.Optional.mailFrom,
    to : Option[String] = Env.Optional.mailTo,
    compose : (String, String, AnyTaskRun, Smtp.Context) => MimeMessage = Reporting.defaultCompose,
    onlyMailFailures : Boolean = false
  )( using trySmtp : Try[Smtp.Context] ) : Set[Reporter] =
    smtpReporter(from,to,compose,onlyMailFailures) match
      case Left( oopsies ) =>
        throw new NoReportersInitialized( oopsies.mkString("\n") )
      case Right( reporter ) =>
        Set( reporter )

  def smtpAndStdOut(
    from : Option[String] = Env.Optional.mailFrom,
    to : Option[String] = Env.Optional.mailTo,
    compose : (String, String, AnyTaskRun, Smtp.Context) => MimeMessage = Reporting.defaultCompose,
    text : AnyTaskRun => String = Reporting.defaultVerticalMessage,
    onlyMailFailures : Boolean = false
  )( using trySmtp : Try[Smtp.Context] ) : Set[Reporter] =
    smtpOnlyOrNone(from,to,compose,onlyMailFailures) ++ stdOutOnly(text)

  def default(
    from : Option[String] = Env.Optional.mailFrom,
    to : Option[String] = Env.Optional.mailTo
  )( using trySmtp : Try[Smtp.Context] ) : Set[Reporter] = smtpAndStdOut(from,to)

end Reporters
