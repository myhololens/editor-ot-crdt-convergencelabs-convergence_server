/*
 * Copyright (c) 2019 - Convergence Labs, Inc.
 *
 * This file is part of the Convergence Server, which is released under
 * the terms of the GNU General Public License version 3 (GPLv3). A copy
 * of the GPLv3 should have been provided along with this file, typically
 * located in the "LICENSE" file, which is part of this source code package.
 * Alternatively, see <https://www.gnu.org/licenses/gpl-3.0.html> for the
 * full text of the GPLv3 license, if it was not provided.
 */

import Dependencies.Compile._
import Dependencies.Test._

organization := "com.convergencelabs"
organizationName := "Convergence Labs, Inc."
organizationHomepage := Some(url("http://convergencelabs.com"))

name := "convergence-server"
description := "Convergence Server"
homepage := Some(url("https://convergence.io"))
maintainer := "info@convergencelabs.com"

licenses += "GPLv3" -> url("https://www.gnu.org/licenses/gpl-3.0.html")

scmInfo := Some(ScmInfo(
      url("https://github.com/convergencelabs/convergence-server"),
      "https://github.com/convergencelabs/convergence-server.git"))

scalaVersion := "2.12.10"

scalacOptions := Seq("-deprecation", "-feature")
fork := true
javaOptions += "-XX:MaxDirectMemorySize=16384m"

libraryDependencies ++=
  akkaCore ++
    orientDb ++
    loggingAll ++
    Seq(
      scalapb,
      convergenceProto,
      akkaHttp,
      json4s,
      jacksonYaml,
      json4sExt,
      akkaHttpJson4s,
      akkaHttpCors,
      commonsLang,
      jose4j,
      bouncyCastle,
      scrypt,
      netty,
      javaWebsockets,
      scallop,
      parboiled
    ) ++
    Seq(orientDbServer % "test") ++
    testingCore ++
    testingAkka

//
// SBT Native Packager Configs
//
mainClass in Compile := Some("com.convergencelabs.convergence.server.ConvergenceServer")
discoveredMainClasses in Compile := Seq()

bashScriptExtraDefines += """addApp "-c ${app_home}/../conf/convergence-server.conf""""
bashScriptExtraDefines += """addJava "-Dlog4j.configurationFile=${app_home}/../conf/log4j2.xml""""
batScriptExtraDefines += """call :add_app "-c %APP_HOME%\conf\convergence-server.conf""""
batScriptExtraDefines += """call :add_java "-Dlog4j.configurationFile=%APP_HOME%\conf\log4j2.xml""""

// Configure the binary distributions to be published along side the normal artifacts.
val packageZip = taskKey[File]("package-zip")
packageZip := (baseDirectory in Compile).value / "target" / "universal" / (name.value + "-" + version.value + ".zip")
artifact in (Universal, packageZip) ~= { (art: Artifact) => art.withType("zip").withExtension("zip") }
addArtifact(artifact in (Universal, packageZip), packageZip in Universal)

val packageTgz = taskKey[File]("package-zip")
packageTgz := (baseDirectory in Compile).value / "target" / "universal" / (name.value + "-" + version.value + ".tgz")
artifact in (Universal, packageTgz) ~= { (art: Artifact) => art.withType("tgz").withExtension("tgz") }
addArtifact(artifact in (Universal, packageTgz), packageTgz in Universal)

publish := (publish dependsOn (packageBin in Universal)).value

//
// SBT Build Info
//
buildInfoKeys := Seq[BuildInfoKey](name, version, scalaVersion, sbtVersion)
buildInfoPackage := "com.convergencelabs.convergence.server"

enablePlugins(JavaAppPackaging, UniversalDeployPlugin)
enablePlugins(BuildInfoPlugin)
enablePlugins(OrientDBPlugin)
