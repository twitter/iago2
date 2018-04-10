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

import com.twitter.iago.integration.HttpServer
import com.twitter.iago.processor.TestRecordProcessor
import com.twitter.iago.{FinagleParrotConfig, ParrotServerFlags, ParrotTest}
import com.twitter.util.Await
import org.junit.runner.RunWith
import org.scalatest.OneInstancePerTest
import org.scalatest.junit.JUnitRunner
import scala.language.reflectiveCalls

@RunWith(classOf[JUnitRunner])
class FinagleTransportSpec extends ParrotTest with OneInstancePerTest with ServerFixture {
  var server: TestServerApp = _

  override def beforeEach() {
    super.beforeEach()
    server = new TestServerApp
    server.runWithFlags(
      Array(
        "-log.level=ALL",
        "-config=com.twitter.iago.server.FinagleTestParrotConfig",
        "-service.port=:*",
        "-requestTimeout=500.milliseconds",
        "-victimHostPort=" + testVictim
      )
    )
  }

  override def afterEach() {
    try {
      super.afterEach()
    } finally {
      server.close()
    }
  }

  "FinagleTransport" should {

    //XXX: Disabled due to com.twitter.finagle.ChannelWriteException: java.net.BindException: Can't assign requested address
    //     on client creation
    if (!sys.props.contains("SKIP_FLAKY")) {
      "allow us to send http requests to web servers" ignore {
        val transport = server.parrot.transport.asInstanceOf[FinagleTransport]
        val request = new ParrotRequest
        val future = transport.sendRequest(request)
        Await.result(future) must not be null
        HttpServer.getAndResetRequests() must be(1)
      }
    }

    // TODO: Add an SSL HTTP server so we can catch problems there
  }
}

class FinagleTestParrotConfig(config: ParrotServerFlags)
    extends FinagleParrotConfig(config) {
  val recordProcessor = new TestRecordProcessor(service, config)
}
