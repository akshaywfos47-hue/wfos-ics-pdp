import java.io.FileReader
import java.util.Properties

import sbt._

import scala.util.control.NonFatal

object Libs {
  val ScalaVersion: String = {
    var reader: FileReader = null
    try {
      val properties = new Properties()
      reader = new FileReader("project/build.properties")
      properties.load(reader)
      val version = properties.getProperty("scala.project.version", "3.6.4")
      println(s"[info] Using Scala version [$version]")
      version
    }
    catch {
      case NonFatal(e) =>
        println("[warn] Failed to read scala.project.version, falling back to 3.6.4")
        "3.6.4"
    }
    finally {
      if (reader != null) reader.close()
    }
  }

  val `scalatest`   = "org.scalatest"          %% "scalatest"   % "3.2.11"    //Apache License 2.0
  val `dotty-cps-async` = "com.github.rssh" %% "dotty-cps-async" % "0.9.23" //BSD 3-clause "New" or "Revised" License
  val `junit-4-13`  = "org.scalatestplus"      %% "junit-4-13"  % "3.2.10.0"
  val `mockito`     = "org.scalatestplus"      %% "mockito-3-4" % "3.2.10.0"
}

object CSW {

  // If you want to change CSW version, then update "csw.version" property in "build.properties" file
  // Same "csw.version" property should be used while running the "csw-services",
  // this makes sure that CSW library dependency and CSW services version is in sync
  val Version: String = {
    var reader: FileReader = null
    try {
      val properties = new Properties()
      reader = new FileReader("project/build.properties")
      properties.load(reader)
      val version = properties.getProperty("csw.version")
      println(s"[info]] Using CSW version [$version] ***********")
      version
    }
    catch {
      case NonFatal(e) =>
        e.printStackTrace()
        throw e
    }
    finally reader.close()
  }

  val `csw-framework` = "com.github.tmtsoftware.csw" %% "csw-framework" % Version
  val `csw-testkit`   = "com.github.tmtsoftware.csw" %% "csw-testkit"   % Version
}
