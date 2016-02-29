import de.heikoseeberger.sbtheader.license.Apache2_0

	name := "slicebox"

	version := "0.13-SNAPSHOT"

	organization := "se.nimsa"

	scalaVersion := "2.11.6"

	scalacOptions := Seq("-encoding", "UTF-8", "-Xlint","-deprecation", "-unchecked", "-feature", "-target:jvm-1.8")

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

	batScriptExtraDefines += """set _JAVA_OPTS=%_JAVA_OPTS% -Xmx1024m -Dconfig.file=%SLICEBOX_HOME%\\conf\\slicebox.conf"""

	// native packaging - linux

	daemonUser in Linux := normalizedName.value // user which will execute the application

	daemonGroup in Linux := (daemonUser in Linux).value // group which will execute the application

	bashScriptExtraDefines += """addJava "-Dconfig.file=${app_home}/../conf/slicebox.conf" """

	rpmVendor := maintainer.value

	rpmLicense := Some("Apache v2")
	
	// for automatic license stub generation

	val licenceYear = "2016"
	val licencedTo = "Lars Edenbrandt"

	headers := Map(
	  "scala" -> Apache2_0(licenceYear, licencedTo),
	  "conf" -> Apache2_0(licenceYear, licencedTo, "#")
	)

	// repos

	resolvers ++= Seq("Typesafe Repository"	at "http://repo.typesafe.com/typesafe/releases/",
										"Sonatype snapshots"	at "http://oss.sonatype.org/content/repositories/snapshots/",
										"Spray Repository"		at "http://repo.spray.io",
										"Spray Nightlies"			at "http://nightlies.spray.io/")

	// deps

	libraryDependencies ++= {
		val akkaVersion				= "2.3.9"
		val sprayVersion			= "1.3.3"
		Seq(
			"com.typesafe.scala-logging"	%% "scala-logging"			% "3.1.0",
			"com.typesafe.akka"				%% "akka-actor"				% akkaVersion,
			"io.spray"						%% "spray-can"				% sprayVersion,
			"io.spray"						%% "spray-routing"			% sprayVersion,
			"io.spray"						%% "spray-client"			% sprayVersion,
			"io.spray"						%% "spray-json"				% "1.3.1",
			"com.typesafe.akka"				%% "akka-slf4j"				% akkaVersion,
			"ch.qos.logback"				%  "logback-classic" 		% "1.1.2",
			"com.typesafe.slick" 			%% "slick"					% "2.1.0",
			"com.h2database"				%  "h2"						% "1.4.190",
			"com.zaxxer"					%  "HikariCP"				% "2.4.3",
			"com.github.t3hnar"				%% "scala-bcrypt"			% "2.4",
			"org.scalatest"					%% "scalatest"				% "2.2.4"			% "test",
			"io.spray"						%% "spray-testkit"			% sprayVersion		% "test",
			"com.typesafe.akka"				%% "akka-testkit"			% akkaVersion		% "test",
			"org.webjars"					%  "angularjs"				% "1.4.7",
			"org.webjars"					%  "angular-material"		% "1.0.1",
			"org.webjars"					%  "angular-file-upload"	% "5.0.0"
		)
	}

	// run tests in separate JVMs

	fork in Test := true

	// eclipse IDE settings

	EclipseKeys.createSrc := EclipseCreateSrc.Default + EclipseCreateSrc.Resource

	EclipseKeys.withSource := true

	// define the project

	lazy val slicebox = (project in file(".")).enablePlugins(SbtWeb, JavaServerAppPackaging, GitBranchPrompt)

	mainClass in Compile := Some("se.nimsa.sbx.app.Main")

	// turn on cached resolution in SBT

	updateOptions := updateOptions.value.withCachedResolution(true)

	// make sure files in the public folder are included in build

	WebKeys.packagePrefix in Assets := "public/"
	
	(managedClasspath in Runtime) += (packageBin in Assets).value
