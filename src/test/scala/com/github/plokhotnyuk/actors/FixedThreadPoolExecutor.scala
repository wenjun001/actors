package com.github.plokhotnyuk.actors

import java.util
import java.util.concurrent._
import java.util.concurrent.atomic.{AtomicReference, AtomicInteger}
import java.util.concurrent.locks.AbstractQueuedSynchronizer
import com.github.plokhotnyuk.actors.FixedThreadPoolExecutor._

/**
 * An implementation of an `java.util.concurrent.ExecutorService ExecutorService`
 * with fixed number of pooled threads. It efficiently works at high rate of task submission
 * and/or with thousands of worker threads without overuse of CPU and increasing latency
 * between submission of tasks and starting of execution of them.
 *
 * For applications that require separate or custom pools, a `FixedThreadPoolExecutor`
 * may be constructed with a given pool size, that by default is equal to the number of available processors.
 *
 * All threads are created in constructor call using a `java.util.concurrent.ThreadFactory`.
 * If not otherwise specified, a default thread factory is used, that creates threads with daemon status.
 *
 * When running of tasks an uncaught exception can occurs. All unhandled exception are redirected to handler
 * that if not adjusted, by default, just print stack trace without stopping of execution of worker thread.
 *
 * Number of tasks which submitted but not yet executed is not limited, so
 * `java.util.concurrent.RejectedExecutionException` can occurs only after shutdown
 * when pool was initialized with default implementation of `onReject: Runnable => Unit`.
 *
 * An implementation of task queue based on MultiLane (over MPMC queues) that described here
 * [[https://blogs.oracle.com/dave/entry/multilane_a_concurrent_blocking_multiset]]
 *
 * Idea to use some implementation of 'java.util.concurrent.locks.AbstractQueuedSynchronizer' borrowed from
 * [[https://github.com/laforge49/JActor2/blob/master/jactor2-core/src/main/java/org/agilewiki/jactor2/core/facilities/ThreadManager.java]]
 *
 * @param poolSize       A number of worker threads in pool
 * @param threadFactory  A factory to be used to build worker threads
 * @param onError        The exception handler for unhandled errors during executing of tasks
 * @param onReject       The handler for rejection of task submission after shutdown
 * @param name           A name of the executor service
 * @param batch          A number of task completions before slowdown
 * @param spin           A number of checking if there is any task submitted before parking of worker thread
 */
class FixedThreadPoolExecutor(poolSize: Int = CPUs,
                              threadFactory: ThreadFactory = daemonThreadFactory(),
                              onError: Throwable => Unit = _.printStackTrace(),
                              onReject: Runnable => Unit = _ => throw new RejectedExecutionException,
                              name: String = generateName(),
                              batch: Int = 256 / CPUs,
                              spin: Int = 0) extends AbstractExecutorService {
  if (poolSize < 1) throw new IllegalArgumentException("poolSize should be greater than 0")
  private val mask = Integer.highestOneBit(Math.min(poolSize, CPUs)) - 1
  private val heads = (0 to mask).map(_ => new PaddedAtomicReference(new TaskNode)).toArray
  private val poller = new Poller(onError, heads.map(n => new PaddedAtomicReference(n.get)).toArray, batch, spin)
  private val terminations = new CountDownLatch(poolSize)
  private val threads = {
    val nm = name // to avoid long field name
    val tf = threadFactory // to avoid creation of field for constructor param
    (1 to poolSize).map {
      i =>
        val t = tf.newThread(new Runnable {
          def run(): Unit = work()
        })
        t.setName(s"$nm-worker-$i")
        t
    }.toArray
  }
  threads.foreach(_.start())

  def shutdown(): Unit = {
    checkShutdownAccess(threads)
    poller.updateState(1)
  }

  def shutdownNow(): util.List[Runnable] = {
    checkShutdownAccess(threads)
    poller.updateState(2)
    threads.filter(_ ne Thread.currentThread).foreach(_.interrupt()) // don't interrupt worker thread due call in task
    new util.LinkedList[Runnable]
  }

  def isShutdown: Boolean = poller.state != 0

  def isTerminated: Boolean = terminations.getCount == 0

  def awaitTermination(timeout: Long, unit: TimeUnit): Boolean = {
    if (threads.exists(_ eq Thread.currentThread)) terminations.countDown() // don't hang up due call in task
    terminations.await(timeout, unit)
  }

  def execute(t: Runnable): Unit =
    if (t eq null) throw new NullPointerException
    else if (poller.state == 0) {
      val n = new TaskNode(t)
      heads(Thread.currentThread().getId.toInt & mask).getAndSet(n).set(n)
      poller.releaseShared(0)
    } else onReject(t)

  override def toString: String = s"${super.toString}[$status], pool size = ${threads.size}, name = $name]"

  private def work(): Unit =
    try work(poller, Thread.currentThread().getId.toInt & mask) catch {
      case _: InterruptedException => // ignore due usage as control flow exception internally
    } finally terminations.countDown()

  @annotation.tailrec
  private def work(poller: Poller, base: Int): Unit = {
    poller.acquireShared(base)
    work(poller, base)
  }

  private def status: String =
    if (isTerminated) "Terminated"
    else poller.state match {
      case 0 => "Running"
      case 1 => "Shutdown"
      case 2 => "Stop"
    }
}

