import NativePackagerHelper._

name := "maestro"

organization := "com.adendamedia"

//version := "0.1.4"
version := "0.32-SNAPSHOT"

scalaVersion := "2.11.8"

enablePlugins(JavaAppPackaging, DockerPlugin)

resolvers += "Sonatype Releases" at "https://oss.sonatype.org/service/repositories/releases/"

//resolvers += Resolver.url(
//  "bintray-skuber",
//  url("http://dl.bintray.com/oriordan/skuber"))(
//  Resolver.ivyStylePatterns)

val akkaVersion = "2.4.17"

val testDependencies = Seq(
  "com.typesafe.akka" %%  "akka-testkit" % akkaVersion  % "test",
  "org.scalatest"     %%  "scalatest"    % "3.0.0"      % "test",
  "org.mockito" % "mockito-all" % "1.10.19" % Test
).map(_.exclude("ch.qos.logback", "logback-classic"))

libraryDependencies ++= Seq(
  "com.github.kliewkliew" %% "salad" % "0.11.04",
  "com.github.kliewkliew" %% "cornucopia" % "0.20-SNAPSHOT",
  "com.typesafe" % "config" % "1.3.1",
  "biz.paluch.redis" % "lettuce" % "5.0.0.Beta1",
  "com.typesafe.akka" %% "akka-actor" % akkaVersion,
  "com.typesafe.akka" %% "akka-agent" % akkaVersion,
  "com.typesafe.akka" %% "akka-stream" % akkaVersion,
  "io.doriordan" %% "skuber" % "0.5-SNAPSHOT",
  "org.slf4j" % "slf4j-log4j12" % "1.7.22"
).map(_.exclude("ch.qos.logback", "logback-classic")) ++ testDependencies

// ------------------------------------------------ //
// ------------- Docker configuration ------------- //
// ------------------------------------------------ //

mappings in Universal ++= directory( baseDirectory.value / "src" / "main" / "resources" )

javaOptions in Universal ++= Seq(
  "-Dconfig.file=/usr/local/etc/container.conf",
  "-Dlog4j.configuration=file:/usr/local/etc/log4j.properties"
)

packageName in Docker := packageName.value

version in Docker := version.value

dockerBaseImage := "openjdk"

dockerRepository := Some("gcr.io/adenda-server-mongodb")

defaultLinuxInstallLocation in Docker := "/usr/local"

daemonUser in Docker := "root"