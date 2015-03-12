	name := "slicebox"

	version := "0.1"

	organization := "se.vgregion"

	scalaVersion := "2.11.4"

	scalacOptions := Seq("-encoding", "UTF-8", "-Xlint","-deprecation", "-unchecked", "-feature", "-target:jvm-1.8")

	Revolver.settings

	maintainer in Linux := "Exini Diagnostics AB"

	maintainer in Windows := "Exini Diagnostics AB"

	packageSummary in Linux := "Slicebox DICOM sharing service"

	packageSummary in Windows := "Slicebox DICOM sharing service"

	packageDescription in Linux := "Slicebox is a service for sharing medical image data with collaborators while protecting patient information"

	packageDescription in Windows := "Slicebox is a service for sharing medical image data with collaborators while protecting patient information"

	mappings in Universal <+= (packageBin in Compile, sourceDirectory ) map { (_, src) =>
    // we are using the reference.conf as default application.conf
    // the user can override settings here
    val conf = src / "main" / "resources" / "slicebox.conf"
    conf -> "conf/slicebox.conf"
	}

	resolvers ++= Seq("Typesafe Repository" at "http://repo.typesafe.com/typesafe/releases/",
	                  "Sonatype snapshots"  at "http://oss.sonatype.org/content/repositories/snapshots/",
	                  "Spray Repository"    at "http://repo.spray.io",
	                  "Spray Nightlies"     at "http://nightlies.spray.io/")

	libraryDependencies ++= {
	  val akkaVersion       = "2.3.8"
	  val sprayVersion      = "1.3.2"
	  Seq(
	    "com.typesafe.scala-logging" 	%% "scala-logging" 								% "3.1.0",
	    "com.typesafe.akka" 					%% "akka-actor"      							% akkaVersion,
	    "io.spray"          					%% "spray-can"       							% sprayVersion,
	    "io.spray"          					%% "spray-routing"   							% sprayVersion,
	    "io.spray" 										%% "spray-client" 								% sprayVersion,
	    "io.spray" 										%% "spray-json" 									% "1.3.1",
	    "com.typesafe.akka" 					%% "akka-slf4j"      							% akkaVersion,
	    "ch.qos.logback"    					%  "logback-classic" 							% "1.1.2",
			"com.typesafe.slick" 					%% "slick" 												% "2.1.0",
			"com.h2database" 							%  "h2" 													% "1.3.170",
			"com.mchange"									%  "c3p0"													% "0.9.5",
			"com.github.t3hnar" 					%% "scala-bcrypt" 								% "2.4",
	    "org.scalatest"     					%% "scalatest"       							% "2.2.1"       	% "test",
			"io.spray" 										%% "spray-testkit" 								% sprayVersion 		% "test",
	    "com.typesafe.akka" 					%% "akka-testkit"    							% akkaVersion   	% "test",
			"org.webjars" 								%  "bootstrap" 										% "3.3.1",
			"org.webjars" 								%  "angularjs" 										% "1.3.3",
		"org.webjars" % "angular-ui-bootstrap" % "0.12.0",
		"org.webjars" % "font-awesome" % "4.2.0"
	  )
	}

	fork in Test := true

	EclipseKeys.createSrc := EclipseCreateSrc.Default + EclipseCreateSrc.Resource

	EclipseKeys.withSource := true

	mainClass in Compile := Some("se.vgregion.app.Main")

	lazy val slicebox = (project in file(".")).enablePlugins(SbtWeb, JavaServerAppPackaging)

	updateOptions := updateOptions.value.withCachedResolution(true)

	WebKeys.packagePrefix in Assets := "public/"
	
	includeFilter in (Assets, LessKeys.less) := "*.less"

	(managedClasspath in Runtime) += (packageBin in Assets).value
