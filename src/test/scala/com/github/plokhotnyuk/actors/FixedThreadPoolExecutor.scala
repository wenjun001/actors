package com.github.plokhotnyuk.actors

import java.util.concurrent._
import java.util.concurrent.atomic._
import java.util.concurrent.locks.AbstractQueuedSynchronizer
import scala.annotation.tailrec

/**
 * A high performance implementation of an {@link ExecutorService} with fixed number of pooled threads.
 * It efficiently works with thousands of threads without overuse of CPU and
 * degradation of latency between task submit and starting of its execution.
 *
 * <p>For applications that require separate or custom pools, a {@code FixedThreadPoolExecutor}
 * may be constructed with a given pool size; by default, equal to the number of available processors.
 *
 * <p>All threads are created in constructor call using a {@link ThreadFactory}.
 * If not otherwise specified, a default thread factory is used, that creates threads with daemon status.
 *
 * <p>When running of tasks an uncaught exception can occurs. All unhandled exception are redirected to
 * provided handler that by default just print stack trace without stopping of worker thread execution.
 *
 * <p>An implementation of task queue based on structure of non-intrusive MPSC node-based queue, described by Dmitriy Vyukov:
 * http://www.1024cores.net/home/lock-free-algorithms/queues/non-intrusive-mpsc-node-based-queue
 *
 * <p>An idea of using of semaphore to control of queue access borrowed from implementation of ThreadManager of JActor2:
 * https://github.com/laforge49/JActor2/blob/master/jactor-impl/src/main/java/org/agilewiki/jactor/impl/ThreadManagerImpl.java
 *
 * @param threadCount a number of worker threads in pool
 * @param threadFactory a factory to be used to build worker threads
 * @param handler the handler for internal worker threads that will be called
 *                in case of unrecoverable errors encountered while executing tasks.
 */
class FixedThreadPoolExecutor(threadCount: Int = Runtime.getRuntime.availableProcessors(),
                              threadFactory: ThreadFactory = new ThreadFactory() {
                                def newThread(r: Runnable): Thread = new Thread(r) {
                                  setDaemon(true)
                                }
                              },
                              handler: Thread.UncaughtExceptionHandler = new Thread.UncaughtExceptionHandler() {
                                def uncaughtException(t: Thread, e: Throwable): Unit = e.printStackTrace()
                              }) extends AbstractExecutorService {
  private val head = new AtomicReference[TaskNode](new TaskNode())
  private val requests = new CountingSemaphore()
  private val running = new AtomicBoolean(true)
  private val tail = new AtomicReference[TaskNode](head.get)
  private val terminations = new CountDownLatch(threadCount)
  private val threads = {
    val tf = threadFactory // to avoid creating of field for the threadFactory constructor param
    (1 to threadCount).map(_ => tf.newThread(new Runnable() {
      def run(): Unit = doWork()
    }))
  }

  threads.foreach(_.start())

  /**
   * Attempts to stop all actively executing tasks and discards of all previously submitted tasks,
   * then blocks until all running tasks will be stopped.
   *
   * <p>This method does not wait for previously submitted tasks to
   * complete execution.  Use {@link #awaitTermination awaitTermination}
   * to do that.
   */
  def shutdown() {
    shutdownNow()
    awaitTermination(0, TimeUnit.MILLISECONDS)
  }

  /**
   * Attempts to stop all actively executing tasks by interrupting of worker threads,
   * and returns a list of the tasks that were submitted and awaiting for execution.
   *
   * <p>This method does not wait for actively executing tasks to terminate. Use {@link #awaitTermination} to do that.
   *
   * <p>Any task that fails to respond to interrupt (tight loop, etc.) may never terminate.
   *
   * @return list of tasks that never commenced execution
   */
  def shutdownNow(): java.util.List[Runnable] = {
    running.lazySet(false)
    threads.filter(_ ne Thread.currentThread()).foreach(_.interrupt()) // don't interrupt worker thread due call in task
    drainTo(new java.util.LinkedList[Runnable](), tail.getAndSet(head.get)) // drain up to current head
  }

  /**
   * Returns <tt>true</tt> if shutdown of pool was started by {@link #shutdownNow} or {@link #shutdown} call.
   *
   * @return <tt>true</tt> if shutdown of pool was started.
   */
  def isShutdown: Boolean = !running.get

  /**
   * Returns <tt>true</tt> if shutdown completed and all worker threads of pool are stopped.
   *
   * @return <tt>true</tt> if shutdown completed.
   */
  def isTerminated: Boolean = terminations.getCount == 0

  def awaitTermination(timeout: Long, unit: TimeUnit): Boolean = {
    if (threads.exists(_ eq Thread.currentThread())) terminations.countDown() // don't hang up due call in task
    terminations.await(timeout, unit)
  }

  /**
   * Executes the given task at some time in the future.
   *
   * <p>Never throws {@link RejectedExecutionException} and all tasks which are submitted after {@link #shutdownNow} call
   * are silently collected in the internal task queue that can be drained up by subsequent {@link #shutdownNow} call.
   *
   * @param task the runnable task
   * @throws NullPointerException if the task is null
   */
  def execute(task: Runnable) {
    enqueue(task)
    requests.releaseShared(1)
  }

  private def enqueue(task: Runnable) {
    if (task eq null) throw new NullPointerException
    val n = new TaskNode(task)
    head.getAndSet(n).lazySet(n)
  }

  @tailrec
  private def drainTo(ts: java.util.List[Runnable], tn: TaskNode): java.util.List[Runnable] =
    if (tn eq tail.get) ts
    else {
      val n = tn.get
      ts.add(n.task)
      drainTo(ts, n)
    }

  private def doWork() {
    try {
      while (running.get) {
        try {
          requests.acquireSharedInterruptibly(1)
          dequeueAndRun()
        } catch {
          case ex: Throwable => if (running.get) handler.uncaughtException(Thread.currentThread(), ex)
        }
      }
    } finally {
      terminations.countDown()
    }
  }

  @tailrec
  private def dequeueAndRun() {
    val tn = tail.get
    val n = tn.get
    if ((n ne null) && tail.compareAndSet(tn, n)) {
      val t = n.task
      n.task = null
      t.run()
    } else dequeueAndRun()
  }
}

private class CountingSemaphore extends AbstractQueuedSynchronizer() {
  private val count = new AtomicInteger()

  override protected final def tryReleaseShared(releases: Int): Boolean = {
    count.getAndAdd(releases)
    true
  }

  @tailrec
  override protected final def tryAcquireShared(acquires: Int): Int = {
    val available = count.get
    val remaining = available - acquires
    if (remaining < 0 || count.compareAndSet(available, remaining)) remaining
    else tryAcquireShared(acquires)
  }
}

private class TaskNode(var task: Runnable = null) extends AtomicReference[TaskNode]