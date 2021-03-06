import sbt.Keys.scalaVersion

name := "xyniac-dynamic-config"

resolvers ++= Seq(
  "bintray-sbt-plugin-releases" at "http://dl.bintray.com/content/sbt/sbt-plugin-releases",
  "Typesafe" at "http://repo.typesafe.com/typesafe/releases/",
  "Maven" at "https://repo1.maven.org/maven2/",
)


lazy val commonSettings = Seq(
  organization := "com.xyniac",
  version := "0.1.1-SNAPSHOT",
  scalaVersion := "2.13.0"
)

lazy val app = (project in file(".")).
  settings(commonSettings: _*).
  enablePlugins()


libraryDependencies += "org.json4s" %% "json4s-native" % "3.6.7"
libraryDependencies += "org.slf4j" % "slf4j-api" % "1.7.30"
libraryDependencies += "com.google.code.gson" % "gson" % "2.8.6"
libraryDependencies += "org.scala-lang.modules" %% "scala-parallel-collections" % "0.2.0"
libraryDependencies += "org.testng" % "testng" % "7.1.0" % Test
libraryDependencies += "org.scalatest" %% "scalatest" % "3.1.1" % Test
libraryDependencies += "org.slf4j" % "slf4j-log4j12" % "1.7.30" % Test
libraryDependencies += "com.github.marschall" % "memoryfilesystem" % "2.1.0" % Test
libraryDependencies += "org.scala-lang" % "scala-reflect" % "2.13.0" % Test
libraryDependencies += "org.scala-lang" % "scala-compiler" % "2.13.0" % Test

retrieveManaged := true
updateOptions := updateOptions.value.withCachedResolution(true)
organization := "com.xyniac"
homepage := Some(url("https://github.com/xiningli/xyniac-dynamic-config"))
scmInfo := Some(ScmInfo(url("https://github.com/username/projectname"), "git@github.com:username/projectname.git"))
developers := List(Developer("xiningli", "Xining Li", "xininglica@gmail.com", url("https://github.com/xiningli")))
licenses += ("Apache-2.0", url("http://www.apache.org/licenses/LICENSE-2.0"))
publishMavenStyle := true

// Add sonatype repository settings
publishTo := Some(
  if (isSnapshot.value)
    Opts.resolver.sonatypeSnapshots
  else
    Opts.resolver.sonatypeStaging
)