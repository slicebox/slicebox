resolvers += Resolver.typesafeRepo("releases")

resolvers += Classpaths.typesafeReleases

addSbtPlugin("com.typesafe.sbt" % "sbt-web" % "1.4.0")

addSbtPlugin("com.typesafe.sbt" % "sbt-jshint" % "1.0.4")

addSbtPlugin("com.typesafe.sbteclipse" % "sbteclipse-plugin" % "4.0.0")

addSbtPlugin("io.spray" % "sbt-revolver" % "0.8.0")

addSbtPlugin("com.typesafe.sbt" % "sbt-native-packager" % "1.1.4")

addSbtPlugin("de.heikoseeberger" % "sbt-header" % "1.5.1")

addSbtPlugin("com.typesafe.sbt" % "sbt-git" % "0.8.5")

addSbtPlugin("org.scoverage" % "sbt-scoverage" % "1.5.0")

addSbtPlugin("org.scoverage" % "sbt-coveralls" % "1.1.0")

addSbtPlugin("com.timushev.sbt" % "sbt-updates" % "0.2.0")
