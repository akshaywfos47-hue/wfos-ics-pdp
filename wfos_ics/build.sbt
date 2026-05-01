// Define aggregated projects
lazy val aggregatedProjects: Seq[ProjectReference] = Seq(
  `wfos-bgrxassembly`,
  `wfos-bgrx-lgripHcd`,
  `wfos-bgrx-rgripHcd`,
  `wfos-bgrx-lgmhcd`,
  `wfos-bgrx-pacthcd`,
  `wfos-wfos-icsdeploy`
)

// Root project
lazy val `wfos-ics-root` = project
  .in(file("."))
  .aggregate(aggregatedProjects: _*)

// Assembly module
lazy val `wfos-bgrxassembly` = project
  .dependsOn(
    `wfos-bgrx-lgripHcd`,
    `wfos-bgrx-rgripHcd`,
    `wfos-bgrx-lgmhcd`,
    `wfos-bgrx-pacthcd`
  )
  .settings(
    libraryDependencies ++= Dependencies.bgrxassembly ++ Seq(
      "com.github.fge" % "uri-template" % "0.9"
    )
  )

// HCD module - Left Gripper
lazy val `wfos-bgrx-lgripHcd` = project
  .settings(
    libraryDependencies ++= Dependencies.lgripHcd ++ Seq(
      "com.github.tmtsoftware.csw" %% "csw-config-client" % CSW.Version,
      "com.github.tmtsoftware.csw" %% "csw-location-client" % CSW.Version,
      "com.github.tmtsoftware.csw" %% "csw-config-api" % CSW.Version
    )
  )

// HCD module - Right Gripper
lazy val `wfos-bgrx-rgripHcd` = project
  .settings(
    libraryDependencies ++= Dependencies.rgripHcd
  )

// HCD module - LGM
lazy val `wfos-bgrx-lgmhcd` = project
  .settings(
    libraryDependencies ++= Dependencies.lgmhcd
  )

// HCD module - PACT
lazy val `wfos-bgrx-pacthcd` = project
  .settings(
    libraryDependencies ++= Dependencies.pacthcd
  )

// Deployment module - single merged definition
lazy val `wfos-wfos-icsdeploy` = project
  .in(file("wfos-wfos-icsdeploy"))
  .enablePlugins(JavaAppPackaging, CswBuildInfo)
  .dependsOn(
    `wfos-bgrxassembly`,
    `wfos-bgrx-lgripHcd`,
    `wfos-bgrx-rgripHcd`,
    `wfos-bgrx-lgmhcd`,
    `wfos-bgrx-pacthcd`
  )
  .settings(
    libraryDependencies ++= Dependencies.WfosIcsDeploy
  )

// Global settings
ThisBuild / libraryDependencies ++= Seq(
  "com.github.fge" % "uri-template" % "0.9"
)
ThisBuild / scalaVersion := Libs.ScalaVersion
ThisBuild / scalafmtOnCompile := true

// Run test suites sequentially to avoid port 7654 (CSW location service) conflicts
Global / concurrentRestrictions += Tags.limit(Tags.Test, 1)