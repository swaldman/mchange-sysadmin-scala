package com.mchange.sysadmin

import javax.mail.*
import javax.mail.internet.*
import javax.mail.{Authenticator,PasswordAuthentication,Session,Transport}
import java.util.{Date,Properties}
import scala.collection.*
import scala.util.Using
import scala.util.control.NonFatal
import scala.jdk.CollectionConverters.*

object TaskRunner:

  def default(from : String, to : String) : TaskRunner =
    new TaskRunner with TaskRunner.SmtpLogging(from=from,to=to) with TaskRunner.ReportLogging()

  lazy val stdoutOnly: TaskRunner = new TaskRunner.ReportLogging(){}

  trait LineLogging( lines : Task.Run => Iterable[String], sink : String => Unit ) extends TaskRunner:
    abstract override def sendReports( taskRun : Task.Run ) : Unit =
      super.sendReports( taskRun )
      try
        lines( taskRun ).foreach( sink )
      catch
        case NonFatal(t) => t.printStackTrace
  end LineLogging

  trait ReportLogging( report : Task.Run => String = defaultVerticalMessage, sink : String => Unit = (text : String) => println(text) ) extends TaskRunner:
    abstract override def sendReports( taskRun : Task.Run ) : Unit =
      super.sendReports( taskRun )
      try
        sink(report( taskRun ))
      catch
        case NonFatal(t) => t.printStackTrace
  end ReportLogging

  object SmtpLogging:
    object Prop:
      val Host = "mail.smtp.host"
      val Port = "mail.smtp.port"
      val Auth = "mail.smtp.auth"
      val User = "mail.smtp.user"
      val Password = "mail.smtp.password" // this is NOT a standard javax.mail property AFAIK
      val StartTlsEnable = "mail.smtp.starttls.enable"
      val Debug = "mail.smtp.debug"
    object Port:
      // see e.g. https://sendgrid.com/blog/whats-the-difference-between-ports-465-and-587/
      val Vanilla     = 25
      val ImplicitTls = 465
      val StartTls    = 587
    case class Auth( user : String, password : String ) extends Authenticator:
      override def getPasswordAuthentication() : PasswordAuthentication = new PasswordAuthentication(user, password)
    object Context:
      given Context = apply(System.getProperties, sys.env)
      def apply( properties : Properties, environment : Map[String,String]) : Context =
        val propsMap = properties.asScala
        val host = (propsMap.get(Prop.Host) orElse environment.get("SMTP_HOST")).map(_.trim).getOrElse( throw new SysadminException("No SMTP Host Configured") )
        val mbUser = (propsMap.get(Prop.User) orElse environment.get("SMTP_USER")).map(_.trim)
        val mbPassword = (propsMap.get(Prop.Password) orElse environment.get("SMTP_PASSWORD")).map(_.trim)
        val auth =
          val mbFlagConfigured = propsMap.get(Prop.Auth)
          (mbFlagConfigured, mbUser, mbPassword) match
            case (Some("true") , Some(user), Some(password)) => Some(Auth(user, password))
            case (None         , Some(user), Some(password)) => Some(Auth(user, password))
            case (Some("false"), Some(_),Some(_)) =>
              System.err.println(s"WARNING: SMTP user and password are both configured, but property '${Prop.Auth}' is false, so authentication is disabled.")
              None
            case (Some("false"), _, _) => None
            case (Some(bad), Some(user), Some(password)) =>
              System.err.println(s"WARNING: Ignoring bad SMTP property '${Prop.Auth}' set to '${bad}'. User and password are set so authentication is enabled.")
              Some(Auth(user, password))
            case (None, Some(user), None) =>
              System.err.println(s"WARNING: A user '${user}' is configured, but no password is set, so authentication is disabled.")
              None
            case (_, _, _) =>
              None
        val startTlsEnabled = (propsMap.get(Prop.StartTlsEnable) orElse environment.get("SMTP_START_TLS") orElse environment.get("SMTP_STARTTLS")).map(_.toBoolean).getOrElse(false)

        // XXX: Can there be unauthenticated TLS? I'm presuming authentication suggests one for or another of TLS
        val defaultPort = auth.fold(Port.Vanilla)(_ => if startTlsEnabled then Port.StartTls else Port.ImplicitTls)

        val port = (propsMap.get(Prop.Port) orElse environment.get("SMTP_PORT")).map( _.toInt ).getOrElse( defaultPort )
        val debug = (propsMap.get(Prop.Debug) orElse environment.get("SMTP_DEBUG")).map(_.toBoolean).getOrElse(false)
        Context(host,port,auth,startTlsEnabled,debug)
    case class Context(
      host : String,
      port : Int = 25,
      auth : Option[Auth] = None,
      startTls : Boolean = false,
      debug    : Boolean = false
    )
  // see https://stackoverflow.com/questions/1990454/using-javamail-to-connect-to-gmail-smtp-server-ignores-specified-port-and-tries
  trait SmtpLogging( from : String, to : String, subject : Task.Run => String = defaultTitle, text : Task.Run => String = defaultVerticalMessage)(using context : SmtpLogging.Context) extends TaskRunner:
    val props =
      import SmtpLogging.Prop
      val tmp = new Properties()
      tmp.setProperty(Prop.Host,           context.host)
      tmp.setProperty(Prop.Port,           context.port.toString)
      tmp.setProperty(Prop.Auth,           context.auth.nonEmpty.toString)
      tmp.setProperty(Prop.StartTlsEnable, context.startTls.toString)
      tmp.setProperty(Prop.Debug,          context.debug.toString)
      tmp

    val session =
      val tmp = Session.getInstance(props, context.auth.getOrElse(null))
      tmp.setDebug(context.debug)
      tmp

    abstract override def sendReports( taskRun : Task.Run ) : Unit =
      super.sendReports( taskRun )
      try
        val msg = new MimeMessage(session)
        msg.setText(text(taskRun))
        msg.setSubject(subject(taskRun))
        msg.setFrom(new InternetAddress(from))
        msg.addRecipient(Message.RecipientType.TO, new InternetAddress(to))
        msg.setSentDate(new Date())
        msg.saveChanges()
        context.auth.fold(Transport.send(msg)): auth =>
          Using.resource(session.getTransport("smtps")): transport =>
            transport.connect(context.host, context.port, auth.user, auth.password);
            transport.sendMessage(msg, msg.getAllRecipients());
      catch
        case NonFatal(t) => t.printStackTrace
  end SmtpLogging

trait TaskRunner:
  def silentRun( task : Task ) : Task.Run =
    val seqRunsReversed = task.sequential.foldLeft( Nil : List[Step.Run] ): ( accum, next ) =>
      accum match
        case Nil => Step.Run.Completed(next) :: accum
        case head :: tail if head.success => Step.Run.Completed(next) :: accum
        case other => Step.Run.Skipped(next) :: accum

    val bestEffortReversed = task.bestAttemptCleanups.foldLeft( Nil : List[Step.Run] ): ( accum, next ) =>
      Step.Run.Completed(next) :: accum

    Task.Run(task,seqRunsReversed.reverse,bestEffortReversed.reverse)

  def sendReports( taskRun : Task.Run ) : Unit = ()

  def runAndReport( task : Task ) : Unit =
    val taskRun = this.silentRun( task )
    sendReports( taskRun )
