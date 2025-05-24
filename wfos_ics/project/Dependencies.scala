import sbt._

object Dependencies {

  val loggingDependencies = Seq(
    "org.slf4j" % "slf4j-api" % "1.7.36",
    "ch.qos.logback" % "logback-classic" % "1.2.11"
  )

  val bgrxassembly = Seq(
    CSW.`csw-framework`,
    CSW.`csw-testkit` % Test,
    Libs.`scalatest` % Test,
    Libs.`junit-4-13` % Test
  ) ++ loggingDependencies

  val lgripHcd = Seq(
    CSW.`csw-framework`,
    CSW.`csw-testkit` % Test,
    Libs.`scalatest` % Test,
    Libs.`junit-4-13` % Test
  ) ++ loggingDependencies

  val rgripHcd = Seq(
    CSW.`csw-framework`,
    CSW.`csw-testkit` % Test,
    Libs.`scalatest` % Test,
    Libs.`junit-4-13` % Test
  ) ++ loggingDependencies

  val lgmhcd = Seq(
    CSW.`csw-framework`,
    CSW.`csw-testkit` % Test,
    Libs.`scalatest` % Test,
    Libs.`junit-4-13` % Test
  ) ++ loggingDependencies

  val pacthcd = Seq(
    CSW.`csw-framework`,
    CSW.`csw-testkit` % Test,
    Libs.`scalatest` % Test,
    Libs.`junit-4-13` % Test
  ) ++ loggingDependencies

  val WfosIcsDeploy = Seq(
    CSW.`csw-framework`,
    CSW.`csw-testkit` % Test
  ) ++ loggingDependencies
}
