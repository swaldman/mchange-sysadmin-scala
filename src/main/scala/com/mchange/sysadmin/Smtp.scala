package com.mchange.sysadmin

import java.util.Properties
import javax.mail.*
import javax.mail.internet.*
import javax.mail.{Authenticator, PasswordAuthentication, Session, Transport}
import scala.util.Using
import scala.jdk.CollectionConverters.*

object Smtp:
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
    end apply
  end Context
  case class Context(
    host : String,
    port : Int = 25,
    auth : Option[Auth] = None,
    startTls : Boolean = false,
    debug    : Boolean = false
  ):
    lazy val props =
      val tmp = new Properties()
      tmp.setProperty(Prop.Host,           this.host)
      tmp.setProperty(Prop.Port,           this.port.toString)
      tmp.setProperty(Prop.Auth,           this.auth.nonEmpty.toString)
      tmp.setProperty(Prop.StartTlsEnable, this.startTls.toString)
      tmp.setProperty(Prop.Debug,          this.debug.toString)
      tmp

    lazy val session =
      val tmp = Session.getInstance(props, this.auth.getOrElse(null))
      tmp.setDebug(this.debug)
      tmp

    private def sendUnauthenticated( msg : MimeMessage ) : Unit = // untested
      // Transport.send(msg)
      Using.resource(session.getTransport("smtp")): transport =>
        transport.connect(this.host, this.port, null, null)
        transport.sendMessage(msg, msg.getAllRecipients())

    private def sendAuthenticated( msg : MimeMessage, auth : Auth ) : Unit =
      Using.resource(session.getTransport("smtps")): transport =>
        transport.connect(this.host, this.port, auth.user, auth.password);
        transport.sendMessage(msg, msg.getAllRecipients());

    def sendMessage( msg : MimeMessage ) =
      this.auth.fold(sendUnauthenticated(msg))(auth => sendAuthenticated(msg,auth))
