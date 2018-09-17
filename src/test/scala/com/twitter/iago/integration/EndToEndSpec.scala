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
package com.twitter.iago.integration

import com.twitter.iago.feeder.{FeederFixture, ParrotFeeder}
import com.twitter.iago.processor.TestRecordProcessor
import com.twitter.iago.server._
import com.twitter.iago.util.PrettyDuration
import com.twitter.iago.{ParrotConfig, ParrotServerFlags, ParrotTest}
import com.twitter.logging.Logger
import com.twitter.util.{Await, Duration, Future, Stopwatch, Time, TimeoutException}
import com.twitter.finagle.http.Response
import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner

@RunWith(classOf[JUnitRunner])
class EndToEndSpec extends ParrotTest with FeederFixture with ServerFixture {
  private[this] val log = Logger.get(getClass.getName)
  private[this] val urls =
    List("about", "about/contact", "about/security", "tos", "privacy", "about/resources")

  if (integration) "Feeder and Server" should {

    "Send requests to the places we tell them" in {
      val server = TestServerApp()
      val feeder = new TestFeederApp(urls)
      feeder.main(Array("-maxRequests=20", "-parrotHosts=" + server.address, "-requestRate=1000"))
      waitForServer(server.parrotServer.done, feeder.cutoffF())
      val (requestsRead, allRequests, rp) = report(feeder.feeder, server.parrot)
      requestsRead must be(urls.size)
      allRequests must be(requestsRead)
      rp.responded must be(requestsRead)
      assert(rp.properlyShutDown)
    }

    "Send requests at the rate we ask them to" in {
      val server = TestServerApp()
      val rate = 5 // rps ... requests per second
      val seconds = 20 // how long we expect to take to send our requests
      val totalRequests: Int = rate * seconds
      println("creating TestFeederApp")
      val feeder = new TestFeederApp(victimList(totalRequests))
      println("TestFeederApp created")
      feeder.nonExitingMain(
        Array(
          "-maxRequests=" + totalRequests,
          "-parrotHosts=" + server.address,
          "-requestRate=" + rate,
          "-reuseFile"
        )
      )
      println("TestFeederApp started")
      waitUntilFirstRecord(server, totalRequests)
      println("TestFeederApp first request received")
      val start = Time.now
      waitForServer(server.parrotServer.done, seconds * 2)
      val parrot = server.parrot
      val (requestsRead, allRequests, rp) = report(feeder.feeder, parrot)
      log.debug(
        "EndToEndSpec: transport.allRequests (%d) == totalRequests (%d): " +
          (allRequests == totalRequests.toLong),
        allRequests,
        totalRequests
      )
      allRequests must be(totalRequests.toLong)
      val untilNow = start.untilNow.inNanoseconds.toDouble / Duration.NanosPerSecond.toDouble
      untilNow must be < (seconds * 1.20)
      untilNow must be > (seconds * 0.80)
      log.debug(
        "EndToEndSpec: transport.allRequests (%d) == requestsRead (%d): " +
          (allRequests == requestsRead),
        allRequests,
        requestsRead
      )
      allRequests must be(requestsRead)
      log.debug(
        "EndToEndSpec: rp.responded (%d) == requestsRead (%d): " + (rp.responded == requestsRead),
        rp.responded,
        requestsRead
      )
      rp.responded must be(requestsRead)
      assert(rp.properlyShutDown)
    }

    "honor timouts" in {
      val server = TestServerApp()
      val rate = 1 // rps ... requests per second
      val seconds = 10 // how long we expect to take to send our requests
      val totalRequests = rate * seconds * 2
      val feeder = new TestFeederApp(victimList(totalRequests))
      feeder.nonExitingMain(
        Array( // shuts down when feederConfig.duration elapses
          "-batchSize=3",
          "-cachedSeconds=1",
          "-duration=5.seconds",
          "-log.level=ALL",
          "-maxRequests=" + totalRequests,
          "-parrotHosts=" + server.address,
          "-requestRate=" + rate,
          "-reuseFile"
        )
      )
      waitForServer(server.parrotServer.done, seconds * 2)
      val (requestsRead, allRequests, rp) = report(feeder.feeder, server.parrot)
      allRequests must be < requestsRead.toLong
      assert(rp.properlyShutDown)
    }
  }

  private[this] def waitForServer(done: Future[Unit], seconds: Double) {
    val duration = Duration.fromNanoseconds((seconds * Duration.NanosPerSecond.toDouble).toLong)
    try {
      Await.ready(done, duration)
    } catch {
      case e: TimeoutException =>
        log.warning("EndToEndSpec: timed out in %s", PrettyDuration(duration))
    }
  }

  private[this] def report(
    feeder: ParrotFeeder,
    parrot: ParrotConfig[ParrotRequest, Object]
  ): (Long, Long, TestRecordProcessor) = {
    val transport = parrot.transport.asInstanceOf[FinagleTransport]
    val result = (
      feeder.requestsRead.get(),
      transport.allRequests.get(),
      parrot.recordProcessor.asInstanceOf[TestRecordProcessor]
    )
    val (requestsRead, allRequests, rp) = result
    log.info(
      "EndToEndSpec: done waiting for the parrot server to finish. %d lines read, %d lines sent to victim," +
        " %d responses back",
      requestsRead,
      allRequests,
      rp.responded
    )
    result
  }

  private[this] def victimList(totalRequests: Int) = List.fill(totalRequests * 2)("/")

  private[this] def waitUntilFirstRecord(server: TestServerApp, totalRequests: Int): Unit = {
    val elapsed = Stopwatch.start()
    Await.ready(server.parrotServer.firstRecordReceivedFromFeeder)
    log.debug(
      "EndToEndSpec: totalRequests = %d, time to first message was %s",
      totalRequests,
      PrettyDuration(elapsed())
    )
  }
}

class TestParrotConfig(config: ParrotServerFlags)
    extends ParrotConfig[ParrotRequest, Response](config) {
  val transport = FinagleTransportFactory(config)
  val recordProcessor = new TestRecordProcessor(service, config)
}
