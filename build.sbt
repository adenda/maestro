name := "maestro"

organization := "com.adendamedia"

version := "0.1.4"

scalaVersion := "2.11.8"

enablePlugins(JavaAppPackaging, DockerPlugin)

resolvers += "Sonatype Releases" at "https://oss.sonatype.org/service/repositories/releases/"

val akkaVersion = "2.4.17"

libraryDependencies ++= Seq(
  "com.github.kliewkliew" %% "salad" % "0.11.04",
  "com.typesafe" % "config" % "1.3.1",
  "biz.paluch.redis" % "lettuce" % "5.0.0.Beta1",
  "com.typesafe.akka" %% "akka-actor" % akkaVersion,
  "com.typesafe.akka" %% "akka-stream" % akkaVersion
)

// ------------------------------------------------ //
// ------------- Docker configuration ------------- //
// ------------------------------------------------ //

javaOptions in Universal ++= Seq(
  "-Dconfig.file=etc/container.conf"
)

packageName in Docker := packageName.value

version in Docker := version.value

dockerBaseImage := "openjdk"

dockerRepository := Some("gcr.io/adenda-server-mongodb")

defaultLinuxInstallLocation in Docker := "/usr/local"

daemonUser in Docker := "root"