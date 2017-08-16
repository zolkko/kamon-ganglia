Ganglia Integration
===================

The [kamon](https://kamon.io)'s reporter backend for Ganglia.

Ganglia is an old-school monitoring system, which claims to
be used on many high-performance computing systems. Despite ganglia-monitor and
gmetad are arguably nice programs, the ganglia-web application just sucks in so
many ways, that, in fact, I am planning to use this project until we replace
Ganglia with something more convenient in our cluster.

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

For a complete example of how to enable the backend, please see `hello-akka-kamon` project under examples directory.