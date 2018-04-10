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
package com.twitter.iago.feeder

import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.{CountDownLatch, TimeUnit}

import com.twitter.finagle.util.DefaultTimer
import com.twitter.iago.ParrotFeederFlags
import com.twitter.iago.util.{ParrotClusterImpl, PrettyDuration, RemoteParrot}
import com.twitter.logging.Logger
import com.twitter.util._

import scala.collection.mutable

object FeederState extends Enumeration {
  val EOF, TIMEOUT, RUNNING = Value
}

case class Results(success: Int, failure: Int)

class ParrotFeeder(config: ParrotFeederFlags) extends Closable {
  private[this] val log = Logger.get(getClass.getName)
  private[this] val shuttingDown = new Promise[Unit]
  val done = new Promise[Unit]
  val requestsRead = new AtomicLong(0)

  @volatile
  protected var state = FeederState.RUNNING

  private[this] val initializedParrots = mutable.Set[RemoteParrot]()

  private[this] val allServers = new CountDownLatch(config.numInstancesF())
  private[this] lazy val cluster = new ParrotClusterImpl(config)
  private[this] lazy val poller = new ParrotPoller(cluster, allServers)

  private[this] lazy val lines = config.logSource

  /**
   * Starts up the whole schebang. Called from FeederMain.
   */
  def start() {

    val duration = config.durationF()
    if (duration > Duration.Bottom)
      shutdownAfter(duration)

    // Poller is starting here so that we can validate that we get enough servers, ie
    // so that validatePreconditions has a chance to pass without a 5 minute wait.
    poller.start()

    if (!validatePreconditions()) {
      shutdown()
    } else {

      val t = new Thread(new Runnable {
        def run() {
          runLoad()
          reportResults()
          shutdown()
        }
      })

      t.start()
    }
  }

  /**
   * Called internally when we're done for any number of reasons. Also can be
   * called remotely if the web management interface is enabled.
   */
  def shutdown(): Future[Unit] = {
    if (shuttingDown.updateIfEmpty(Return.Unit)) {
      try {
        log.trace("ParrotFeeder: shutting down ...")
        if (state == FeederState.RUNNING)
          state = FeederState.TIMEOUT // shuts down immediately when timeout
        cluster.shutdown()
        poller.shutdown()
        log.trace("ParrotFeeder: shut down")
      } finally {
        done.setDone()
      }
    }
    done
  }

  def close(deadline: Time)(implicit timer: Timer = DefaultTimer.twitter): Future[Unit] = {
    shutdown().raiseWithin(timeout = deadline - Time.now)(timer = timer)
  }

  def close(deadline: Time): Future[Unit] = {
    shutdown().raiseWithin(timeout = deadline - Time.now)(timer = DefaultTimer.twitter)
  }

  private[this] def validatePreconditions(): Boolean = {
    if (config.batchSizeF() <= 0) {
      log.error("batchSize is set <= 0! Shutting down")
      return false
    }

    if (config.maxRequestsF() <= 0) {
      log.error("maxRequests is <= 0! Shutting down")
      return false
    }

    if (!lines.hasNext) {
      log.error("The log file is empty! Shutting down")
      return false
    }

    // Must call this before blocking below
    cluster.connectParrots()

    // This gives our server(s) a chance to start up by waiting on a latch the Poller manages.
    // This technically could have a bug -- if a server were to start up and then disappear,
    // the latch would still potentially tick down in the poller, and we'd end up with
    // fewer servers than expected. The code further down will cover that condition.

    val wantedInstances = config.numInstancesF()
    log.info("Awaiting %d servers to stand up and be recognized.", wantedInstances)
    allServers.await(5, TimeUnit.MINUTES)

    if (cluster.runningParrots.isEmpty) {
      log.error("Empty Parrots list! Is Parrot running somewhere?")
      return false
    }

    val actualInstances = cluster.runningParrots.size

    if (actualInstances != wantedInstances) {
      log.error("Found %d servers, expected %d", actualInstances, wantedInstances)
      cluster.runningParrots foreach { parrot =>
        log.error("Server: %s", parrot.address)
      }
    }
    true
  }

  // TODO: What happens if our parrot membership changes? We should use the most current
  // TODO: cluster members, but that implies iterating through the set of parrots in an
  // TODO: ordered way even as they get disconnected and removed from the set. This would
  // TODO: require something like a consistent hash. Oof.
  // TEST: make sure that if we are only supposed to send a partial batch, it happens correctly
  private[this] def runLoad() {

    skipForward(config.linesToSkipF())

    while (state == FeederState.RUNNING) {
      // limit the number of parrots we use to what we were configured to use
      val parrots = cluster.runningParrots.slice(0, config.numInstancesF())

      parrots foreach { parrot =>
        if (!initialized(parrot)) {
          initialize(parrot)
        }

        parrot.queueBatch {
          readBatch(linesToRead)
        }

        if (config.maxRequestsF() - requestsRead.get <= 0) {
          log.info(
            "ParrotFeeder.runLoad: exiting because config.maxRequests = %d and requestsRead.get = %d",
            config.maxRequestsF(),
            requestsRead.get
          )
          state = FeederState.EOF
        } else if (!lines.hasNext) {
          if (config.reuseFileF()) {
            log.trace("ParrotFeeder.runLoad: inputLog is exhausted, restarting reader.")
            lines.reset()
          } else {
            log.info("ParrotFeeder.runLoad: exiting because the log is exhausted")
            state = FeederState.EOF
          }
        }
      }
    }
  }

  private[this] def skipForward(skip: Int) {
    for (i <- 1 to skip if lines.hasNext) {
      lines.next()
    }
  }

  private[this] def initialized(parrot: RemoteParrot): Boolean = {
    initializedParrots.contains(parrot)
  }

  private[this] def initialize(parrot: RemoteParrot) {
    val requestRate = config.requestRateF()
    parrot.targetDepth = config.cachedSecondsF() * requestRate
    parrot.setRate(requestRate)
    parrot.createConsumer {
      state
    }
    initializedParrots += parrot
  }

  private[this] def linesToRead: Int = {
    val batchSize = config.batchSizeF()
    val linesLeft = config.maxRequestsF() - requestsRead.get

    if (!lines.hasNext || linesLeft <= 0) 0
    else if (batchSize <= linesLeft) batchSize
    else linesLeft.toInt
  }

  private[this] def readBatch(readSize: Int): List[String] = {
    log.debug("Reading %d log lines.", readSize)

    val result = for (i <- 1 to readSize if lines.hasNext) yield lines.next()

    requestsRead.addAndGet(result.size)
    result.toList
  }

  // TEST: How do we handle the case where we feed a parrot for a while and then it dies,
  // TEST: but we continue the run with the rest of the cluster?
  private[this] def reportResults() {
    val results = getResults(cluster.parrots)
    log.info("Lines played: %d failed: %d", results.success, results.failure)
  }

  // TEST: confirm that a set of parrots add up to the expected total
  private[this] def getResults(remotes: Set[RemoteParrot]): Results = {
    var successes = 0
    var failures = 0

    remotes foreach (successes += _.results.success)
    remotes foreach (failures += _.results.failure)

    Results(successes, failures)
  }

  private[this] def shutdownAfter(duration: Duration): Future[Unit] = {
    DefaultTimer.twitter.doLater(duration) {
      log.info(
        "ParrotFeeder.shutdownAfter: shutting down due to duration timeout of %s",
        PrettyDuration(duration)
      )
      state = FeederState.TIMEOUT
      shutdown()
    }
  }
}
