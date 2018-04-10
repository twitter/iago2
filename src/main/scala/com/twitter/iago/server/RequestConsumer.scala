/*
Copyright 2012 Twitter, Inc.

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
 */
package com.twitter.iago.server

import java.util.NoSuchElementException
import java.util.concurrent.atomic.{AtomicInteger, AtomicReference}
import java.util.concurrent.{Executors, LinkedBlockingDeque}
import com.twitter.concurrent.NamedPoolThreadFactory
import com.twitter.conversions.time._
import com.twitter.finagle.Service
import com.twitter.finagle.stats.{DefaultStatsReceiver, StatsReceiver}
import com.twitter.finagle.util.HashedWheelTimer
import com.twitter.iago.util.{PrettyDuration, RequestDistribution}
import com.twitter.logging.Logger
import com.twitter.util._
import scala.annotation.tailrec
import scala.collection.parallel

trait RequestSchedule {
  def next(): Time
}

// TODO move to `com.twitter.utils`
class IagoRequestSchedule(
  dist: RequestDistribution,
  startTime: => Time = Time.now
) extends RequestSchedule {
  private[this] val prev: AtomicReference[Time] = new AtomicReference(null)
  private[this] val distribution: AtomicReference[RequestDistribution] = new AtomicReference(dist)

  def next(): Time = {
    if (prev.get == null) {
      val start = startTime
      if (prev.compareAndSet(null, start))
        return start
    }
    nextTime(distribution.get.timeToNextArrival())
  }

  def setNewDistribution(newDist: RequestDistribution) {
    distribution.set(newDist)
  }

  def jumpToTime(time: Time) {
    prev.set(time)
  }

  @tailrec
  private[this] def nextTime(dur: Duration): Time = {
    val old = prev.get
    val newTime = old + dur
    if (prev.compareAndSet(old, newTime)) newTime else nextTime(dur)
  }
}

object RequestConsumer {
  private[this] val numThreads = parallel.availableProcessors
  protected val futurePool = FuturePool.interruptible(Executors.newFixedThreadPool(numThreads))
  val cancelEx = new Exception("RequestConsumer stop requested.")
}

class RequestConsumer[Req <: ParrotRequest, Rep](
  distributionFactory: Int => RequestDistribution,
  transport: Service[Req, Rep],
  futurePool: FuturePool = RequestConsumer.futurePool,
  statsReceiver: StatsReceiver = DefaultStatsReceiver
) {
  private[this] val log = Logger.get(getClass)
  private[this] implicit val timer = HashedWheelTimer(
    new NamedPoolThreadFactory("Iago Load Distribution Timer", true),
    tickDuration = 50.microseconds,
    ticksPerWheel = 1000
  )
  private[this] val queue = new LinkedBlockingDeque[Req]()
  private[this] val dispatcher = new Promise[Unit]
  private[this] var rate: Int = 1
  private[this] val schedule = new IagoRequestSchedule(distributionFactory(rate))
  val totalProcessed = new AtomicInteger(0)

  private[this] val scopedStats = statsReceiver.scope("request_consumer")
  private[this] val requestsSent = scopedStats.counter("requests_sent")

  val started = new Promise[Unit]
  private[this] val done = new Promise[Unit]

  def offer(request: Req): Future[Boolean] = {
    futurePool { queue.offerFirst(request) }
  }

  def run(): Future[Unit] = {
    if (started.updateIfEmpty(Return.Unit)) {
      log.info("RequestConsumer: beginning run.")
      dispatcher.setInterruptHandler {
        case _ =>
          val finalizer = new Promise[Unit]
          finalizer.respond(_ => done.setDone())
          finalizer.become(takeOne(draining = true))
      }
      dispatcher.become(takeOne())
    }
    started
  }

  private[this] def send(request: Req): Unit = {
    try {
      val future = transport(request)
      requestsSent.incr()
      future.ensure {
        totalProcessed.getAndIncrement()
      }
    } catch {
      case NonFatal(e) =>
        log.error(e, "Exception sending request: %s", request)
    }
  }

  private[this] def takeOne(draining: Boolean = false): Promise[Unit] = {
    val p = Promise[Unit]()
    def takeAnother() = p.become(takeOne(draining))
    futurePool {
      if (draining) {
        queue.pop()
      } else {
        queue.take()
      }
    }.onSuccess { request =>
        send(request)
        val waitTime = schedule.next()
        val now = Time.now
        // Most timers latch on millisecondly intervals due to the behavior
        // of `Object.wait(long)`. Events are processed immediately until
        // one is scheduled for the future. This allows handling rates higher
        // than 1/ms in a way that is faithful to the underlying schedule.
        if (waitTime <= now) {
          // Resetting the schedule in the event that the application is paused
          // for a long time prevents extended bursts of traffic.
          if (waitTime < now.minus(5.seconds)) { schedule.jumpToTime(now) }
          takeAnother()
        } else {
          timer.doAt(waitTime) { takeAnother() }
        }
      }
      .handle { case _: NoSuchElementException => p.setDone() }
    p
  }

  def pause() {
    log.info("Pause not implemented.")
    // suspend
  }

  def continue() {
    log.info("Continue not implemented.")
    // resume
  }

  def size = {
    queue.size()
  }

  def setRate(newRate: Int): Future[Unit] = {
    futurePool {
      this.synchronized {
        rate = newRate
        schedule.setNewDistribution(distributionFactory(rate))
      }
    }.ensure { log.info(s"Rate set to %s", newRate) }
  }

  def shutdown(): Future[Unit] = {
    val elapsed = Stopwatch.start()
    dispatcher.raise(RequestConsumer.cancelEx)
    done.ensure {
      log.trace("RequestConsumer shut down in %s", PrettyDuration(elapsed()))
    }
  }
}
