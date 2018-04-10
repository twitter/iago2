/*
Copyright 2013 Twitter, Inc.

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

import com.twitter.finagle.TimeoutException
import com.twitter.iago.feeder.FeederFixture
import com.twitter.iago.processor.TestThriftRecordProcessor
import com.twitter.iago.server._
import com.twitter.iago.util.PrettyDuration
import com.twitter.iago.{ParrotConfig, ParrotServerFlags, ParrotTest}
import com.twitter.logging.Logger
import com.twitter.util.{Await, Duration, Future}
import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner
@RunWith(classOf[JUnitRunner])
class ThriftRecordProcessorSpec extends ParrotTest with FeederFixture with ServerFixture {

  private val log = Logger.get(getClass)
  if (integration) "Thrift Record Processor" should {

    "get responses from a Thrift service" in {
      EchoServer.serve()
      val server = new TestServerApp {
        override lazy val parrot = (new ThriftTestParrot(this))
          .asInstanceOf[ParrotConfig[ParrotRequest, Object]]
      }

      server.nonExitingMain(
        Array(
          "-victimHostPort=" + EchoServer.address,
          "-service.port=:0",
          "-requestTimeout=500.milliseconds"
        )
      )

      // the following feeder shuts down when it reaches the end of the log
      val feeder = new TestFeederApp(List("hello"))
      feeder.nonExitingMain(Array("-parrotHosts=" + server.address))

      val successful = waitForServer(server.parrotServer.done, feeder.cutoffF())
      if (successful) {
        EchoServer.getRequestCount must not be 0
        server.parrot.recordProcessor
          .asInstanceOf[TestThriftRecordProcessor]
          .response must not be ""
      }
      EchoServer.close
    }
  }

  private def waitForServer(done: Future[Unit], seconds: Double): Boolean = {
    val duration = Duration.fromNanoseconds((seconds * Duration.NanosPerSecond).toLong)
    try {
      Await.ready(done, duration)
      true
    } catch {
      case timeout: TimeoutException =>
        log.warning("ThriftRecordProcessorSpec: timed out in %s", PrettyDuration(duration))
        false
      case interrupted: InterruptedException =>
        log.warning("ThriftRecordProcessorSpec: test was shut down")
        false
      case other: Exception =>
        log.warning("Error message: %s", other.getMessage)
        log.warning(other.getStackTraceString)
        false
    }
  }
}

private class ThriftTestParrot(config: ParrotServerFlags)
    extends ParrotConfig[ParrotRequest, Array[Byte]](config) {
  val transport = ThriftTransportFactory(config)
  val recordProcessor = new TestThriftRecordProcessor(service)
}