private object FixedThreadPoolExecutor {
  private val CPUs = Runtime.getRuntime.availableProcessors
  private val poolId = new AtomicInteger
  private val shutdownPerm = new RuntimePermission("modifyThread")

  def checkShutdownAccess(ts: Seq[Thread]): Unit =
    Option(System.getSecurityManager).foreach {
      sm =>
        sm.checkPermission(shutdownPerm)
        ts.foreach(sm.checkAccess)
    }

  def daemonThreadFactory(): ThreadFactory = new ThreadFactory {
    def newThread(worker: Runnable): Thread = new Thread(worker) {
      setDaemon(true)
    }
  }

  def generateName(): String = s"FixedThreadPool-${poolId.incrementAndGet()}"
}

private final class Poller(onError: Throwable => Unit, tails: Array[PaddedAtomicReference[TaskNode]],
                           batch: Int, spin: Int) extends AbstractQueuedSynchronizer {
  private val size = tails.length

  def state = getState

  @annotation.tailrec
  def updateState(newState: Int): Unit = {
    val currState = getState
    if (newState > currState && !compareAndSetState(currState, newState)) updateState(newState)
  }

  override protected def tryReleaseShared(ignore: Int): Boolean = true

  override protected def tryAcquireShared(base: Int): Int = {
    val workerTail = tails(base)
    pollAndRun(workerTail, workerTail, base, 1, batch, spin)
  }

  @annotation.tailrec
  private def pollAndRun(workerTail: PaddedAtomicReference[TaskNode], tail: PaddedAtomicReference[TaskNode],
                         base: Int, offset: Int, b: Int, s: Int): Int = {
    val tn = tail.get
    val n = tn.get
    if (n ne null) {
      if (tail.compareAndSet(tn, n)) {
        try n.task.run() catch {
          case ex: Throwable => onError(ex)
        } finally n.task = null // to avoid possible memory leak when queue is empty
        if (getState > 1) throw new InterruptedException
        else if (b != 0) pollAndRun(workerTail, workerTail, base, 1, b - 1, spin)
        else 0 // slowdown to avoid starvation
      } else pollAndRun(workerTail, workerTail, base, 1, batch, spin)
    } else if (offset < size) pollAndRun(workerTail, tails(base ^ offset), base, offset + 1, batch, s)
    else if (getState > 0) throw new InterruptedException
    else if (s != 0) pollAndRun(workerTail, workerTail, base, 1, batch, s - 1)
    else -1
  }
}

private class TaskNode(var task: Runnable = null) extends AtomicReference[TaskNode]

private class PaddedAtomicReference[T](t: T) extends AtomicReference[T](t) {
  var p1, p2, p3, p4, p5, p6: Long = _
}
