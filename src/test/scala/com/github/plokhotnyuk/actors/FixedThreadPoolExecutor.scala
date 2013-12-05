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
 */
class FixedThreadPoolExecutor(poolSize: Int = CPUs,
                              threadFactory: ThreadFactory = daemonThreadFactory(),
                              onError: Throwable => Unit = _.printStackTrace(),
                              onReject: Runnable => Unit = t => throw new RejectedExecutionException(t.toString),
                              name: String = generateName()) extends AbstractExecutorService {
  assert(poolSize > 0, "poolSize should be greater than 0")
  private val mask = Integer.highestOneBit(Math.min(poolSize, CPUs)) - 1
  private val tails = (0 to mask).map(_ => new PaddedAtomicReference(new TaskNode)).toArray
  private val state = new AtomicInteger // pool states: 0 - running, 1 - shutdown, 2 - stop
  private val sync = new AbstractQueuedSynchronizer {
    override protected final def tryReleaseShared(ignore: Int): Boolean = true

    override protected final def tryAcquireShared(ignore: Int): Int = pollAndRun()
  }
  private val heads = tails.map(n => new PaddedAtomicReference(n.get)).toArray
  private val terminations = new CountDownLatch(poolSize)
  private val threads = {
    val nm = name // to avoid long field name
    val ts = terminations // to avoid long field name
    val tf = threadFactory // to avoid creation of field for constructor param
    (1 to poolSize).map {
      i =>
        val t = tf.newThread(new Runnable {
          def run(): Unit =
            try work() catch {
              case _: InterruptedException => // ignore due usage as control flow exception internally
            } finally ts.countDown()
        })
        t.setName(s"$nm-worker-$i")
        t
    }
  }

  threads.foreach(_.start())

  def shutdown(): Unit = {
    checkShutdownAccess(threads)
    setState(1)
  }

  def shutdownNow(): util.List[Runnable] = {
    checkShutdownAccess(threads)
    setState(2)
    threads.filter(_ ne Thread.currentThread).foreach(_.interrupt()) // don't interrupt worker thread due call in task
    new util.LinkedList[Runnable]
  }

  def isShutdown: Boolean = state.get != 0

  def isTerminated: Boolean = terminations.getCount == 0

  def awaitTermination(timeout: Long, unit: TimeUnit): Boolean = {
    if (threads.exists(_ eq Thread.currentThread)) terminations.countDown() // don't hang up due call in task
    terminations.await(timeout, unit)
  }

  def execute(t: Runnable): Unit =
    if (t eq null) throw new NullPointerException
    else if (state.get == 0) {
      val n = new TaskNode(t)
      heads(Thread.currentThread().getId.toInt & mask).getAndSet(n).set(n)
      sync.releaseShared(1)
    } else onReject(t)

  override def toString: String = s"${super.toString}[$status], pool size = ${threads.size}, name = $name]"

  @annotation.tailrec
  private def work(): Unit = {
    sync.acquireShared(1)
    work()
  }

  private def pollAndRun(): Int = {
    val pos = Thread.currentThread().getId.toInt & mask
    pollAndRun(pos, 1, tails(pos))
  }

  @annotation.tailrec
  private def pollAndRun(pos: Int, offset: Int, tail: PaddedAtomicReference[TaskNode]): Int = {
    val tn = tail.get
    val n = tn.get
    if (n ne null) {
      if (tail.compareAndSet(tn, n)) {
        try n.task.run() catch {
          case ex: Throwable => onError(ex)
        } finally n.task = null // to avoid possible memory leak when queue is empty
      }
      if (state.get == 2) throw new InterruptedException
      else 1
    } else if (offset <= 1024) pollAndRun(pos, offset + 1, tails((pos ^ offset) & mask))
    else if (state.get != 0) throw new InterruptedException
    else -1
  }

  @annotation.tailrec
  private def setState(newState: Int): Unit = {
    val currState = state.get
    if (newState > currState && !state.compareAndSet(currState, newState)) setState(newState)
  }

  private def status: String =
    if (isTerminated) "Terminated"
    else state.get match {
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

private class TaskNode(var task: Runnable = null) extends AtomicReference[TaskNode]

private class PaddedAtomicReference[T](t: T) extends AtomicReference[T](t) {
  var p1, p2, p3, p4, p5, p6: Long = _
}
