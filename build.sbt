name := "kamon-ganglia"

scalaVersion := "2.11.11"

inThisBuild(List(
  organization := "io.blumenplace",
  scalaVersion := "2.11.11",
  version      := "0.2.0-SNAPSHOT"
))

libraryDependencies ++= Seq(
  "io.kamon" %% "kamon-core" % "1.1.2",
  "info.ganglia.gmetric4j" % "gmetric4j" % "1.0.10",
  "com.typesafe.akka" %% "akka-actor" % "2.5.12"
)
