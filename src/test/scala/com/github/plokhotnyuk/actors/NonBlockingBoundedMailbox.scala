package com.github.plokhotnyuk.actors

import java.util.concurrent.atomic.AtomicReference
import scala.util.control.NoStackTrace
import com.typesafe.config.Config
import akka.actor.{ActorSystem, ActorRef}
import akka.AkkaException
import akka.dispatch._

class NonBlockingBoundedMailbox(bound: Int = Int.MaxValue) extends MailboxType with ProducesMessageQueue[MessageQueue] {
  if (bound <= 0) throw new IllegalArgumentException("Mailbox bound should be greater than 0")

  def this(settings: ActorSystem.Settings, config: Config) = this(config.getInt("mailbox-bound"))

  override def create(owner: Option[ActorRef], system: Option[ActorSystem]): MessageQueue = new NBBQ(bound)
}

@SerialVersionUID(1L)
case class OutOfMailboxBoundException(message: String) extends AkkaException(message) with NoStackTrace

private final class NBBQ(bound: Int) extends AtomicReference(new NBBQNode) with MessageQueue {
  private val tail = new AtomicReference(get)

  override def enqueue(receiver: ActorRef, handle: Envelope): Unit = offer(new NBBQNode(handle))

  override def dequeue(): Envelope = poll(tail)

  override def numberOfMessages: Int = get.count - tail.get.count

  override def hasMessages: Boolean = get ne tail.get

  @annotation.tailrec
  override def cleanUp(owner: ActorRef, deadLetters: MessageQueue): Unit = {
    val e = dequeue()
    if (e ne null) {
      deadLetters.enqueue(owner, e)
      cleanUp(owner, deadLetters)
    }
  }

  @annotation.tailrec
  private def offer(n: NBBQNode): Unit = {
    val tc = tail.get.count
    val h = get
    val hc = h.count
    if (hc - tc < bound) {
      n.count = hc + 1
      if (compareAndSet(h, n)) h.lazySet(n)
      else offer(n)
    } else throw new OutOfMailboxBoundException("Mailbox bound exceeded")
  }

  @annotation.tailrec
  private def poll(t: AtomicReference[NBBQNode]): Envelope = {
    val tn = t.get
    val n = tn.get
    if (n ne null) {
      if (t.compareAndSet(tn, n)) {
        val e = n.env
        n.env = null // to avoid possible memory leak when queue is empty
        e
      } else poll(t)
    } else null
  }
}

private class NBBQNode(var env: Envelope = null) extends AtomicReference[NBBQNode] {
  var count: Int = _
}
