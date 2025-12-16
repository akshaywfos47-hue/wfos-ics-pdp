import sbt._

object Dependencies {

  val loggingDependencies = Seq(
    "org.slf4j" % "slf4j-api" % "1.7.36",
    "ch.qos.logback" % "logback-classic" % "1.2.11"
  )
  val pekkoDependencies = Seq(
    "org.apache.pekko" %% "pekko-actor" % "1.0.2",
    "org.apache.pekko" %% "pekko-stream" % "1.0.2",
    "org.apache.pekko" %% "pekko-slf4j" % "1.0.2"
  )


  val bgrxassembly = Seq(
    CSW.`csw-framework`,
    CSW.`csw-testkit` % Test,
    Libs.`scalatest` % Test,
    Libs.`junit-4-13` % Test
  ) ++ loggingDependencies ++ loggingDependencies

  val lgripHcd = Seq(
    CSW.`csw-framework`,
    CSW.`csw-testkit` % Test,
    Libs.`scalatest` % Test,
    Libs.`junit-4-13` % Test
  ) ++ loggingDependencies++ pekkoDependencies

  val rgripHcd = Seq(
    CSW.`csw-framework`,
    CSW.`csw-testkit` % Test,
    Libs.`scalatest` % Test,
    Libs.`junit-4-13` % Test
  ) ++ loggingDependencies ++ pekkoDependencies

  val lgmhcd = Seq(
    CSW.`csw-framework`,
    CSW.`csw-testkit` % Test,
    Libs.`scalatest` % Test,
    Libs.`junit-4-13` % Test
  ) ++ loggingDependencies ++ pekkoDependencies

  val pacthcd = Seq(
    CSW.`csw-framework`,
    CSW.`csw-testkit` % Test,
    Libs.`scalatest` % Test,
    Libs.`junit-4-13` % Test
  ) ++ loggingDependencies ++ pekkoDependencies

  val WfosIcsDeploy = Seq(
    CSW.`csw-framework`,
    CSW.`csw-testkit` % Test
  ) ++ loggingDependencies ++ pekkoDependencies
}
