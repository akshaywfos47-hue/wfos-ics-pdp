// Define aggregated projects
ThisBuild / scalaVersion := Libs.ScalaVersion
ThisBuild / scalafmtOnCompile := true

lazy val aggregatedProjects: Seq[ProjectReference] = Seq(
  `wfos-bgrxassembly`,
  `wfos-bgrx-lgripHcd`,
  `wfos-bgrx-rgripHcd`,
  `wfos-bgrx-lgmhcd`,
  `wfos-bgrx-pacthcd`,
  `wfos-wfos-icsdeploy`
)

// Root project aggregating all subprojects
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
      "com.github.tmtsoftware.csw" %% "csw-config-client" % "6.0.0",
      "com.github.tmtsoftware.csw" %% "csw-location-client" % "6.0.0",
      "com.github.tmtsoftware.csw" %% "csw-config-api" % "6.0.0"
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

  // / HCD module - LGM
lazy val `wfos-bgrx-pacthcd` = project
  .settings(
    libraryDependencies ++= Dependencies.pacthcd
  )

// Deployment module
lazy val `wfos-wfos-icsdeploy` = project
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

// Global dependencies (if required by all modules)
ThisBuild / libraryDependencies ++= Seq(
  "com.github.fge" % "uri-template" % "0.9"
)
