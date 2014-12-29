package scalaz.concurrent

import com.github.plokhotnyuk.actors.{ScalaForkJoinTask, JavaForkJoinTask}
import java.util.concurrent._
import java.util.concurrent.atomic.AtomicReference
import scala.concurrent.util.Unsafe.{instance => u}
import scalaz.Contravariant
import sun.misc.Unsafe

/**
 * Processes messages of type `A`, one at a time. Messages are submitted to
 * the actor with the method `!`. Processing is typically performed asynchronously,
 * this is controlled by the provided `strategy`.
 *
 * Memory consistency guarantee: when each message is processed by the `handler`, any memory that it
 * mutates is guaranteed to be visible by the `handler` when it processes the next message, even if
 * the `strategy` runs the invocations of `handler` on separate threads. This is achieved because
 * the `Actor` reads a volatile memory location before entering its event loop, and writes to the same
 * location before suspending.
 *
 * Implementation based on non-intrusive MPSC node-based queue, described by Dmitriy Vyukov:
 * [[http://www.1024cores.net/home/lock-free-algorithms/queues/non-intrusive-mpsc-node-based-queue]]
 *
 * @see scalaz.concurrent.Promise for a use case.
 */
sealed trait Actor2[A] {
  /** Pass the message `a` to the mailbox of this actor */
  def !(a: A): Unit

  def contramap[B](f: B => A): Actor2[B]

  def apply(a: A): Unit = this ! a

  def toEffect: Run[A] = Run[A](a => this ! a)
}

object Actor2 extends ActorInstances2 with ActorFunctions2

sealed abstract class ActorInstances2 {
  implicit val actorContravariant: Contravariant[Actor2] = new Contravariant[Actor2] {
    def contramap[A, B](r: Actor2[A])(f: B => A): Actor2[B] = r contramap f
  }
}

trait ActorFunctions2 {
  private val rethrow: Throwable => Unit = {
    case _: InterruptedException => Thread.currentThread.interrupt()
    case e =>
      val t = Thread.currentThread
      val h = t.getUncaughtExceptionHandler
      if (h ne null) h.uncaughtException(t, e)
      throw e
  }

  private val ignore: Any => Unit = _ => ()

  /**
   * Create actor with unbounded message queue
   *
   * @param handler  The message handler
   * @param onError  Exception handler, called if the message handler throws any `Throwable`
   * @param strategy Execution strategy, for example, a strategy that is backed by an `ExecutorService`
   * @tparam A       The type of messages accepted by this actor
   * @return         An instance of actor
   */
  def unboundedActor[A](handler: A => Unit, onError: Throwable => Unit = rethrow)
                       (implicit strategy: ActorStrategy): Actor2[A] =
    if (strategy eq ActorStrategy.Sequential) new SeqUnboundedActor[A](onError, handler)
    else new UnboundedActor[A](strategy, onError, handler)

  /**
   * Create actor with bounded message queue
   *
   * @param bound      An allowed maximum number of messages in queue
   * @param handler    The message handler
   * @param onError    Exception handler, called if the message handler throws any `Throwable`
   * @param onOverflow Overflow handler, called if the queue of non-handled incoming messages is full
   * @param strategy   Execution strategy, for example, a strategy that is backed by an `ExecutorService`
   * @tparam A         The type of messages accepted by this actor
   * @return           An instance of actor
   */
  def boundedActor[A](bound: Int, handler: A => Unit, onError: Throwable => Unit = rethrow, onOverflow: A => Unit = ignore)
                     (implicit strategy: ActorStrategy): Actor2[A] = {
    require(bound > 0, "Bound should be greater than 0")
    if (strategy eq ActorStrategy.Sequential) new SeqBoundedActor[A](bound, onError, onOverflow, handler)
    else new BoundedActor[A](bound, strategy, onError, onOverflow, handler)
  }

  implicit def ToFunctionFromActor[A](a: Actor2[A]): A => Unit = a ! _
}

private case class UnboundedActor[A](strategy: ActorStrategy, onError: Throwable => Unit,
                                     handler: A => Unit) extends AtomicReference[Node[A]] with Actor2[A] {
  def !(a: A): Unit = {
    val n = new Node(a)
    val h = getAndSet(n)
    if (h ne null) h.lazySet(n)
    else strategy(act(n))
  }

  def contramap[B](f: B => A): Actor2[B] = new UnboundedActor[B](strategy, onError, b => this ! f(b))

  @annotation.tailrec
  private def act(n: Node[A], i: Int = strategy.batch, f: A => Unit = handler): Unit = {
    try f(n.a) catch {
      case ex: Throwable => onError(ex)
    }
    val n2 = n.get
    if ((n2 ne null) && i != 0) act(n2, i - 1, f)
    else strategy(suspendOrAct(n))
  }

  private def suspendOrAct(n: Node[A]): Unit = {
    val n2 = n.get
    if (n2 ne null) act(n2)
    else if ((n ne get) || !compareAndSet(n, null)) act(n.next)
  }
}

private case class SeqUnboundedActor[A](onError: Throwable => Unit,
                                        handler: A => Unit) extends AtomicReference[Node[A]] with Actor2[A] {
  def !(a: A): Unit = {
    val n = new Node(a)
    val h = getAndSet(n)
    if (h ne null) h.lazySet(n)
    else act(n, handler)
  }

  def contramap[B](f: B => A): Actor2[B] = new SeqUnboundedActor[B](onError, b => this ! f(b))

  @annotation.tailrec
  private def act(n: Node[A], f: A => Unit): Unit = {
    try f(n.a) catch {
      case ex: Throwable => onError(ex)
    }
    val n2 = n.get
    if (n2 ne null) act(n2, f)
    else if ((n ne get) || !compareAndSet(n, null)) act(n.next, f)
  }
}

