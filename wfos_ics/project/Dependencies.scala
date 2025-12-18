import sbt._

object Dependencies {

  val pekkoDependencies = Seq(
    "org.apache.pekko" %% "pekko-actor" % "1.1.3",
    "org.apache.pekko" %% "pekko-actor-typed" % "1.1.3",
    "org.apache.pekko" %% "pekko-cluster" % "1.1.3",
    "org.apache.pekko" %% "pekko-cluster-typed" % "1.1.3",
    "org.apache.pekko" %% "pekko-cluster-tools" % "1.1.3",
    "org.apache.pekko" %% "pekko-distributed-data" % "1.1.3",
    "org.apache.pekko" %% "pekko-coordination" % "1.1.3",
    "org.apache.pekko" %% "pekko-serialization-jackson" % "1.1.3"
  )

  val loggingDependencies = Seq(
  "org.slf4j" % "slf4j-api" % "2.0.13",
  "ch.qos.logback" % "logback-classic" % "1.5.6"
  )

  val bgrxassembly = Seq(
    CSW.`csw-framework`,
    CSW.`csw-testkit` % Test,
    Libs.`scalatest` % Test,
    Libs.`junit-4-13` % Test
  ) ++ loggingDependencies ++ pekkoDependencies

  val lgripHcd = Seq(
    CSW.`csw-framework`,
    CSW.`csw-testkit` % Test,
    Libs.`scalatest` % Test,
    Libs.`junit-4-13` % Test
  ) ++ loggingDependencies ++ pekkoDependencies

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
