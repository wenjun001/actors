package com.github.plokhotnyuk.actors

import org.junit.runner.RunWith
import org.specs2.mutable.Specification
import org.specs2.runner.JUnitRunner
import com.github.plokhotnyuk.actors.Helper._
import java.util.concurrent.CountDownLatch
import akka.jsr166y.ForkJoinPool
import Scalaz2._
import scalaz.concurrent.Strategy

@RunWith(classOf[JUnitRunner])
class ScalazActor2Test extends Specification {
  implicit val executor = new ForkJoinPool()

  import Strategy.Executor

  "Single-producer sending" in {
    val n = 100000000
    timed("Single-producer sending", n) {
      val l = new CountDownLatch(1)
      val a = tickActor(l, n)
      sendTicks(a, n)
      l.await()
    }
  }

  "Multi-producer sending" in {
    val n = 100000000
    timed("Multi-producer sending", n) {
      val l = new CountDownLatch(1)
      val a = tickActor(l, n)
      for (j <- 1 to CPUs) fork {
        sendTicks(a, n / CPUs)
      }
      l.await()
    }
  }

  "Ping between actors" in {
    val n = 20000000
    timed("Ping between actors", n) {
      val l = new CountDownLatch(2)
      var p1: Actor2[Message] = null
      val p2 = actor2[Message] {
        var i = n / 2
        (m: Message) =>
          p1 ! m
          i -= 1
          if (i == 0) l.countDown()
      }
      p1 = actor2[Message] {
        var i = n / 2
        (m: Message) =>
          p2 ! m
          i -= 1
          if (i == 0) l.countDown()
      }
      p2 ! Message()
      l.await()
    }
  }

  "Max throughput" in {
    val n = 100000000
    timed("Max throughput", n) {
      val l = new CountDownLatch(halfOfCPUs)
      for (j <- 1 to halfOfCPUs) fork {
        val a = tickActor(l, n / halfOfCPUs)
        sendTicks(a, n / halfOfCPUs)
      }
      l.await()
    }
  }

  private[this] def tickActor(l: CountDownLatch, n: Int): Actor2[Message] = actor2[Message] {
    var i = n
    (m: Message) =>
      i -= 1
      if (i == 0) l.countDown()
  }

  private[this] def sendTicks(a: Actor2[Message], n: Int) {
    val m = Message()
    var i = n
    while (i > 0) {
      a ! m
      i -= 1
    }
  }
}