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

import java.net.ConnectException
import com.twitter.finagle.stats.{DefaultStatsReceiver, StatsReceiver}
import com.twitter.logging.Logger
import com.twitter.util._

object RequestQueue {
  val queueFullEx = new Exception("Failed to enqueue request. The queue is full.")
  val queuingEx = new Exception("Faileed to enqueue request.")
}

class RequestQueue[Req <: ParrotRequest, Rep](
  consumer: RequestConsumer[Req, Rep],
  transport: ParrotTransport[Req, Rep],
  statsReceiver: StatsReceiver = DefaultStatsReceiver
) {
  private[this] val log = Logger.get(getClass)
  private[this] val scopedStats = statsReceiver.scope("request_queue")
  private[this] val queueDepthGauge = scopedStats.addGauge("queue_depth") { queueDepth }
  private[this] val responseTimeout = scopedStats.counter("response_timeout")
  private[this] val connectionRefused = scopedStats.counter("connection_refused")
  private[this] val unexpectedError = scopedStats.counter("unexpected_error")
  private[this] val success = scopedStats.counter("success")

  def addRequest(request: Req): (Future[Rep], Future[Unit]) = {
    val response = new Promise[Rep]()
    request.response = response
    val queueResult = consumer
      .offer(request)
      .respond {
        case Return(false) => response.raise(RequestQueue.queueFullEx)
        case Throw(_) => response.raise(RequestQueue.queuingEx)
        case _ =>
      }
      .map { _ =>
        ()
      }
    (response, queueResult)
  }

  def pause() {
    consumer.pause()
  }

  def resume() {
    consumer.continue()
  }

  def setRate(newRate: Int): Future[Unit] = {
    consumer.setRate(newRate)
  }

  def start(): Future[Unit] = {
    log.debug("starting RequestQueue")
    transport respond {
      case Return(response) =>
        transport.stats(response).foreach {
          _.foreach { rep =>
            scopedStats.counter(rep).incr()
          }
        }
      case Throw(t) =>
        t match {
          case e: ConnectException =>
            if (e.getMessage.contains("timed out")) responseTimeout.incr()
            if (e.getMessage.contains("refused")) connectionRefused.incr()
          case _ =>
            unexpectedError.incr()
            scopedStats.counter("unexpected_error/" + t.getClass.getName).incr()
            log.error("unexpected error: %s", t)
        }
    }
    consumer.run()
  }

  def queueDepth: Int = consumer.size
  def totalProcessed: Int = consumer.totalProcessed.get()

  /** indicate when the first record has been received */
  def firstRecordReceivedFromFeeder: Future[Unit] = consumer.started

  def shutdown(): Future[Unit] = {
    log.trace("RequestQueue: shutting down.")
    consumer.shutdown()
  }
}
