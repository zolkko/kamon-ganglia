Ganglia Integration
===================

Reporting Metrics to Ganglia
=============================

[Ganglia] is a hardcore old-school monitoring system, that claims to
be used on high-performance computing systems. It is use RDDTools for data storage and
visualization.

Limitations
-----------
Only XDR (UDP) v3.1 protocol is supported.

Installation
------------ 
This kamon-ganglia will never going to be published anywhere, so the one option to use the library is to
directly add a `git` dependency to your project. The `build.sbt` file may look like:

```scala
lazy val kamonGanglia = RootProject(uri("git://github.com/zolkko/kamon-ganglia.git"))

lazy val root = (project in file(".")).
  settings(
    inThisBuild(List(
      organization := "com.example",
      scalaVersion := "2.11.11",
      version      := "0.1.0-SNAPSHOT"
    )),
    name := "Hello",
    libraryDependencies ++= Seq(
      "io.kamon" %%"kamon-core" % "0.6.7"
    )
  ).dependsOn(kamonGanglia)
```

Finally create an `application.conf` file:

```json
kamon {
  ganglia {
    hostname = "example.com"
    port = 8649
    metric-name-prefix = "sample-app"
  }
}
```