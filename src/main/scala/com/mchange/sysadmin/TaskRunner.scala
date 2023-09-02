package com.mchange.sysadmin

import scala.collection.*
import scala.util.control.NonFatal
import javax.mail.*
import javax.mail.internet.*
import javax.mail.{Authenticator, PasswordAuthentication, Session, Transport}
import scala.util.Using
import scala.jdk.CollectionConverters.*
import com.mchange.codegenutil.*
import com.mchange.sysadmin.Task.Run.usualSuccessCriterion

object Task:

    //def default(from : String, to : String) : TaskRunner =
    //  new TaskRunner with TaskRunner.SmtpLogging(from=from,to=to) with TaskRunner.ReportLogging()

    // def stdoutOnly: TaskRunner = new TaskRunner.ReportLogging(){}

  object Run:
    def usualSuccessCriterion(run : Run) = run.sequential.isEmpty || run.sequential.last.success
  trait Run:
    def name : String
    def sequential : List[Step.Run]
    def bestAttemptCleanUps : List[Step.Run]
    def success : Boolean

  object Step:
    sealed trait Result:
      def exitCode: Option[Int]
      def stepOut : String
      def stepErr : String
      def carryForwardDescription : Option[String]
    end Result
    object Run:
      sealed trait Completed extends Run:
        def result : Result
      sealed trait Skipped extends Run
    sealed trait Run:
      def step         : Step
      def success      : Boolean
  sealed trait Step:
    def name              : String
    def environment       : Map[String,String]
    def workingDirectory  : os.Path
    def actionDescription : String
  end Step


  object Reporting:
    def defaultTitle( run : Task.Run ) =
      hostname.fold("TASK")(hn => "[" + hn + "]") + ": " + run.name + " -- " + (if run.success then "SUCCEEDED" else "FAILED")

    def defaultVerticalMessage( run : Task.Run ) =
      val mainSection =
        s"""|=====================================================================
            | ${defaultTitle(run)}
            |=====================================================================
            | Timestamp: ${timestamp}
            | Succeeded overall? ${if run.success then "Yes" else "No"}
            |
            | SEQUENTIAL:
            |${defaultVerticalMessageSequential(run.sequential)}""".stripMargin.trim + LineSep + LineSep

      def cleanupsSectionIfNecessary =
        s"""|-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-
            |
            | BEST-ATTEMPT CLEANUPS:
            |${defaultVerticalMessageBestAttemptCleanups(run.bestAttemptCleanUps)}""".stripMargin.trim

      val midsection = if run.bestAttemptCleanUps.isEmpty then "" else (cleanupsSectionIfNecessary + LineSep + LineSep)

      val footer =
        s"""|
            |=====================================================================
            |.   .   .   .   .   .   .   .   .   .   .   .   .   .   .   .   .   .""".stripMargin.trim + LineSep

      mainSection + midsection + footer

    def defaultVerticalMessageSequential( sequential : List[Task.Step.Run] ) : String =
      val tups = immutable.LazyList.from(1).zip(sequential)
      val untrimmed = tups.foldLeft(""): (accum, next) =>
        accum + (LineSep*2) + defaultVerticalMessage(next)
      untrimmed.trim

    def defaultVerticalMessageBestAttemptCleanups( bestAttemptCleanups : List[Task.Step.Run] ) : String =
      val untrimmed = bestAttemptCleanups.foldLeft(""): (accum, next) =>
        accum + (LineSep*2) + defaultVerticalMessage(next)
      untrimmed.trim

    def defaultVerticalMessage( run : Task.Step.Run ) : String = defaultVerticalMessage(None, run)

    def defaultVerticalMessage( tup : Tuple2[Int,Task.Step.Run]) : String = defaultVerticalMessage(Some(tup(0)),tup(1))

    def defaultVerticalMessage( index : Option[Int], run : Task.Step.Run ) : String =
