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
This kamon-ganglia will never going to be published, so the one option to use the library is to
add a code like following into `build.sbt` file of your project:

```scala
lazy val kamonGanglia = RootProject(uri("git://github.com/zolkko/kamon-ganglia.git"))
```
