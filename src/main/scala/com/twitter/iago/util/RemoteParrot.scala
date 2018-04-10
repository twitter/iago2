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
package com.twitter.iago.util

import java.net.InetSocketAddress
import com.twitter.finagle.service.{RetryExceptionsFilter, RetryPolicy}
import com.twitter.finagle.util.DefaultTimer
import org.apache.thrift.protocol.TBinaryProtocol
import com.twitter.conversions.time.intToTimeableNumber
import com.twitter.finagle.{Address, Name, ThriftMux}
import com.twitter.logging.Logger
import com.twitter.iago.feeder.FeedConsumer
import com.twitter.iago.thriftscala.ParrotServerService
import com.twitter.iago.thriftscala.ParrotStatus
import com.twitter.util._
import com.twitter.iago.feeder.FeederState
import com.twitter.finagle.stats.DefaultStatsReceiver

class InternalCounter(var success: Int = 0, var failure: Int = 0) {
  def add(counter: InternalCounter) {
    success += counter.success
    failure += counter.failure
  }
}

class RemoteParrot(
  val results: InternalCounter,
  val address: InetSocketAddress,
  val finagleTimeout: Duration = 5.seconds,
  val batchSize: Int,
  var queueDepth: Double = 0.0,
  var targetDepth: Double = 0.0
) {
  private[this] val log = Logger(getClass.getName)
  private[this] var consumer: FeedConsumer = null
  private[this] var traceCount: Long = 0

  private[this] val (service, client) = connect

  def createConsumer(state: => FeederState.Value) {
    log.trace("RemoteParrot: creating consumer")

    // We want to queue to at least be the size on one batch and have some
    // extra rooms for buffering new requests
    val queueCapacity = batchSize * 1.25
    log.info(s"Setting FeedConsumer queue capacity to ${queueCapacity}")
    consumer = new FeedConsumer(this, state, queueCapacity.toInt)
    consumer.start()
    log.trace("RemoteParrot: consumer created")
  }

  def hasCapacity = consumer.queue.remainingCapacity > 0

  def addRequest(batch: List[String]) {
    consumer.addRequest(batch)
  }

  def queueBatch(batchFun: => List[String]) {
    if (hasCapacity) {
      traceCount = 0
      val batch = batchFun
      if (batch.size > 0) {
        log.trace("RemoteParrot: Queuing batch for %s with %d requests", address, batch.size)
        addRequest(batch)
      }
    } else {
      if (traceCount < 2)
        log.trace("RemoteParrot: parrot[%s] queue is over capacity", address)
      else if (traceCount == 2)
        log.trace("RemoteParrot: parrot[%s] more over capacity warnings ...Ï", address)
      traceCount += 1
    }
  }

  def setRate(newRate: Int) {
    log.trace("RemoteParrot: setting rate %d", newRate)
    waitFor(client.setRate(newRate))
    log.trace("RemoteParrot: rate set")
  }

  def sendRequest(batch: Seq[String]): ParrotStatus = {
    log.trace(
      "RemoteParrot.sendRequest: parrot[%s] sending requests of size=%d to the server",
      address,
      batch.size
    )
    val result = waitFor(client.sendRequest(batch))
    log.trace(
      "RemoteParrot.sendRequest: parrot[%s] done sending requests of size=%d to the server",
      address,
      batch.size
    )
    result
  }

  def getStatus: ParrotStatus = {
    waitFor(client.getStatus())
  }

  def pause() {
    waitFor(client.pause())
  }

  def resume() {
    waitFor(client.resume())
  }

  def shutdown() {
    consumer.shutdown
    waitFor(client.shutdown())
    service.close()
  }

  def isConnected() = {
    service.isAvailable
  }

  override def equals(that: Any): Boolean = {
    that match {
      case other: RemoteParrot => other.address == address
      case _ => false
    }
  }

  override lazy val hashCode = address.hashCode()

  def isBusy = queueDepth > targetDepth

  private[this] def connect = {
    val retryPolicy = RetryPolicy.tries(2, RetryPolicy.TimeoutAndWriteExceptionsOnly)

    def retryingFilter[Rep, Req] =
      new RetryExceptionsFilter[Rep, Req](retryPolicy, DefaultTimer.twitter, DefaultStatsReceiver)

    val service = retryingFilter andThen ThriftMux.newService(
      Name.bound(Address(address)),
      label = "iago_server"
    )
    val client = new ParrotServerService.FinagledClient(service, new TBinaryProtocol.Factory())

    (service, client)
  }

  private[this] def waitFor[A](future: Future[A]) = Await.result(future, finagleTimeout)
}