//      def action( step : Step ) : String =
//        step match
//          case exec : Step.Exec => s"Parsed command: ${exec.parsedCommand}"
//          case arbitrary : Step.Arbitrary => "Action: <internal function>"
      val body = run match
        case completed : Step.Run.Completed => defaultVerticalBody(completed)
        case skipped   : Step.Run.Skipped   => defaultVerticalBody(skipped)
      val header =
        s"""|---------------------------------------------------------------------
            | ${index.fold(run.step.name)(i => i.toString + ". " + run.step.name)}
            |---------------------------------------------------------------------
            | ${run.step.actionDescription}
            | Succeeded? ${if run.success then "Yes" else "No"}""".stripMargin.trim
      header + LineSep + body

    def defaultVerticalBody(completed : Task.Step.Run.Completed) : String =
      val stdOutContent =
        if completed.result.stepOut.nonEmpty then completed.result.stepOut else "<EMPTY>"
      val stdErrContent =
        if completed.result.stepErr.nonEmpty then completed.result.stepErr else "<EMPTY>"
      val mbExitCode = completed.result.exitCode.fold(""): code =>
        s"""| Exit code: ${code}
            |
            |""".stripMargin // don't trim, we want the initial space
      val stdOutStdErr =
        s"""| stdout:
            |${increaseIndent(5)(stdOutContent)}
            |
            | stderr:
            |${increaseIndent(5)(stdErrContent)}""".stripMargin // don't trim, we want the initial space
      val mbCarryForward = completed.result.carryForwardDescription.fold(""): cfd =>
        s"""|
            | carryforward:
            |${increaseIndent(5)(cfd)}""".stripMargin
      mbExitCode + stdOutStdErr + mbCarryForward

    // Leave this stuff out
    // We end up mailing sensitive stuff from the environment
    //
    //      |
    //      | Working directory:
    //      |${increaseIndent(5)(completed.step.workingDirectory.toString)}
    //      |
    //      | Environment:
    //      |${increaseIndent(5)(pprint.PPrinter.BlackWhite(completed.step.environment).plainText)}"""

    def defaultVerticalBody(skipped : Task.Step.Run.Skipped) : String =
      s"""|
          | SKIPPED!""".stripMargin // don't trip, we want the linefeed and initial space

  end Reporting

  /*
  trait LineLogging[T]( lines : Task[T]#Run => Iterable[String], sink : String => Unit ) extends Task[T]:
    abstract override def sendReports( taskRun : Run ) : Unit =
      super.sendReports( taskRun )
      try
        lines( taskRun ).foreach( sink )
      catch
        case NonFatal(t) => t.printStackTrace
  end LineLogging

  trait ReportLogging[T]( report : Task[T]#Run => String = Task.Reporting.defaultVerticalMessage[T], sink : String => Unit = (text : String) => println(text) ) extends Task[T]:
    abstract override def sendReports( taskRun : Run ) : Unit =
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
  trait SmtpLogging[T]( from : String, to : String, subject : Task[T]#Run => String = Task.Reporting.defaultTitle[T], text : Task[T]#Run => String = Task.Reporting.defaultVerticalMessage[T])(using context : SmtpLogging.Context) extends Task[T]:
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

    abstract override def sendReports( taskRun : Run ) : Unit =
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
  */

