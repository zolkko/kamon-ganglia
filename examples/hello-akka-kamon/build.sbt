import Dependencies._


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
      "ch.qos.logback"    %  "logback-classic" % "1.1.2",
      "com.typesafe.akka" %% "akka-actor"      % "2.5.3",
      "io.kamon" %% "kamon-core" % "0.6.7",
      "io.kamon" %% "kamon-akka" % "0.6.3",
      scalaTest % Test
    )
  )
  .dependsOn(kamonGanglia)


aspectjSettings

javaOptions in run <++= AspectjKeys.weaverOptions in Aspectj

mainClass in Compile := Some("example.Hello")

fork in run := true
