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

package com.twitter.iago.server

import java.net.{InetAddress, InetSocketAddress}
import com.twitter.app.App
import com.twitter.finagle.builder.Server
import com.twitter.iago.integration.HttpServer
import com.twitter.iago.util.LoadTestStub
import com.twitter.iago.{ParrotConfig, ParrotServerFlags}
import com.twitter.logging.Logging
import com.twitter.finagle.http.Response
import org.scalatest.{BeforeAndAfterEach, OneInstancePerTest, TestSuite}
import scala.language.{existentials, implicitConversions}

trait ServerFixture extends BeforeAndAfterEach with OneInstancePerTest {
  this: TestSuite =>

  val httpServerAddress = new InetSocketAddress(InetAddress.getLoopbackAddress, 0)
  val testVictim = "localhost:" + httpServerAddress.getPort.toString

  override def beforeEach(): Unit = {
    HttpServer.serve(httpServerAddress)
    super.beforeEach() // To be stackable, must call super.beforeEach last
  }

  override def afterEach(): Unit = {
    try {
      super.afterEach() // To be stackable, must call super.afterEach first
    } finally {
      HttpServer.close()
    }
  }

  lazy val dumbTestServer = {
    new App with ParrotServerFlags {
      val parrotServer = new ParrotServer(this)
      val self = this
      override lazy val parrot = new ParrotConfig[ParrotRequest, Response](this) {
        val transport = new DumbTransport
        //val recordProcessor = new SimpleRecordProcessor(service, self)
        val recordProcessor = new LoadTestStub(service)
      }.asInstanceOf[ParrotConfig[ParrotRequest, Object]]
      def main() {
        parrotServer.start()
      }
    }
  }

  class TestServerApp extends App with ParrotServerFlags with Logging {
    lazy val parrotServer = new ParrotServer(this)

    def main() {
      parrotServer.start()
    }

    def address =
      "localhost:" +
        thriftServer.server.boundAddress.asInstanceOf[InetSocketAddress].getPort.toString

    def runWithFlags(flags: Seq[String]): TestServerApp = {
      nonExitingMain(flags.toArray)
      this
    }

    def runAndGet(): TestServerApp =
      runWithFlags(
        Array(
          "-log.level=ALL",
          "-config=com.twitter.iago.integration.TestParrotConfig",
          "-service.port=:*",
          "-requestTimeout=500.milliseconds",
          "-victimHostPort=" + testVictim
        )
      )

    def run(): Unit = { runAndGet() }
  }

  object TestServerApp {
    def apply(): TestServerApp = { new TestServerApp }.runAndGet()
    def withFlags(flags: Seq[String]): TestServerApp = { new TestServerApp }.runWithFlags(flags)
  }
}