trait Task[T]( init : T ):

  case class Run(
    sequential : List[Task.this.Step.Run],
    bestAttemptCleanUps : List[Task.this.Step.Run],
    isSuccess : Run => Boolean = Task.Run.usualSuccessCriterion
  ) extends Task.Run:
    def success = isSuccess( this )

  type Carrier = (T, Int, String, String) => T

  def arbitraryExec( prior : T, thisStep : Task.this.Step.Arbitrary, command : os.Shellable, carryForward : Carrier ) : Step.Result =
    val tmp = os.proc(command).call( cwd = thisStep.workingDirectory, env = thisStep.environment, check = false, stdin = os.Pipe, stdout = os.Pipe, stderr = os.Pipe )
    val exitCode = tmp.exitCode
    val stepOut = tmp.out.trim()
    val stepErr = tmp.err.trim()
    Step.Result( Some(exitCode), stepOut, stepErr )( carryForward( prior, tmp.exitCode, tmp.out.trim(), tmp.err.trim() ) )
  def arbitraryExec( prior : T, thisStep : Task.this.Step.Arbitrary, command : os.Shellable )(using ev : T =:= Unit) : Step.Result = arbitraryExec( prior, thisStep, command, (_,_,_,_) => ().asInstanceOf[T] )

  object Step:
    object Result:
      val defaultCarryForwardDescriber : T => Option[String] =
        case _ : Unit => None
        case other    => Some( other.toString )
      def emptyWithCarryForward( t : T ) : Result = Result(None,"","")(t)
    case class Result(
      exitCode: Option[Int],
      stepOut : String,
      stepErr : String
    )(
      val carryForward : T,
      val carryForwardDescriber : T => Option[String] = defaultCarryForwardDescriber
    ) extends Task.Step.Result:
      def carryForwardDescription = carryForwardDescriber(carryForward)
    def exitCodeIsZero(run : Task.Step.Run.Completed) : Boolean = run.result.exitCode.fold(false)( _ == 0 )
    def stepErrIsEmpty(run : Task.Step.Run.Completed) : Boolean = run.result.stepErr.isEmpty
    case class Arbitrary (
      name : String,
      workingDirectory : os.Path = os.pwd,
      environment : immutable.Map[String,String] = sys.env,
      actionDescription : String = "Action: <internal function>"
    )(
      val action : (T, Task.this.Step.Arbitrary) => Result,
      val isSuccess : Task.this.Step.Run.Completed => Boolean
    ) extends Task.this.Step:
      override def toString() = s"Step.Arbitrary(name=${name}, workingDirectory=${workingDirectory}, environment=********)"
    case class Exec (
      name : String,
      workingDirectory : os.Path = os.pwd,
      environment : immutable.Map[String,String] = sys.env,
    )(
      val parsedCommand : List[String],
      val carrier : Carrier,
      val isSuccess : Task.this.Step.Run.Completed => Boolean = exitCodeIsZero,
    ) extends Task.this.Step:
      def actionDescription = s"Parsed command: ${parsedCommand}"
      override def toString() = s"Step.Exec(name=${name}, parsedCommand=${parsedCommand}, workingDirectory=${workingDirectory}, environment=********)"
    object Run:
      object Completed:
        def apply( prior : T, step : Task.this.Step ) : Task.this.Step.Run.Completed =
          val result =
            try
              step match
                case exec : Task.this.Step.Exec =>
                  val tmp = os.proc(exec.parsedCommand).call( cwd = exec.workingDirectory, env = exec.environment, check = false, stdin = os.Pipe, stdout = os.Pipe, stderr = os.Pipe )
                  Task.this.Step.Result( Some(tmp.exitCode), tmp.out.trim(), tmp.err.trim() )( prior )
                case arbitrary : Task.this.Step.Arbitrary =>
                  arbitrary.action( prior, arbitrary )
            catch
              case NonFatal(t) => Step.Result(None,"",t.fullStackTrace)(prior)
          Task.this.Step.Run.Completed.apply( step, result )
      case class Completed( step : Task.this.Step, result : Task.this.Step.Result ) extends Task.this.Step.Run, Task.Step.Run.Completed:
        def success : Boolean = step.isSuccess(this)
      case class Skipped( step : Step ) extends Task.this.Step.Run:
        val success : Boolean = false
    sealed trait Run extends Task.Step.Run:
      def step         : Step
      def success      : Boolean
  sealed trait Step extends Task.Step:
    def isSuccess : Task.this.Step.Run.Completed => Boolean
  end Step

  def silentRun() : Run =
    val seqRunsReversed = this.sequential.foldLeft( Nil : List[Step.Run] ): ( accum, next ) =>
      accum match
        case Nil => Step.Run.Completed(init, next) :: accum
        case (head : Step.Run.Completed) :: tail if head.success => Step.Run.Completed(head.result.carryForward, next) :: accum
        case other => Step.Run.Skipped(next) :: accum

    val bestEffortReversed = this.bestAttemptCleanups.foldLeft( Nil : List[Step.Run] ): ( accum, next ) =>
      val lastCompleted =
        seqRunsReversed
          .collect { case completed : this.Step.Run.Completed => completed }
          .headOption
      Step.Run.Completed(lastCompleted.fold(init)(_.result.carryForward),next) :: accum

    Run(seqRunsReversed.reverse,bestEffortReversed.reverse)

  def sendReports( taskRun : Run ) : Unit = ()

  def runAndReport() : Unit =
    val run = this.silentRun()
    sendReports( run )

  def name                : String
  def sequential          : List[Step]
  def bestAttemptCleanups : List[Step]


