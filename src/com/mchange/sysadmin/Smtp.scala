package com.mchange.sysadmin

import java.util.Date
import java.util.Properties
import jakarta.mail.*
import jakarta.mail.internet.*
import jakarta.mail.{Authenticator, PasswordAuthentication, Session, Transport}
import java.io.BufferedInputStream
import java.util.Properties
import scala.util.{Try,Failure,Success,Using}
import scala.jdk.CollectionConverters.*
import scala.io.Codec

object Smtp:
  object Env:
    val Host = "SMTP_HOST"
    val Port = "SMTP_PORT"
    val User = "SMTP_USER"
    val Password = "SMTP_PASSWORD"
    val StartTls = "SMTP_STARTTLS"
    val StartTlsAlt = "SMTP_START_TLS"
    val Debug = "SMTP_DEBUG"
    val Properties = "SMTP_PROPERTIES" // path to a properties file containing the properties below
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
  case class Address( email : String, displayName : Option[String] = None, codec : Codec = Codec.UTF8):
    def toInternetAddress = new InternetAddress( email, displayName.getOrElse(null), codec.charSet.name() )
  case class Auth( user : String, password : String ) extends Authenticator:
    override def getPasswordAuthentication() : PasswordAuthentication = new PasswordAuthentication(user, password)
  object Context:
    lazy given TrySmtpContext : Try[Context] = Try( Smtp.Context.Default )
    lazy given Default : Context =
      val props =
        sys.env.get(Env.Properties) match
          case Some( pathStr ) =>
            val path = os.Path(pathStr)
            val tryProps = Try:
              if os.exists(path) then
                Using.resource(new BufferedInputStream(os.read.inputStream(path))): is =>
                  val props = new Properties( System.getProperties )
                  props.load(is)
                  props
              else
                System.err.println(s"[SMTP config] No file at path specified by ${Env.Properties}, ${path}. Reverting to system properties only.")
                System.getProperties
            tryProps match
              case Success( props ) => props
              case Failure( t ) =>
                System.err.println(s"[SMTP config] Reverting to system properties only. Exception while loading SMTP properties at path specified by ${Env.Properties}, ${path}:")
                t.printStackTrace()
                System.getProperties
          case None =>
            System.getProperties
      end props
      this.apply( props, sys.env )
    def apply( properties : Properties, environment : Map[String,String]) : Context =
      val propsMap = properties.asScala
      val host = (propsMap.get(Prop.Host) orElse environment.get(Env.Host)).map(_.trim).getOrElse( throw new SmtpInitializationFailed("No SMTP Host Configured") )
      val mbUser = (propsMap.get(Prop.User) orElse environment.get(Env.User)).map(_.trim)
      val mbPassword = (propsMap.get(Prop.Password) orElse environment.get(Env.Password)).map(_.trim)
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
      val specifiedPort : Option[Int] = (propsMap.get(Prop.Port) orElse environment.get(Env.Port)).map( _.toInt )

      val startTlsEnabled =
        (propsMap.get(Prop.StartTlsEnable) orElse environment.get(Env.StartTls) orElse environment.get(Env.StartTlsAlt))
          .map(_.toBoolean)
          .getOrElse( specifiedPort == Some(Port.StartTls) )

      // XXX: Can there be unauthenticated TLS? I'm presuming authentication suggests one for or another of TLS
      def defaultPort = auth.fold(Port.Vanilla)(_ => if startTlsEnabled then Port.StartTls else Port.ImplicitTls)

      val port = specifiedPort.getOrElse( defaultPort )
      val debug = (propsMap.get(Prop.Debug) orElse environment.get(Env.Debug)).map(_.toBoolean).getOrElse(false)
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
  end Context

  private def setSubjectFromToCcBcc(
    msg     : MimeMessage,
    subject : String,
    from    : Address,
    to      : Seq[Address],
    cc      : Seq[Address] = Seq.empty,
    bcc     : Seq[Address] = Seq.empty
  )( using context : Smtp.Context ) : Unit =
    msg.setSubject(subject)
    msg.setFrom(from.toInternetAddress)
    to.foreach( address => msg.addRecipient(Message.RecipientType.TO, address.toInternetAddress) )
    cc.foreach( address => msg.addRecipient(Message.RecipientType.CC, address.toInternetAddress) )
    bcc.foreach( address => msg.addRecipient(Message.RecipientType.BCC, address.toInternetAddress) )
  end setSubjectFromToCcBcc

  def sendSimplePlaintext(
    plainText : String,
    subject   : String,
    from      : Address,
    to        : Seq[Address],
    cc        : Seq[Address] = Seq.empty,
    bcc       : Seq[Address] = Seq.empty
  )( using context : Smtp.Context ) : Unit =
    val msg = new MimeMessage(context.session)
    // last entry is highest priority!
    msg.setContent(plainText, "text/plain")
    setSubjectFromToCcBcc( msg, subject, from, to, cc, bcc )
    msg.setSentDate(new Date())
    msg.saveChanges()
    context.sendMessage(msg)
  end sendSimplePlaintext

  def sendSimpleHtmlPlaintextAlternative(
    htmlText  : String,
    plainText : String,
    subject   : String,
    from      : Address,
    to        : Seq[Address],
    cc        : Seq[Address] = Seq.empty,
    bcc       : Seq[Address] = Seq.empty
  )( using context : Smtp.Context ) : Unit =
    val msg = new MimeMessage(context.session)
    val htmlAlternative =
      val tmp = new MimeBodyPart()
      tmp.setContent(htmlText, "text/html")
      tmp
    val plainTextAlternative =
      val tmp = new MimeBodyPart()
      tmp.setContent(plainText, "text/plain")
      tmp
    // last entry is highest priority!
    val multipart = new MimeMultipart("alternative", plainTextAlternative, htmlAlternative)
    msg.setContent(multipart)
    setSubjectFromToCcBcc( msg, subject, from, to, cc, bcc )
    msg.setSentDate(new Date())
    msg.saveChanges()
    context.sendMessage(msg)
  end sendSimpleHtmlPlaintextAlternative
