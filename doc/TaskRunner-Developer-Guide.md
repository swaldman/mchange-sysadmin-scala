# TaskRunner Developer Guide

## Table of Contents

1. [Introduction](#introduction)
2. [Core Concepts](#core-concepts)
3. [Getting Started](#getting-started)
4. [Carryforward: State Management](#carryforward-state-management)
5. [Step Types](#step-types)
6. [Task Execution Flow](#task-execution-flow)
7. [Reporting](#reporting)
8. [Parallelization](#parallelization)
9. [Environment Configuration](#environment-configuration)
10. [Advanced Features](#advanced-features)
11. [Best Practices](#best-practices)
12. [API Reference](#api-reference)
13. [FAQs](#faqs)

---

## Introduction

TaskRunner is a Scala 3 framework for building reliable, observable system administration scripts. It provides a structured approach to executing tasks with comprehensive reporting, error handling, and state management.

### Key Features

- **Structured task execution** with sequential and best-effort steps
- **Comprehensive reporting** via email (HTML/text), stdout, stderr, or custom reporters
- **State tracking** through a "carryforward" mechanism
- **Parallelization** support for setups, followups, and reporting
- **Resilient error handling** with graceful degradation
- **Rich output capture** including exit codes, stdout, stderr, and custom notes

### When to Use TaskRunner

TaskRunner is ideal for:

- Database backup and restore operations
- Certificate renewal and deployment
- System maintenance and cleanup tasks
- Multi-step deployment workflows
- Any sysadmin task requiring detailed audit trails

---

## Core Concepts

### The Three Categories of Steps

TaskRunner organizes steps into three categories:

#### 1. Best-Effort Setups

- Execute **before** sequential steps
- All setups are **always attempted**, regardless of other setup failures
- Receive the **initial** carryforward value
- Their carryforward results are **discarded**
- Can be parallelized
- Use cases: checking prerequisites, logging startup state, cleanup of prior artifacts

#### 2. Sequential Steps

- Execute **in order**, one after another
- If a step **fails**, all remaining sequential steps are **skipped**
- Pass carryforward **from one step to the next**
- This is where your main task logic lives
- Cannot be parallelized (by design)
- Use cases: the core workflow of your task

#### 3. Best-Effort Followups

- Execute **after** sequential steps
- All followups are **always attempted**, regardless of other failures
- Receive the carryforward from the **last completed sequential step**
- Their carryforward results are **discarded**
- Can be parallelized
- Use cases: cleanup, logging final state, sending notifications

### Essential vs Non-Essential Steps

By default:
- **Sequential steps** are considered essential (failure causes task failure)
- **Best-effort steps** (setups/followups) are non-essential (don't affect task success)

You can override this by setting the `essential` field on any step:
- `essential = Some(true)` - Failure causes overall task failure
- `essential = Some(false)` - Failure doesn't affect overall task success
- `essential = None` - Use default behavior (sequential=true, best-effort=false)

### Success Criteria

A task is considered successful when:
1. All essential setups succeed
2. All sequential steps succeed OR are non-essential and fail
3. All essential followups succeed

---

## Getting Started

### Installation

Add to your `build.mill` (Mill) or `build.sbt` (SBT):

```scala
// Mill 1.0.x
def mvnDeps = Seq(
  mvn"com.mchange::mchange-sysadmin-scala:0.2.0-SNAPSHOT"
)

// SBT
libraryDependencies += "com.mchange" %% "mchange-sysadmin-scala" % "0.2.0-SNAPSHOT"
```

### Minimal Example

```scala
import com.mchange.sysadmin.taskrunner.*

// Create a TaskRunner parameterized with Unit (no state tracking)
val taskRunner = TaskRunner[Unit]

// Import the DSL
import taskRunner.*

// Define a simple task
val myTask = new Task:
  def name = "Hello World Task"

  def init = () // Initial carryforward value

  def bestEffortSetups = Set.empty

  def sequential = List(
    Step.Exec("Say Hello", List("echo", "Hello, World!"))
  )

  def bestEffortFollowups = Set.empty

// Run with stdout reporter
val reporters = Reporters.stdOutOnly()
runAndReport(myTask, reporters)
```

---

## Carryforward: State Management

The "carryforward" is TaskRunner's mechanism for passing state between steps. It's a type parameter `T` that flows through your task execution.

### Using Unit (No State)

The simplest approach when you don't need to track state:

```scala
val taskRunner = TaskRunner[Unit]
import taskRunner.*

val task = new Task:
  def init = ()
  // ... rest of task definition
```

### Using a Custom Type

For tasks that need to track state:

```scala
// Define your state type
case class BackupState(
  tmpDir: Option[os.Path] = None,
  backupFile: Option[os.Path] = None,
  backupSize: Long = 0
)

val taskRunner = TaskRunner[BackupState]
import taskRunner.*

val task = new Task:
  def init = BackupState()

  def sequential = List(
    Step.Arbitrary("Create temp dir"): (state, self) =>
      val tmpDir = os.temp.dir()
      Step.Result.onward(state.copy(tmpDir = Some(tmpDir)))
    ,

    Step.Arbitrary("Perform backup"): (state, self) =>
      val backupFile = state.tmpDir.get / "backup.sql"
      // ... perform backup logic ...
      Step.Result.onward(
        state.copy(
          backupFile = Some(backupFile),
          backupSize = os.size(backupFile)
        )
      )
  )
```

### Carryforward Describer

Control how the carryforward is displayed in reports:

```scala
Step.Result(
  exitCode = Some(0),
  stepOut = "",
  stepErr = "",
  carryForward = myState,
  carryForwardDescriber = state => Some(s"Backup: ${state.backupFile.getOrElse("none")}")
)
```

---

## Step Types

### Step.Exec - Execute Shell Commands

For running external commands via `os.proc`:

```scala
Step.Exec(
  name = "List files",
  parsedCommand = List("ls", "-la", "/tmp"),
  workingDirectory = os.pwd,              // optional, defaults to os.pwd
  environment = sys.env,                  // optional, defaults to sys.env
  carrier = Carrier.carryPrior,           // optional, defaults to Carrier.carryPrior
  isSuccess = Step.exitCodeIsZero,        // optional, defaults to exitCodeIsZero
  essential = None                        // optional, defaults based on step category
)
```

**Parameters:**
- `name` - Human-readable step name (appears in reports)
- `parsedCommand` - Command as a `List[String]` (already split, not a shell string)
- `workingDirectory` - Directory to execute command in
- `environment` - Environment variables as a `Map[String,String]`
- `carrier` - Function to extract data from command output into carryforward
- `isSuccess` - Custom success criterion (see below)
- `essential` - Whether step failure causes task failure

**Important:** Commands are executed via `os.proc`, not a shell. Use `List("ls", "-la")`, not `List("ls -la")`.

#### Using Carrier Functions

The `carrier` parameter allows you to extract data from command output and incorporate it into your carryforward state:

```scala
type Carrier = (T, Int, String, String) => T
//              ^   ^    ^       ^
//              |   |    |       stderr
//              |   |    stdout
//              |   exit code
//              prior carryforward
```

Example:

```scala
case class State(hostname: String = "")

val getHostname = Step.Exec(
  name = "Get hostname",
  parsedCommand = List("hostname"),
  carrier = (state, exitCode, stdout, stderr) =>
    state.copy(hostname = stdout.trim())
)
```

If you just want to carry the prior state forward, you can use a predefined `Carrier`, `carryPrior`.

### Step.Arbitrary - Custom Actions

```scala
// Simple case - just updating state
Step.Arbitrary("Custom logic"): (state, self) =>
  val newState = doSomething(state)
  Step.Result.onward(newState)

// When you need to capture output or specify exit codes
Step.Arbitrary("Custom logic with output"): (state, self) =>
  val result = doSomething(state)
  Step.Result(
    exitCode = Some(0),       // None if not applicable
    stepOut = "output text",
    stepErr = "",
    carryForward = result
  )
```

**With optional parameters:**

```scala
Step.Arbitrary(
  name = "Custom logic",
  isSuccess = Step.defaultIsSuccess,     // optional
  workingDirectory = os.pwd,             // optional
  environment = sys.env,                 // optional
  actionDescription = None,              // optional, appears in reports
  essential = None                       // optional
): (state, self) =>
  // Your custom logic here
  val result = doSomething(state)

  Step.Result(
    exitCode = Some(0),       // None if not applicable
    stepOut = "output text",
    stepErr = "",
    carryForward = result
  )
```

**Action Function Signature:**
```scala
(state: T, self: Step.Arbitrary) => Step.Result
```

The action receives:
1. `state` - The prior carryforward value (type `T`)
2. `self` - The step itself (for accessing step metadata, useful with `arbitraryExec`)

And must return a `Step.Result` containing:
- `exitCode` - Optional exit code (use `None` for pure Scala actions)
- `stepOut` - Captured output text
- `stepErr` - Captured error text
- `carryForward` - Updated carryforward value

### Custom Success Criteria

Both step types accept an `isSuccess` function:

```scala
def isSuccess: Step.Run.Completed => Boolean
```

Built-in options:
- `Step.exitCodeIsZero` - Success if exit code is 0
- `Step.stepErrIsEmpty` - Success if stderr is empty
- `Step.defaultIsSuccess` - Success if exit code is 0 OR (no exit code AND stderr is empty)

Custom example:

```scala
Step.Exec(
  name = "Grep for pattern",
  parsedCommand = List("grep", "pattern", "file.txt"),
  isSuccess = run => {
    // Grep returns 1 if no match, which is okay for us
    run.result.exitCode.exists(code => code == 0 || code == 1)
  }
)
```

### Helper Methods: arbitraryExec

For running shell commands within arbitrary step actions.

It returns a `Step.Result`, which will include the exit code of the exec'ed process, and which can be returned directly as the parent step's result,
or accessed during continuing logic.

You need to supply a `Carrier`, a function that determines what new state will appear in the returned result. If you just want to retain the prior state, you can use `carryPrior`.

```scala
Step.Arbitrary("Complex operation"): (state, self) =>
  // Run a command and extract its output
  arbitraryExec(
    state,
    self,
    os.proc("some", "command"),
    carrier = (state, exitCode, stdout, stderr) =>
      // Process output and update state
      state.updated(stdout)
  )
```

---

## Task Execution Flow

### 1. Task Definition

Implement the `Task` trait:

```scala
val task = new Task:
  def name: String                     // Task name (appears in reports)
  def init: T                          // Initial carryforward value
  def bestEffortSetups: Set[Step]      // Can be empty
  def sequential: List[Step]           // Can be empty
  def bestEffortFollowups: Set[Step]   // Can be empty
```

### 2. Execution Order

```
┌─────────────────────────────────────┐
│   All Best-Effort Setups            │
│   (parallel if configured)          │
│   - All attempted                   │
│   - Each gets init carryforward     │
└─────────────────────────────────────┘
                 ↓
┌─────────────────────────────────────┐
│   Sequential Steps                  │
│   (always sequential)               │
│   - Executes in order               │
│   - Carryforward flows through      │
│   - Stops on first failure          │
└─────────────────────────────────────┘
                 ↓
┌─────────────────────────────────────┐
│   All Best-Effort Followups         │
│   (parallel if configured)          │
│   - All attempted                   │
│   - Each gets last completed CF     │
└─────────────────────────────────────┘
```

### 3. Carryforward Flow

**Setups:**
- Each setup receives `init`
- Their updated carryforwards are discarded

**Sequential:**
- First step receives `init`
- Each subsequent step receives the carryforward from the previous completed step
- If a step is skipped, the carryforward doesn't change

**Followups:**
- Each followup receives the carryforward from the last **completed** sequential step
- If no sequential step completed, they receive `init`
- Their updated carryforwards are discarded

### 4. Skipping Behavior

A sequential step is skipped if:
- Any prior essential setup failed, OR
- Any prior essential sequential step failed

When a step is skipped:
- It doesn't execute
- It's marked as `Step.Run.Skipped` in the report
- The carryforward doesn't change

---

## Reporting

### Reporter Type

A reporter is simply a function:

```scala
type Reporter = AnyTaskRun => Unit
```

It receives the complete task run and can do anything with it (send email, print to console, write to file, etc.).

**What is `AnyTaskRun`?**

`AnyTaskRun` is a type alias for `TaskRunner[?]#Task.Run`, representing the complete execution result of a task. It contains:

- `task: Task` - The task that was executed
- `bestEffortSetups: Set[Step.Run.Completed]` - Results from all setup steps
- `sequential: List[Step.Run]` - Results from sequential steps (may include `Completed` or `Skipped`)
- `bestEffortFollowups: Set[Step.Run.Completed]` - Results from all followup steps
- `success: Boolean` - Whether the overall task succeeded

Each `Step.Run` contains the step definition and its execution result (exit code, stdout, stderr, carryforward state).

For detailed API information, see the [Scaladoc](https://javadoc.io/doc/com.mchange/mchange-sysadmin-scala_3).

### Built-in Reporters

#### Stdout Reporter

```scala
val reporters: Set[Reporter] = Reporters.stdOutOnly()
```

Prints formatted text report to stdout.

#### Stderr Reporter

```scala
val reporters: Set[Reporter] = Reporters.stdErrOnly()
```

Prints formatted text report to stderr.

#### SMTP Reporter

```scala
// Configure SMTP per mailutil docs (e.g., via SMTP_PROPERTIES env var)
// The given Try[Smtp.Context] is automatically provided

val reporters: Set[Reporter] = Reporters.smtpOnlyOrNone(
  from = Some("admin@example.com"),
  to = Some("alerts@example.com")
)
```

Sends HTML and plain text email reports.

#### Combined Reporters

```scala
val reporters: Set[Reporter] = Reporters.smtpAndStdOut(
  from = Some("admin@example.com"),
  to = Some("alerts@example.com")
)
```

Or combine manually:

```scala
val reporters: Set[Reporter] = Set(
  Reporters.stdOutOnly(),
  Reporters.smtpOnlyOrNone(from = ..., to = ...)
).flatten
```

### Default Reporter

Uses environment variables for email configuration:

```scala
// Set environment variables:
// SYSADMIN_MAIL_FROM=admin@example.com
// SYSADMIN_MAIL_TO=alerts@example.com
// Configure SMTP per mailutil docs (e.g., via SMTP_PROPERTIES)

val reporters: Set[Reporter] = Reporters.default()
```

### Custom Reporters

```scala
val customReporter: Reporter = (run: AnyTaskRun) => {
  // Access task run data
  val success = run.success
  val taskName = run.task.name

  // Process best-effort setups
  run.bestEffortSetups.foreach { stepRun =>
    println(s"Setup: ${stepRun.step.name} - ${stepRun.success}")
  }

  // Process sequential steps
  run.sequential.foreach {
    case completed: AnyStepRunCompleted =>
      println(s"Step: ${completed.step.name}")
      println(s"  Exit code: ${completed.result.exitCode}")
      println(s"  Output: ${completed.result.stepOut}")
    case skipped: AnyStepRunSkipped =>
      println(s"Step: ${skipped.step.name} - SKIPPED")
  }

  // Process followups
  run.bestEffortFollowups.foreach { stepRun =>
    println(s"Followup: ${stepRun.step.name} - ${stepRun.success}")
  }
}

val reporters: Set[Reporter] = Set(customReporter)
```

### Report Content

Reports include:
- **Overall task success/failure**
- **Timestamp** of execution
- **Hostname** (if available)
- **For each step:**
  - Step name and sequence number
  - Success/failure status
  - Essential/non-essential annotation
  - Exit code (if applicable)
  - Stdout content
  - Stderr content
  - Notes (if provided)
  - Carryforward state (if not Unit)
  - Action description (for Exec steps: the command; for Arbitrary: optional custom text)

### Email Report Format

HTML reports include:
- Color-coded step indicators (green for successful steps, red for failed steps, yellow for skipped steps)
- Structured sections for setups, sequential steps, and followups
- Pretty-printed carryforward state
- Both HTML and plain text versions (multipart MIME)

### Customizing Report Composition

```scala
import jakarta.mail.internet.MimeMessage
import com.mchange.mailutil.Smtp

def myCompose(
  from: String,
  to: String,
  run: AnyTaskRun,
  context: Smtp.Context
): MimeMessage = {
  // Build custom message
  // See Reporting.defaultCompose for example
  ???
}

val reporters: Set[Reporter] = Reporters.smtpOnlyOrNone(
  compose = myCompose
)
```

---

## Parallelization

### Configuration

By default, all steps execute sequentially. To enable parallelization:

```scala
import scala.concurrent.ExecutionContext.Implicits.global

val taskRunner = TaskRunner[Unit](
  Parallelize(Parallelizable.Setups, Parallelizable.Followups)
)
```

**Parallelization Options:**

```scala
// Never parallelize (default)
Parallelize.Never

// Parallelize specific categories
Parallelize(Parallelizable.Setups)
Parallelize(Parallelizable.Followups)
Parallelize(Parallelizable.Reporting)
Parallelize(Parallelizable.Setups, Parallelizable.Followups)
Parallelize(Parallelizable.Setups, Parallelizable.Followups, Parallelizable.Reporting)
```

### What Can Be Parallelized

- **Setups** - All setup steps can run in parallel
- **Followups** - All followup steps can run in parallel
- **Reporting** - All reporters can run in parallel
- **Sequential steps** - NEVER parallelized (by design)

### ExecutionContext Requirement

When parallelization is enabled, you must provide an `ExecutionContext`:

```scala
import scala.concurrent.ExecutionContext

given ec: ExecutionContext = ExecutionContext.global
// or create a custom one:
// given ec: ExecutionContext = ExecutionContext.fromExecutor(
//   java.util.concurrent.Executors.newFixedThreadPool(4)
// )

val taskRunner = TaskRunner[Unit](
  Parallelize(Parallelizable.Setups)
)
```

### Thread Safety Considerations

When using parallelization:
- Ensure your `Step.Arbitrary` actions are thread-safe if they share mutable state
- File operations should use unique file paths
- Be cautious with shared resources

---

## Environment Configuration

### Email Configuration

If you'd like e-mail reporting, TaskRunner expects an `Smtp.Context` to be available (see [below](#smtp_context)) as well as the following environment variables:

```bash
export SYSADMIN_MAIL_FROM=admin@example.com
export SYSADMIN_MAIL_TO=alerts@example.com
```


### SMTP Context

For email reporting, reporters require a `given Try[Smtp.Context]` from `com.mchange.mailutil` in scope. **In most cases, you don't need to write this explicitly.**

The `Smtp.Context` companion object automatically provides a `given Try[Smtp.Context]` when you configure SMTP via:

1. **Properties file** (recommended) - via `SMTP_PROPERTIES` environment variable or `mail.smtp.properties` system property
2. **Environment variables** - `SMTP_HOST`, `SMTP_PORT`, `SMTP_USER`, `SMTP_PASSWORD`, etc.
3. **System properties** - Java system properties for SMTP configuration

For these standard configuration methods, simply configure your SMTP settings as described in the [mailutil configuration documentation](https://github.com/swaldman/mailutil?tab=readme-ov-file#configuration), and the `given Try[Smtp.Context]` will be automatically available.

**Only if you configure SMTP in code** do you need to explicitly provide a `given Try[Smtp.Context]`:

```scala
import scala.util.Try
import com.mchange.mailutil.Smtp

given trySmtp: Try[Smtp.Context] = Try {
  Smtp.Context(
    host = "smtp.example.com",
    // ... other parameters
  )
}
```

---

## Advanced Features

### Adding Notes to Steps

Enhance reports with custom notes:

```scala
Step.Arbitrary("Perform backup"): (state, self) =>
  val backupFile = createBackup()
  val size = os.size(backupFile)

  Step.Result(
    exitCode = Some(0),
    stepOut = "",
    stepErr = "",
    carryForward = state.copy(backupFile = Some(backupFile))
  ).withNotes(
    s"Backup size: ${friendlyFileSize(size)}\nLocation: ${backupFile}"
  )
```

The `withNotes` method:
- Accepts a by-name parameter (lazy evaluation)
- Catches any exceptions during note generation
- Displays exception details in the report if note generation fails

### Exception Handling

TaskRunner automatically handles exceptions:

```scala
Step.Arbitrary("Risky operation"): (state, self) =>
  // If this throws an exception:
  val result = dangerousOperation()

  Step.Result(Some(0), "", "", state)
```

If an exception occurs:
- Exit code is set to `None`
- Stdout is empty
- Stderr contains the full stack trace
- Carryforward is unchanged (prior value is preserved)

### Conditional Execution

Use the carryforward state for conditional logic:

```scala
Step.Arbitrary("Conditional step"): (state, self) =>
  if state.shouldPerformBackup then
    performBackup()
    Step.Result(Some(0), "Backup performed", "", state.copy(backupDone = true))
  else
    Step.Result(Some(0), "Backup skipped", "", state)
```

### Working Directory and Environment

Each step can have its own working directory and environment:

```scala
Step.Exec(
  name = "List project files",
  parsedCommand = List("ls", "-la"),
  workingDirectory = os.pwd / "project",
  environment = sys.env ++ Map("CUSTOM_VAR" -> "value")
)
```

### Custom Success Criteria for Tasks

Override the default task success criterion:

```scala
val run = silentRun(myTask)

// Default uses Task.Run.usualSuccessCriterion
// Create custom criteria:
val customRun = run.copy(
  isSuccess = run => {
    // Custom logic
    run.sequential.exists(_.success)
  }
)
```

### Silent Runs

Get task run results without reporting:

```scala
val run: Task.Run = silentRun(myTask)

if run.success then
  println("Task succeeded!")
else
  println("Task failed!")

// Access detailed results
run.sequential.foreach {
  case completed: AnyStepRunCompleted =>
    println(s"${completed.step.name}: ${completed.result.exitCode}")
  case skipped: AnyStepRunSkipped =>
    println(s"${skipped.step.name}: SKIPPED")
}
```

---

## Best Practices

### 1. Choose the Right Carryforward Type

- Use `Unit` for simple tasks without state
- Use a case class for tasks that need to pass data between steps
- Keep the carryforward type immutable
- Consider implementing a custom `carryForwardDescriber` for better reports

### 2. Organize Steps by Category

**Setups for:**
- Checking prerequisites (disk space, required commands, connectivity)
- Logging initial state
- Creating temporary resources
- Non-critical preparation work

**Sequential for:**
- The main workflow of your task
- Steps that depend on each other
- Operations that must happen in order

**Followups for:**
- Cleanup (even on failure)
- Logging final state
- Sending notifications
- Removing temporary files

### 3. Use Essential Flag Wisely

- Make critical setup steps essential (e.g., creating a backup before destructive operations)
- Make critical cleanup steps essential (e.g., removing sensitive temporary files)
- Don't make logging or notification steps essential

### 4. Customize Error-handling

```scala
// Option 1: Catch error and continue (don't fail the task)
Step.Arbitrary("Risky operation"): (state, self) =>
  try
    val result = performOperation()
    Step.Result(Some(0), "Success", "", state.updated(result))
  catch
    case NonFatal(e) =>
      // Return 0 to indicate success despite the error
      Step.Result(Some(0), "", e.getMessage, state)

// Option 2: Custom success criteria to accept certain failures
Step.Arbitrary(
  name = "Flexible operation",
  isSuccess = run =>
    // Accept both success and specific failure cases
    run.result.exitCode.exists(code => code == 0 || code == 1)
): (state, self) =>
  try
    val result = performOperation()
    Step.Result(Some(0), "Success", "", state.updated(result))
  catch
    case NonFatal(e) =>
      Step.Result(Some(1), "", e.getMessage, state)

// Option 3: Make the step non-essential so failures don't affect overall task
Step.Arbitrary(
  name = "Optional operation",
  essential = Some(false) // Failure won't fail the overall task
): (state, self) =>
  try
    val result = performOperation()
    Step.Result(Some(0), "Success", "", state.updated(result))
  catch
    case NonFatal(e) =>
      Step.Result(Some(1), "", e.getMessage, state)
```

### 5. Add Meaningful Notes

```scala
.withNotes(
  s"""Operation completed:
     |  Files processed: ${state.fileCount}
     |  Total size: ${friendlyFileSize(state.totalSize)}
     |  Duration: ${state.duration}
   """.stripMargin
)
```

### 6. Use Descriptive Names

Good step names help when reading reports:

```scala
// Good
"Create temporary backup directory"
"Perform PostgreSQL dump of all databases"
"Upload backup to S3 bucket: backups-prod"

// Bad
"Step 1"
"Backup"
"Upload"
```

### 7. Test with Silent Runs

During development, use `silentRun` to test without sending emails:

```scala
val run = silentRun(myTask)
println(s"Success: ${run.success}")
run.sequential.foreach:
  case completed: AnyStepRunCompleted =>
    println(s"${completed.step.name}: ${completed.success}")
  case _ => ()
```

### 8. Parallelize Wisely

- Enable parallelization for independent I/O-bound operations
- Keep sequential operations that depend on each other in `sequential`
- Be cautious with shared resources
- Consider the number of available cores

### 9. Environment Variable Best Practices

```scala
// Use environment variables for configuration
val dbHost = sys.env.getOrElse("DB_HOST", "localhost")
val backupRetention = sys.env.get("BACKUP_RETENTION_DAYS").map(_.toInt).getOrElse(30)
```

---

## API Reference

### TaskRunner[T]

```scala
class TaskRunner[T](parallelize: Parallelize = Parallelize.Never)
```

**Methods:**
- `silentRun(task: Task): Task.Run` - Execute task without reporting
- `runAndReport(task: Task, reporters: Set[Reporter]): Unit` - Execute and report

**Type Members:**
- `type Carrier = (T, Int, String, String) => T` - Function to extract data from command output
- `trait Task` - Task definition
- `sealed trait Step` - Step definition
- `type Result = Step.Result` - Step execution result

### Task

```scala
trait Task:
  def name: String
  def init: T
  def bestEffortSetups: Set[Step]
  def sequential: List[Step]
  def bestEffortFollowups: Set[Step]
```

### Step.Exec

```scala
case class Exec(
  name: String,
  parsedCommand: List[String],
  workingDirectory: os.Path = os.pwd,
  environment: Map[String,String] = sys.env,
  carrier: Carrier = Carrier.carryPrior,
  isSuccess: Step.Run.Completed => Boolean = defaultIsSuccess,
  essential: Option[Boolean] = None
) extends Step
```

### Step.Arbitrary

```scala
// Case class
case class Arbitrary(
  name: String,
  action: (T, Step.Arbitrary) => Result,
  isSuccess: Step.Run.Completed => Boolean = defaultIsSuccess,
  workingDirectory: os.Path = os.pwd,
  environment: Map[String,String] = sys.env,
  actionDescription: Option[String] = None,
  essential: Option[Boolean] = None
) extends Step

// Companion object apply method
object Arbitrary:
  def apply(
    name: String,
    isSuccess: Step.Run.Completed => Boolean = defaultIsSuccess,
    workingDirectory: os.Path = os.pwd,
    environment: Map[String,String] = sys.env,
    actionDescription: Option[String] = None,
    essential: Option[Boolean] = None
  )(action: (T, Step.Arbitrary) => Result): Arbitrary
```

**Example:**

```scala
// Minimal - just name and action
Step.Arbitrary("Step name"): (state, self) =>
  // Action body
  val updatedState = doStuff( state )
  Step.Result.onward(updatedState)

// With optional parameters
Step.Arbitrary(
  name = "Step name",
  isSuccess = Step.defaultIsSuccess,
  essential = Some(true)
): (state, self) =>
  // Action body
  val updatedState = doStuff( state )
  Step.Result.onward(updatedState)
```

**Alternative `arbitrary()` helper method:**

The `arbitrary()` method provides the same functionality as `Step.Arbitrary()` but is available for those who prefer a shorter name. The explicit `Step.Arbitrary()` form is recommended for clarity.

### Step.Result

```scala
case class Result(
  exitCode: Option[Int],
  stepOut: String,
  stepErr: String,
  carryForward: T,
  notes: Option[String] = None,
  carryForwardDescriber: T => Option[String] = defaultCarryForwardDescriber
)
```

**Methods:**
- `withNotes(genNotes: => String): Result` - Add notes to result

**Companion Object:**
- `onward(carryForward: T, notes: Option[String] = None, carryForwardDescriber: T => Option[String] = defaultCarryForwardDescriber): Result` - **Recommended** for simple state updates. Creates a successful result with no exit code and empty output.
- `emptyWithCarryForward(t: T): Result` - Result with no exit code, empty output
- `zeroWithCarryForward(t: T): Result` - Result with exit code 0, empty output

**Usage:**
```scala
// Recommended for simple state updates
Step.Result.onward(newState)

// With notes
Step.Result.onward(newState, notes = Some("Operation completed successfully"))
```

### Parallelizable

```scala
enum Parallelizable:
  case Setups, Followups, Reporting
```

### Reporters

```scala
object Reporters:
  def stdOutOnly(formatter: AnyTaskRun => String = ...): Set[Reporter]
  def stdErrOnly(formatter: AnyTaskRun => String = ...): Set[Reporter]
  def smtpOnlyOrNone(...)(using Try[Smtp.Context]): Set[Reporter]
  def smtpOrFail(...)(using Try[Smtp.Context]): Set[Reporter]
  def smtpAndStdOut(...)(using Try[Smtp.Context]): Set[Reporter]
  def default(...)(using Try[Smtp.Context]): Set[Reporter]
```

### Type Aliases

These type aliases help work with TaskRunner instances across different carryforward types:

```scala
type AnyTaskRunner       = TaskRunner[?]
type AnyTask             = AnyTaskRunner#Task
type AnyTaskRun          = AnyTaskRunner#TaskType#Run
type AnyStep             = AnyTaskRunner#Step
type AnyStepRun          = AnyTaskRunner#StepType#Run
type AnyStepRunCompleted = AnyTaskRunner#StepType#RunType#Completed
type AnyStepRunSkipped   = AnyTaskRunner#StepType#RunType#Skipped

type Reporter = AnyTaskRun => Unit
```

**Key types explained:**

- **`AnyTaskRun`** - Complete task execution result containing all step runs and overall success status. Used as input to reporters.
- **`AnyStepRun`** - Result of executing a single step (either `Completed` or `Skipped`).
- **`AnyStepRunCompleted`** - A step that was executed, containing exit code, stdout, stderr, and carryforward.
- **`AnyStepRunSkipped`** - A step that was skipped due to prior failures.
- **`Reporter`** - Function type for generating reports from task runs.

---

## Additional Resources

- **Scaladoc**: https://javadoc.io/doc/com.mchange/mchange-sysadmin-scala_3
- **Example Scripts**: https://github.com/swaldman/mchange-sysadmin-scripts (see the `taskbin/` directory)
- **Source Code**: https://github.com/swaldman/mchange-sysadmin-scala
- **os-lib Documentation**: https://github.com/com-lihaoyi/os-lib

---

## Troubleshooting

### Email Reports Not Sending

1. Check environment variables are set:
   ```bash
   echo $SYSADMIN_MAIL_FROM
   echo $SYSADMIN_MAIL_TO
   ```

2. Verify SMTP is configured properly (check environment variables or properties file)

3. Use `smtpOrFail` to get explicit errors:
   ```scala
   val reporters: Set[Reporter] = Reporters.smtpOrFail() // throws if config is invalid
   ```

### Steps Being Skipped

- Check if an earlier essential step failed
- Verify `essential` flags are set correctly
- Use `silentRun` to inspect which step failed:
  ```scala
  val run = silentRun(myTask)
  run.sequential.zipWithIndex.foreach { case (stepRun, i) =>
    println(s"$i: ${stepRun.step.name} - ${stepRun.success}")
  }
  ```

### Carryforward Not Updating

- Ensure you're returning a new carryforward value in your steps
- Remember: best-effort steps (setups/followups) have their carryforward discarded
- For sequential steps, verify each step returns an updated carryforward

### Commands Not Found

- Ensure commands are in PATH or use absolute paths:
  ```scala
  Step.Exec("Run script", List("/usr/local/bin/myscript.sh"))
  ```
- Set environment variables if needed:
  ```scala
  environment = sys.env ++ Map("PATH" -> "/usr/local/bin:/usr/bin:/bin")
  ```

---

## FAQs

**1. Why do I have to `import taskRunner.*` after I create a `TaskRunner` instance?**

You can't just create a `TaskRunner.Step`, because it is a _dependent type_. Each _instance_ of `TaskRunner` has its own `Step` type, 
which cannot substitute or be substituted for by any other instance's `Step`. 

This adds some complexity. If we write

```scala
import com.mchange.sysadmin.taskrunner.*

// Create a TaskRunner parameterized with Unit (no state tracking)
val taskRunner = TaskRunner[Unit]

val myStep = Step.Exec("Run Twingle Script",List("twingle"))
```

The call to `Step.Exec` would fail. It would need to be

```scala
val myStep = taskRunner.Step.Exec("Run Twingle Script",List("twingle"))
```

which is longwinded.

A more concise, and recommended, approach is to just write `import taskRunner.*`

```scala
import com.mchange.sysadmin.taskrunner.*

// Create a TaskRunner parameterized with Unit (no state tracking)
val taskRunner = TaskRunner[Unit]
import taskRunner.*

val myStep = Step.Exec("Run Twingle Script",List("twingle"))
```


You should not be working with multiple `TaskRunner` instances in any single context,
and this gives you access to the full API without ceremony.

**2. Why does the library rely on dependent types?**

Dependent types add extra complexity to the library. 

Early version of the library just had top-level,
independent types including `Step`, `Step.Result`, `Step.Run`, `Step.Completed`, etc. 

But because all of these elements need to adhere to a common but parameterized type for
the carry forward state, everything had to be written in terms of 
`Step[T]`, `Step.Result[T]`, `Step.Run[T]`, `Step.Completed[T]` etc.

This got noisy, seemed inconvenient to work with.

The current, cleaner and simpler, API seemed woth accepting the oddness and complexity of dependent types.
`import taskRunner.*` is not that big a deal.

**3. Why do `Result` objects not include an `Option[Throwable]`, signalling that an `Exception` has occurred?**

A `Task` was conceived as kind of a structured, instrumented shell script. The library began with `Step.Exec`,
which literally just runs something and looks to the exit code for success or failure.

The library was then generalized to include `Step.Arbitrary(...)`, which just executes some Scala task, and which
has no natural integral "exit code" to signal success or failure.

But a Scala task also has no natural "standard error" output that must be captured. Nothing ever needs to be
written into that element of the result.

So rather than complicate `Result` objects, it seemed parsimonious to just let notional output to standard
error signify that something has gone awry in most cases. (You can define your own `isSuccess` function,
and override this. But it's rare that you would have to for a `Step.Arbitrary`.)

For now, an unhandled `Exception` just gets its stack trace written as "standard out", which by default gets interpreted
as failure, and the prior state is carried forward. 

In the future, there could be a user-defined function, perhaps `(T, Throwable) => T` or even `(T, Result) => Result`
to override this behavior, and customize how different exceptions are interpreted or affect execution.
But so far, we've never encountered a need for it.


---

**Version**: 0.2.0-SNAPSHOT

**Last Updated**: 2024-11-07

**License**: Apache 2.0
