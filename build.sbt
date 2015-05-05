import de.heikoseeberger.sbtheader.license.Apache2_0

	name := "slicebox"

	version := "0.2-SNAPSHOT"

	organization := "se.nimsa"

	scalaVersion := "2.11.6"

	scalacOptions := Seq("-encoding", "UTF-8", "-Xlint","-deprecation", "-unchecked", "-feature", "-target:jvm-1.8")

	Revolver.settings

	maintainer in Universal := "nimsa.se"

	packageSummary in Universal := "Slicebox DICOM sharing service"

	packageDescription in Universal := "Slicebox is a service for sharing medical image data with collaborators while protecting patient information"

	mappings in Universal <+= (packageBin in Compile, sourceDirectory ) map { (_, src) =>
		val httpConf = src / "main" / "resources" / "application.conf"
		httpConf -> "conf/slicebox.conf"
	}

	bashScriptExtraDefines += """addJava "-Dconfig.file=${app_home}/../conf/slicebox.conf" """
	batScriptExtraDefines += """set _JAVA_OPTS=%_JAVA_OPTS% -Dconfig.file=%SLICEBOX_HOME%\\conf\\slicebox.conf"""

	val licenceYear = "2015"
	val licencedTo = "Karl Sjöstrand"

	headers := Map(
	  "scala" -> Apache2_0(licenceYear, licencedTo),
	  "conf" -> Apache2_0(licenceYear, licencedTo, "#")
	)

	resolvers ++= Seq("Typesafe Repository"	at "http://repo.typesafe.com/typesafe/releases/",
										"Sonatype snapshots"	at "http://oss.sonatype.org/content/repositories/snapshots/",
										"Spray Repository"		at "http://repo.spray.io",
										"Spray Nightlies"			at "http://nightlies.spray.io/")

	libraryDependencies ++= {
		val akkaVersion				= "2.3.9"
		val sprayVersion			= "1.3.3"
		Seq(
			"com.typesafe.scala-logging" 	%% "scala-logging"								% "3.1.0",
			"com.typesafe.akka" 					%% "akka-actor"										% akkaVersion,
			"io.spray"										%% "spray-can"										% sprayVersion,
			"io.spray"										%% "spray-routing"								% sprayVersion,
			"io.spray" 										%% "spray-client"									% sprayVersion,
			"io.spray" 										%% "spray-json"										% "1.3.1",
			"com.typesafe.akka" 					%% "akka-slf4j"										% akkaVersion,
			"ch.qos.logback"							%  "logback-classic" 							% "1.1.2",
			"com.typesafe.slick" 					%% "slick"												% "2.1.0",
			"com.h2database" 							%  "h2"														% "1.3.170",
			"com.mchange"									%  "c3p0"													% "0.9.5",
			"com.github.t3hnar" 					%% "scala-bcrypt"									% "2.4",
			"org.scalatest"								%% "scalatest"										% "2.2.4"					% "test",
			"io.spray" 										%% "spray-testkit"								% sprayVersion 		% "test",
			"com.typesafe.akka" 					%% "akka-testkit"									% akkaVersion			% "test",
			"org.webjars" 								%  "angularjs"										% "1.3.15",
			"org.webjars"									%	 "angular-material"							% "0.8.3",
			"org.webjars"									%  "font-awesome"									% "4.2.0"
		)
	}

	fork in Test := true

	EclipseKeys.createSrc := EclipseCreateSrc.Default + EclipseCreateSrc.Resource

	EclipseKeys.withSource := true

	mainClass in Compile := Some("se.nimsa.sbx.app.Main")

	lazy val slicebox = (project in file(".")).enablePlugins(SbtWeb, JavaServerAppPackaging, GitBranchPrompt)

	updateOptions := updateOptions.value.withCachedResolution(true)

	WebKeys.packagePrefix in Assets := "public/"
	
	(managedClasspath in Runtime) += (packageBin in Assets).value
