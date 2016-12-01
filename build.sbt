import de.heikoseeberger.sbtheader.license.Apache2_0

name := "slicebox"
version := "1.3-SNAPSHOT"
organization := "se.nimsa"
scalaVersion := "2.11.8"
scalacOptions := Seq("-encoding", "UTF-8", "-Xlint", "-deprecation", "-unchecked", "-feature", "-target:jvm-1.8")

// define the project

lazy val slicebox = (project in file(".")).enablePlugins(SbtWeb, JavaServerAppPackaging, GitBranchPrompt)
mainClass in Compile := Some("se.nimsa.sbx.app.Slicebox")

// for sbt-resolver, (the re-start and re-stop commands)

Revolver.settings

// native packaging

maintainer := "nimsa.se"
packageSummary := "Slicebox DICOM sharing service"
packageDescription := "Slicebox is a service for sharing medical image data with collaborators while protecting patient information"

// native packaging - universal

mappings in Universal += {
  val conf = (resourceDirectory in Compile).value / "slicebox.conf"
  conf -> "conf/slicebox.conf"
}
mappings in Universal += {
  val conf = (resourceDirectory in Compile).value / "logback.xml"
  conf -> "conf/logback.xml"
}
batScriptExtraDefines += """set _JAVA_OPTS=%_JAVA_OPTS% -Xmx2G -Dconfig.file="%SLICEBOX_HOME%\\conf\\slicebox.conf" -Dlogback.configurationFile="%SLICEBOX_HOME%\\conf\\logback.xml" """

// native packaging - linux

daemonUser in Linux := normalizedName.value // user which will execute the application
daemonGroup in Linux := (daemonUser in Linux).value // group which will execute the application
bashScriptExtraDefines ++= Seq(
  """addJava "-Xmx2G" """,
  """addJava "-Dconfig.file=${app_home}/../conf/slicebox.conf" """,
  """addJava "-Dlogback.configurationFile=${app_home}/../conf/logback.xml" """)

// rpm specific

rpmVendor := maintainer.value
rpmLicense := Some("Apache v2")
packageArchitecture in Rpm := "noarch"
rpmGroup := Some("Applications/Research")
version in Rpm := version.value.replace("-SNAPSHOT", "")
rpmRelease := {
  if (version.value.matches(".*-SNAPSHOT")) System.currentTimeMillis().toString else "1"
}

// for automatic license stub generation

val licenceYear = "2016"
val licencedTo = "Lars Edenbrandt"
headers := Map(
  "scala" -> Apache2_0(licenceYear, licencedTo),
  "conf" -> Apache2_0(licenceYear, licencedTo, "#"))

// repos

resolvers ++= Seq(
  "Typesafe Repository" at "http://repo.typesafe.com/typesafe/releases/",
  "Sonatype snapshots" at "http://oss.sonatype.org/content/repositories/snapshots/",
  Resolver.bintrayRepo("hseeberger", "maven"))

// deps

libraryDependencies ++= {
  val akkaVersion = "2.4.12"
  val akkaHttpVersion = "10.0.0"
  Seq(
    "com.typesafe.scala-logging" %% "scala-logging" % "3.5.0",
    "com.typesafe.akka" %% "akka-slf4j" % akkaVersion,
    "com.typesafe.akka" %% "akka-http" % akkaHttpVersion,
    "de.heikoseeberger" %% "akka-http-play-json" % "1.10.1",
    "ch.qos.logback" % "logback-classic" % "1.1.7",
    "com.typesafe.slick" %% "slick" % "2.1.0",
    "com.h2database" % "h2" % "1.4.193",
    "mysql" % "mysql-connector-java" % "5.1.40",
    "com.zaxxer" % "HikariCP" % "2.5.1",
    "com.github.t3hnar" %% "scala-bcrypt" % "3.0",
    "com.amazonaws" % "aws-java-sdk-s3" % "1.11.52",
    "org.scalatest" %% "scalatest" % "3.0.1" % "test",
    "com.typesafe.akka" %% "akka-testkit" % akkaVersion % "test",
    "com.typesafe.akka" %% "akka-http-testkit" % akkaHttpVersion % "test",
    "org.webjars" % "angularjs" % "1.5.8",
    "org.webjars" % "angular-material" % "1.1.1",
    "org.webjars" % "angular-file-upload" % "11.0.0"
  )
}

dependencyOverrides += "com.typesafe.akka" %% "akka-http" % "10.0.0" // akka-http-play-json wants akka-http 3.0.0-RC1

// run tests in separate JVMs

fork in Test := true

// eclipse IDE settings

EclipseKeys.createSrc := EclipseCreateSrc.Default
EclipseKeys.withSource := true

// turn on cached resolution in SBT

updateOptions := updateOptions.value.withCachedResolution(true)

// make sure files in the public folder are included in build

WebKeys.packagePrefix in Assets := "public/"
(managedClasspath in Runtime) += (packageBin in Assets).value
