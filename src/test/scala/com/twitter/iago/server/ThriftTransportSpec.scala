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

import com.twitter.app.App
import com.twitter.finagle.thrift.ThriftClientRequest
import com.twitter.iago.integration.EchoServer
import com.twitter.iago.processor.TestThriftRecordProcessor
import com.twitter.iago.util.ThriftFixture
import com.twitter.iago.{HostPortListVictim, ParrotConfig, ParrotServerFlags, ParrotTest}
import com.twitter.util.Await
import org.junit.runner.RunWith
import org.scalatest.OneInstancePerTest
import org.scalatest.junit.JUnitRunner

import scala.language.existentials

@RunWith(classOf[JUnitRunner])
class ThriftTransportSpec extends ParrotTest with ThriftFixture with OneInstancePerTest {

  "Thrift Transport" should {

    if (integration) "send requests to a Thrift service" in {
      EchoServer.serve()

      val serverConfig = new App with ParrotServerFlags {
        override lazy val victim = HostPortListVictim(EchoServer.address)
        val self = this
        override lazy val parrot = new ParrotConfig[ParrotRequest, Array[Byte]](self) {
          val transport = ThriftTransportFactory(self)
          val recordProcessor = new TestThriftRecordProcessor(service) // not used
        }.asInstanceOf[ParrotConfig[ParrotRequest, Object]]
      }
      serverConfig.nonExitingMain(Array[String]("-service.port=:0"))
      val transport = serverConfig.parrot.transport.asInstanceOf[ThriftTransport]

      val message = new ThriftClientRequest(serialize("echo", "message", "hello"), false)
      val request = new ParrotRequest(message = message)
      val rep = Await.result(transport.sendRequest(request))

      EchoServer.getRequestCount must not be 0
      rep.containsSlice("hello".getBytes) must be(true)
      EchoServer.close
    }
  }
}
