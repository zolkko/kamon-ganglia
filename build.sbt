name := "kamon-ganglia"

scalaVersion := "2.13.1"

inThisBuild(List(
  organization := "io.blumenplace",
  scalaVersion := "2.13.1",
  version      := "0.2.5-SNAPSHOT"
))

libraryDependencies ++= Seq(
  "io.kamon" %% "kamon-core" % "2.0.1",
  "info.ganglia.gmetric4j" % "gmetric4j" % "1.0.10"
)
