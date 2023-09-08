# mchange-sysadmin-scala

Some tools for doing sysadmin scripting in Scala

[![scaladoc](https://javadoc.io/badge2/com.mchange/mchange-sysadmin-scala_3/scaladoc.svg)](https://javadoc.io/doc/com.mchange/mchange-sysadmin-scala_3)

### Cautious tasks with reports

The main utility here is [`TaskRunner`](src/main/scala/com/mchange/sysadmin/TaskRunner.scala).

The idea is you

* Define a "carryforward" or payload type to track state through the task.
  (You can use `Unit` if you don't need to track state)
* Define a sequence of steps that accept and pass forward your carryforward.
  Each step will be evaluated, and its exit code, out, err, etc. will be 
  included in reports.

  If all steps succeed, the overall task succeeds. If any step fails, later steps
  are skipped and the overall task fails
* Define a list of "best-attempt cleanups", each attempted exactly once
  regardless of whether the main sequence or other clean-up steps have succeeded
  or failed. The cleanup steps are given the carryforward produced by the last
  unskipped task.

When you run a task, you supply a list "reporters", which generated and send
reports about the run. SMTP, stdout, and stderr reporters are defined, though
of course you could define your own.

For example, [here](https://github.com/swaldman/mchange-sysadmin-scripts/blob/main/taskbin/renew-certs)
is a very simple task to renew letsencrypt certificates. The payload type is just `Unit`.

Here is a more complicated example that backs up a database and uploads the
results to an [rclone](https://rclone.org/) destination.

The task is defined
within an abstract class, so it can be [trivially](https://github.com/swaldman/mchange-sysadmin-scripts/blob/main/taskbin/backup-postgres)
[specialized](https://github.com/swaldman/mchange-sysadmin-scripts/blob/main/taskbin/backup-mysql) for
multiple databases.

A bespoke case class `Pad` tracks the necessary state between steps. It retains
the temporary backup destination file, which is deleted in a cleanup step after 
the backup is uploaded or copied to its final destination.

#### Example e-mailed report

Prettier HTML-mail coming soon, I hope! But here's what (default) reports
currently look like:

```plaintext
=====================================================================
[tickle5]: Backup mysql, all databases -- SUCCEEDED
=====================================================================
Timestamp: 2023-09-04T19:44:41Z
Succeeded overall? Yes

SEQUENTIAL:
---------------------------------------------------------------------
1. Ensure availability of rclone, if necessary
---------------------------------------------------------------------
Action: <internal function>
Succeeded? Yes
Exit code: 0

out:
    rclone v1.57.0-DEV
    - os/version: centos 8 (64 bit)
    - os/kernel: 4.18.0-512.el8.x86_64 (x86_64)
    - os/type: linux
    - os/arch: amd64
    - go/version: go1.16.12
    - go/linking: dynamic
    - go/tags: none

err:
    <EMPTY>

notes:
    destpath: onedrive:cloud-backups/mysql

carryforward:
    Pad(tmpDir = None, backupFile = None)

---------------------------------------------------------------------
2. Create Temp Dir
---------------------------------------------------------------------
Action: <internal function>
Succeeded? Yes

out:
    <EMPTY>

err:
    <EMPTY>

carryforward:
    Pad(tmpDir = Some(value = /tmp/17588242228680154941), backupFile = None)

---------------------------------------------------------------------
3. Perform mysql Backup
---------------------------------------------------------------------
Action: <internal function>
Succeeded? Yes
Exit code: 0

out:
    <EMPTY>

err:
    <EMPTY>

notes:
    Backup size: 128.062 MiB

carryforward:
    Pad(
      tmpDir = Some(value = /tmp/17588242228680154941),
      backupFile = Some(value = /tmp/17588242228680154941/tickle5-mysql-dumpall-2023-09-04)
    )

---------------------------------------------------------------------
4. Copy backup to storage
---------------------------------------------------------------------
Action: <internal function>
Succeeded? Yes
Exit code: 0

out:
    <EMPTY>

err:
    <EMPTY>

carryforward:
    Pad(
      tmpDir = Some(value = /tmp/17588242228680154941),
      backupFile = Some(value = /tmp/17588242228680154941/tickle5-mysql-dumpall-2023-09-04)
    )

-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-

BEST-ATTEMPT CLEANUPS:
---------------------------------------------------------------------
Remove temporary local backup.
---------------------------------------------------------------------
Action: <internal function>
Succeeded? Yes

out:
    <EMPTY>

err:
    <EMPTY>

carryforward:
    Pad(
      tmpDir = Some(value = /tmp/17588242228680154941),
      backupFile = Some(value = /tmp/17588242228680154941/tickle5-mysql-dumpall-2023-09-04)
    )

=====================================================================
.   .   .   .   .   .   .   .   .   .   .   .   .   .   .   .   .   .
```

## Developer Docs

Dependency Javadocs:
* https://javadoc.io/doc/com.lihaoyi/pprint_3/latest/index.html
* https://javadoc.io/doc/com.lihaoyi/fansi_3/latest/index.html

Dependency References:
* https://com-lihaoyi.github.io/PPrint/
* https://www.lihaoyi.com/post/MicrooptimizingyourScalacode.html
