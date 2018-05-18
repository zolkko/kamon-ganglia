package example

import scala.concurrent.duration._

import akka.actor.{Actor, ActorSystem, Props}


class TestActor extends Actor {

  implicit val ec = context.dispatcher

  private val simpleCounter = kamon.Kamon.counter("simple-counter")

  private def random: Int = (scala.math.random * 100.0).toInt

  override def preStart(): Unit = {
    context.system.scheduler.scheduleOnce(1 second, self, random)
  }

  override def receive: Receive = {
    case msg =>
      simpleCounter.increment()
      println(s"received a message $msg")
      context.system.scheduler.scheduleOnce(1 second, self, random)
  }

}


object Hello extends App {

  kamon.Kamon.loadReportersFromConfig()

  implicit val system = ActorSystem("my-app")
  implicit val ec = system.dispatcher
  system.actorOf(Props(new TestActor))

  system.whenTerminated.onComplete { _ =>
    kamon.Kamon.stopAllReporters()
  }
}
