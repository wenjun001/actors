package com.github.plokhotnyuk.actors

import org.junit.runner.RunWith
import org.specs2.runner.JUnitRunner
import org.specs2.mutable.Specification
import org.specs2.execute.{Success, Result}
import org.specs2.specification.{Step, Fragments, Example}
import concurrent.forkjoin.{ForkJoinWorkerThread, ForkJoinPool}
import java.util.concurrent._
import java.util.concurrent.atomic.AtomicInteger
import com.higherfrequencytrading.affinity.AffinitySupport
import com.github.plokhotnyuk.actors.BenchmarkSpec._
import com.higherfrequencytrading.affinity.impl.{PosixJNAAffinity, WindowsJNAAffinity, NativeAffinity}

@RunWith(classOf[JUnitRunner])
abstract class BenchmarkSpec extends Specification {
  sequential
  xonly

  override def map(fs: => Fragments) = Step(setup()) ^ fs.map {
    case Example(desc, body) => Example(desc.toString, { printf("\n%s:\n", desc); body() })
    case other => other
  } ^ Step(shutdown())

  def setup() {
    threadSetup()
  }

  def shutdown() {
  }
}

object BenchmarkSpec {
  val executorServiceType = System.getProperty("benchmark.executorServiceType", "lifo-forkjoin-pool")
  val parallelism = System.getProperty("benchmark.parallelism", Runtime.getRuntime.availableProcessors.toString).toInt
  val threadPriority = System.getProperty("benchmark.threadPriority", Thread.currentThread().getPriority.toString).toInt
  val isAffinityOn = System.getProperty("benchmark.affinityOn", "false").toBoolean
  if (isAffinityOn) {
    if (NativeAffinity.LOADED) {
      println("Using JNI-based affinity control implementation")
    } else if (NativeAffinity.isWindows && AffinitySupport.isJNAAvailable && WindowsJNAAffinity.LOADED) {
      println("Using Windows JNA-based affinity control implementation")
    } else if (AffinitySupport.isJNAAvailable && PosixJNAAffinity.LOADED) {
      println("Using Posix JNA-based affinity control implementation")
    } else {
      println("Using dummy affinity control implementation")
    }
  }
  val printBinding = System.getProperty("benchmark.printBinding", "false").toBoolean
  val nextCpuId = new AtomicInteger()

  def createExecutorService(): ExecutorService = {
    def createForkJoinWorkerThreadFactory() = new ForkJoinPool.ForkJoinWorkerThreadFactory {
      def newThread(pool: ForkJoinPool): ForkJoinWorkerThread = new ForkJoinWorkerThread(pool) {
        override def run() {
          threadSetup()
          super.run()
        }
      }
    }

    def createThreadFactory() = new ThreadFactory {
      override def newThread(r: Runnable): Thread = new Thread {
        override def run() {
          threadSetup()
          r.run()
        }
      }
    }

    executorServiceType match {
      case "fifo-forkjoin-pool" => new ForkJoinPool(parallelism, createForkJoinWorkerThreadFactory(), null, true)
      case "lifo-forkjoin-pool" => new ForkJoinPool(parallelism, createForkJoinWorkerThreadFactory(), null, false)
      case "fixed-thread-pool" => new ThreadPoolExecutor(parallelism, parallelism, 60, TimeUnit.SECONDS,
        new LinkedBlockingQueue[Runnable](), createThreadFactory(), new ThreadPoolExecutor.AbortPolicy())
      case _ => throw new IllegalArgumentException("Unsupported executorService")
    }
  }

  def timed(n: Int)(benchmark: => Unit): Result = {
    val t = System.nanoTime
    benchmark
    val d = System.nanoTime - t
    printf("%,d ns\n%,d ops\n%,d ns/op\n%,d ops/s\n", d, n, d / n, (n * 1000000000L) / d)
    Success()
  }

  def fork(code: => Unit) {
    new Thread {
      override def run() {
        threadSetup()
        code
      }
    }.start()
  }

  def threadSetup() {
    def setCurrentThreadPriority(priority: Int) {
      def ancestors(thread: ThreadGroup, acc: List[ThreadGroup] = Nil): List[ThreadGroup] =
        if (thread.getParent != null) ancestors(thread.getParent, thread :: acc) else acc

      val thread = Thread.currentThread()
      ancestors(thread.getThreadGroup).foreach(_.setMaxPriority(priority))
      thread.setPriority(priority)
    }

    setCurrentThreadPriority(threadPriority)
    if (isAffinityOn) synchronized {
      val cpuId = nextCpuId.getAndIncrement % Runtime.getRuntime.availableProcessors
      AffinitySupport.setAffinity(1L << cpuId)
      if (printBinding) {
        val thread = Thread.currentThread()
        println("CPU[" + cpuId + "]: '" + thread.getName + "' with priority: " + thread.getPriority)
      }
    }
  }
}