ThisBuild / publishTo := {
    if (isSnapshot.value) Some(Resolver.url("sonatype-snapshots", url("https://oss.sonatype.org/content/repositories/snapshots")))
    else Some(Resolver.url("sonatype-staging", url("https://oss.sonatype.org/service/local/staging/deploy/maven2")))
}

ThisBuild / organization := "com.mchange"
ThisBuild / version      := "0.0.5"

lazy val root = project
  .in(file("."))
  .settings (
    name                     := "mchange-sysadmin-scala",
    scalaVersion             := "3.3.0",
//    scalacOptions            += "-explain",
    resolvers                += Resolver.mavenLocal,
    libraryDependencies      += "com.mchange" %% "codegenutil" % "0.0.2",
    libraryDependencies      += "com.lihaoyi" %% "os-lib"      % "0.9.1",
    libraryDependencies      += "com.lihaoyi" %% "pprint"      % "0.8.1",
    libraryDependencies      += "com.sun.mail" % "javax.mail"  % "1.6.2",
    libraryDependencies      += "com.sun.mail" % "smtp"        % "1.6.2",
    pomExtra                 := pomExtraForProjectName_Apache2( name.value ),
  )

def pomExtraForProjectName_Apache2( projectName : String ) = {
    <url>https://github.com/swaldman/{projectName}</url>
      <licenses>
          <license>
              <name>The Apache Software License, Version 2.0</name>
              <url>http://www.apache.org/licenses/LICENSE-2.0.txt</url>
              <distribution>repo</distribution>
          </license>
      </licenses>
      <scm>
          <url>https://github.com/swaldman/{projectName}</url>
          <connection>scm:git:git@github.com:swaldman/{projectName}.git</connection>
      </scm>
      <developers>
          <developer>
              <id>swaldman</id>
              <name>Steve Waldman</name>
              <email>swaldman@mchange.com</email>
          </developer>
      </developers>
}




