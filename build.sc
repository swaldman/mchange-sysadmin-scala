import $meta._

import mill._
import mill.scalalib._
import mill.scalalib.publish._

import $ivy.`com.mchange::untemplate-mill:0.1.0`
import untemplate.mill._

object sysadmin extends RootModule with UntemplateModule with PublishModule {

  val JakartaMailVersion = "2.0.1"

  override def scalaVersion = "3.3.1"

//  def scalacOptions = T {
//    super.scalacOptions() ++ Seq("-explain")
//  }

  override def artifactName = "mchange-sysadmin-scala"
  override def publishVersion = T{"0.1.0"}
  override def pomSettings    = T{
    PomSettings(
      description = "A library of utilities for sysadmin scripts",
      organization = "com.mchange",
      url = "https://github.com/swaldman/mchange-sysadmin-scala",
      licenses = Seq(License.`Apache-2.0`),
      versionControl = VersionControl.github("swaldman", "mchange-sysadmin-scala"),
      developers = Seq(
	      Developer("swaldman", "Steve Waldman", "https://github.com/swaldman")
      )
    )
  }
  
  override def ivyDeps = T{
    super.ivyDeps() ++ Agg(
      ivy"com.mchange::codegenutil:0.0.2",
      ivy"com.lihaoyi::os-lib:0.9.1",
      ivy"com.lihaoyi::pprint:0.8.1",
      ivy"org.apache.commons:commons-text:1.10.0",
      ivy"org.jsoup:jsoup:1.16.1", // just to pretty-print HTML for now
      ivy"com.sun.mail:jakarta.mail:${JakartaMailVersion}",
      ivy"com.sun.mail:smtp:${JakartaMailVersion}",
    )
  }

  // we'll build an index!
  override def untemplateIndexNameFullyQualified : Option[String] = Some("com.mchange.sysadmin.IndexedUntemplates")

  override def untemplateSelectCustomizer: untemplate.Customizer.Selector = { key =>
    var out = untemplate.Customizer.empty

    // to customize, examine key and modify the customer
    // with out = out.copy=...
    //
    // e.g. out = out.copy(extraImports=Seq("commchangesysadmin.*"))

    out
  }
}