private final class Node[A](val a: A) extends AtomicReference[Node[A]] {
  @annotation.tailrec
  def next: Node[A] = {
    val n2 = get
    if (n2 ne null) n2
    else next
  }
}

private case class BoundedActor[A](bound: Int, strategy: ActorStrategy, onError: Throwable => Unit, onOverflow: A => Unit,
                                   handler: A => Unit) extends AtomicReference[NodeWithCount[A]] with Actor2[A] {
  @volatile private var count: Int = _

  def !(a: A): Unit = checkAndAdd(new NodeWithCount(a))

  def contramap[B](f: B => A): Actor2[B] = new BoundedActor[B](bound, strategy, onError, b => onOverflow(f(b)), b => this ! f(b))

  @annotation.tailrec
  private def checkAndAdd(n: NodeWithCount[A]): Unit = {
    val tc = count
    val h = get
    if (h eq null) {
      n.count = tc + 1
      if (compareAndSet(h, n)) strategy(act(n))
      else checkAndAdd(n)
    } else {
      val hc = h.count
      if (hc - tc < bound) {
        n.count = hc + 1
        if (compareAndSet(h, n)) h.lazySet(n)
        else checkAndAdd(n)
      } else onOverflow(n.a)
    }
  }

  @annotation.tailrec
  private def act(n: NodeWithCount[A], i: Int = strategy.batch, f: A => Unit = handler,
                  u: Unsafe = u, o: Long = BoundedActor.countOffset): Unit = {
    u.putOrderedInt(this, o, n.count)
    try f(n.a) catch {
      case ex: Throwable => onError(ex)
    }
    val n2 = n.get
    if ((n2 ne null) && i != 0) act(n2, i - 1, f, u, o)
    else strategy(suspendOrAct(n))
  }

  private def suspendOrAct(n: NodeWithCount[A]): Unit = {
    val n2 = n.get
    if (n2 ne null) act(n2)
    else if ((n ne get) || !compareAndSet(n, null)) act(n.next)
  }
}

private case class SeqBoundedActor[A](bound: Int, onError: Throwable => Unit, onOverflow: A => Unit,
                                      handler: A => Unit) extends AtomicReference[NodeWithCount[A]] with Actor2[A] {
  @volatile private var count: Int = _

  def !(a: A): Unit = checkAndAdd(new NodeWithCount(a))

  def contramap[B](f: B => A): Actor2[B] = new SeqBoundedActor[B](bound, onError, b => onOverflow(f(b)), b => this ! f(b))

  @annotation.tailrec
  private def checkAndAdd(n: NodeWithCount[A]): Unit = {
    val tc = count
    val h = get
    if (h eq null) {
      n.count = tc + 1
      if (compareAndSet(h, n)) act(n, handler, u, SeqBoundedActor.countOffset)
      else checkAndAdd(n)
    } else {
      val hc = h.count
      if (hc - tc < bound) {
        n.count = hc + 1
        if (compareAndSet(h, n)) h.lazySet(n)
        else checkAndAdd(n)
      } else onOverflow(n.a)
    }
  }

  @annotation.tailrec
  private def act(n: NodeWithCount[A], f: A => Unit, u: Unsafe, o: Long): Unit = {
    u.putOrderedInt(this, o, n.count)
    try f(n.a) catch {
      case ex: Throwable => onError(ex)
    }
    val n2 = n.get
    if (n2 ne null) act(n2, f, u, o)
    else if ((n ne get) || !compareAndSet(n, null)) act(n.next, f, u, o)
  }
}

private object BoundedActor {
  private val countOffset = u.objectFieldOffset(classOf[BoundedActor[_]].getDeclaredField("count"))
}

private object SeqBoundedActor {
  private val countOffset = u.objectFieldOffset(classOf[SeqBoundedActor[_]].getDeclaredField("count"))
}

private final class NodeWithCount[A](val a: A) extends AtomicReference[NodeWithCount[A]] {
  var count: Int = _

  @annotation.tailrec
  def next: NodeWithCount[A] = {
    val n2 = get
    if (n2 ne null) n2
    else next
  }
}

trait ActorStrategy {
  def batch: Int

  def apply(a: => Unit): Unit
}

object ActorStrategy {
  val Sequential: ActorStrategy = new AtomicReference[Thread] with ActorStrategy {
    val batch: Int = -1

    def apply(a: => Unit): Unit = a
  }

  def Executor(s: ExecutorService, b: Int = 10): ActorStrategy = s match {
    case p: scala.concurrent.forkjoin.ForkJoinPool => new ScalaForkJoinStrategy(p, b)
    case p: ForkJoinPool => new JavaForkJoinStrategy(p, b)
    case p => new ExecutorStrategy(p, b)
  }
}

private final class ScalaForkJoinStrategy(p: scala.concurrent.forkjoin.ForkJoinPool, b: Int) extends AsyncStrategy(b) {
  def apply(a: => Unit): Unit = new ScalaForkJoinTask(p) {
    def exec(): Boolean = {
      a
      false
    }
  }
}

private final class JavaForkJoinStrategy(p: ForkJoinPool, b: Int) extends AsyncStrategy(b) {
  def apply(a: => Unit): Unit = new JavaForkJoinTask(p) {
    def exec(): Boolean = {
      a
      false
    }
  }
}

private final class ExecutorStrategy(p: ExecutorService, b: Int) extends AsyncStrategy(b) {
  def apply(a: => Unit): Unit = p.execute(new Runnable {
    def run(): Unit = a
  })
}

private abstract class AsyncStrategy(b: Int) extends ActorStrategy {
  val batch: Int = b
}
