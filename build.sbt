name := "slicebox"
version := "1.3-SNAPSHOT"
organization := "se.nimsa"
scalaVersion := "2.12.1"
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

organizationName := "Lars Edenbrandt"
startYear := Some(2014)
licenses += ("Apache-2.0", new URL("https://www.apache.org/licenses/LICENSE-2.0.txt"))
excludeFilter.in(unmanagedSources.in(headerCreate)) := HiddenFileFilter || "*.html" || "*.js" || "*.css"

// repos

resolvers ++= Seq(
  "Typesafe Repository" at "http://repo.typesafe.com/typesafe/releases/",
  "Sonatype snapshots" at "http://oss.sonatype.org/content/repositories/snapshots/",
  "dcm4che Repository" at "http://www.dcm4che.org/maven2/",
  Resolver.bintrayRepo("hseeberger", "maven"))

// deps

libraryDependencies ++= {
  val akkaVersion = "2.4.17"
  val akkaHttpVersion = "10.0.6"
  val slickVersion = "3.2.0"
  val dcm4cheVersion = "3.3.8"
  Seq(
    "com.typesafe.scala-logging" %% "scala-logging" % "3.5.0",
    "com.typesafe.akka" %% "akka-slf4j" % akkaVersion,
    "com.typesafe.akka" %% "akka-http" % akkaHttpVersion,
    "de.heikoseeberger" %% "akka-http-play-json" % "1.15.0",
    "ch.qos.logback" % "logback-classic" % "1.2.3",
    "com.typesafe.slick" %% "slick" % slickVersion,
    "com.typesafe.slick" %% "slick-hikaricp" % slickVersion,
    "com.h2database" % "h2" % "1.4.195",
    "mysql" % "mysql-connector-java" % "6.0.6",
    "com.zaxxer" % "HikariCP" % "2.6.1",
    "com.github.t3hnar" %% "scala-bcrypt" % "3.0",
    "com.amazonaws" % "aws-java-sdk-s3" % "1.11.126",
    "org.scalatest" %% "scalatest" % "3.0.3" % "test",
    "org.dcm4che" % "dcm4che-core" % dcm4cheVersion,
    "org.dcm4che" % "dcm4che-image" % dcm4cheVersion,
    "org.dcm4che" % "dcm4che-imageio" % dcm4cheVersion,
    "org.dcm4che" % "dcm4che-net" % dcm4cheVersion,
    "com.typesafe.akka" %% "akka-testkit" % akkaVersion % "test",
    "com.typesafe.akka" %% "akka-http-testkit" % akkaHttpVersion % "test",
    "org.webjars" % "angularjs" % "1.5.9",
    "org.webjars" % "angular-material" % "1.1.4",
    "org.webjars" % "angular-file-upload" % "11.0.0"
  )
}

dependencyOverrides += "com.typesafe.akka" %% "akka-http" % "10.0.0" // akka-http-play-json wants akka-http 3.0.0-RC1

// run tests in separate JVMs

fork in Test := true

// turn on cached resolution in SBT

updateOptions := updateOptions.value.withCachedResolution(true)

// make sure files in the public folder are included in build

WebKeys.packagePrefix in Assets := "public/"
(managedClasspath in Runtime) += (packageBin in Assets).value
