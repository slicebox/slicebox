import de.heikoseeberger.sbtheader.license.Apache2_0

    name := "slicebox"
    version := "1.1"
    organization := "se.nimsa"
    scalaVersion := "2.11.8"
    scalacOptions := Seq("-encoding", "UTF-8", "-Xlint","-deprecation", "-unchecked", "-feature", "-target:jvm-1.8")

    // define the project

    lazy val slicebox = (project in file(".")).enablePlugins(SbtWeb, JavaServerAppPackaging, GitBranchPrompt)
    mainClass in Compile := Some("se.nimsa.sbx.app.Main")

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
    batScriptExtraDefines += """set _JAVA_OPTS=%_JAVA_OPTS% -Xmx1024m -Dconfig.file=%SLICEBOX_HOME%\\conf\\slicebox.conf -Dlogback.configurationFile=%SLICEBOX_HOME%\\conf\\logback.xml"""

    // native packaging - linux

    daemonUser in Linux := normalizedName.value // user which will execute the application
    daemonGroup in Linux := (daemonUser in Linux).value // group which will execute the application
    bashScriptExtraDefines ++= Seq(
        """addJava "-Dconfig.file=${app_home}/../conf/slicebox.conf" """,
        """addJava "-Dlogback.configurationFile=${app_home}/../conf/logback.xml" """)

    // rpm specific

    rpmVendor := maintainer.value
    rpmLicense := Some("Apache v2")
    packageArchitecture in Rpm := "noarch"
    rpmGroup := Some("Applications/Research")
    version in Rpm := version.value.replace("-SNAPSHOT", "")
    rpmRelease := {if (version.value.matches(".*-SNAPSHOT")) System.currentTimeMillis().toString else "1"}

    // for automatic license stub generation

    val licenceYear = "2016"
    val licencedTo = "Lars Edenbrandt"
    headers := Map(
        "scala" -> Apache2_0(licenceYear, licencedTo),
        "conf" -> Apache2_0(licenceYear, licencedTo, "#"))

    // repos

    resolvers ++= Seq(
        "Typesafe Repository"   at "http://repo.typesafe.com/typesafe/releases/",
        "Sonatype snapshots"    at "http://oss.sonatype.org/content/repositories/snapshots/",
        "Spray Repository"      at "http://repo.spray.io",
        "Spray Nightlies"       at "http://nightlies.spray.io/")

    // deps

    libraryDependencies ++= {
        val akkaVersion     = "2.3.9"
        val sprayVersion    = "1.3.3"
        Seq(
            "com.typesafe.scala-logging"    %% "scala-logging"          % "3.4.0",
            "com.typesafe.akka"             %% "akka-actor"             % akkaVersion,
            "io.spray"                      %% "spray-can"              % sprayVersion,
            "io.spray"                      %% "spray-routing"          % sprayVersion,
            "io.spray"                      %% "spray-client"           % sprayVersion,
            "io.spray"                      %% "spray-json"             % "1.3.2",
            "com.typesafe.akka"             %% "akka-slf4j"             % akkaVersion,
            "ch.qos.logback"                 % "logback-classic"        % "1.1.7",
            "com.typesafe.slick"            %% "slick"                  % "2.1.0",
            "com.h2database"                 % "h2"                     % "1.4.191",
            "mysql"                          % "mysql-connector-java"   %  "6.0.2",
            "com.zaxxer"                     % "HikariCP"               % "2.4.6",
            "com.github.t3hnar"             %% "scala-bcrypt"           % "2.6",
            "com.amazonaws"                  % "aws-java-sdk-s3"        % "1.11.0",
            "org.scalatest"                 %% "scalatest"              % "2.2.5"           % "test",
            "io.spray"                      %% "spray-testkit"          % sprayVersion      % "test",
            "com.typesafe.akka"             %% "akka-testkit"           % akkaVersion       % "test",
            "org.webjars"                    % "angularjs"              % "1.5.5",
            "org.webjars"                    % "angular-material"       % "1.0.7",
            "org.webjars"                    % "angular-file-upload"    % "11.0.0"
          )
    }

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
