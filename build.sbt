name := "kamon-ganglia"

scalaVersion := "2.11.11"

inThisBuild(List(
  organization := "io.blumenplace",
  scalaVersion := "2.11.11",
  version      := "0.1.0-SNAPSHOT"
))

libraryDependencies ++= Seq(
  "io.kamon" %% "kamon-core" % "0.6.7",
  "info.ganglia.gmetric4j" % "gmetric4j" % "1.0.10",
  "com.typesafe.akka" %% "akka-actor" % "2.5.3"
)
