package example

import scala.concurrent.duration._

import akka.actor.{Actor, ActorSystem, Props}
import kamon.Kamon


class TestActor extends Actor {

  implicit val ec = context.dispatcher

  private val simpleCounter = Kamon.metrics.counter("simple-counter")

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
  Kamon.start()

  implicit val system = ActorSystem("my-app")
  implicit val ec = system.dispatcher
  system.actorOf(Props(new TestActor))

  system.whenTerminated.onComplete { _ =>
    Kamon.shutdown()
  }
}
