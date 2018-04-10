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
import java.net.InetSocketAddress

import com.twitter.iago.integration.InProcessMemcached
import com.twitter.finagle.memcached.protocol.{Get, Gets, Response, Set, Stored, Value, Values}
import com.twitter.iago._
import com.twitter.iago.feeder.FeederFixture
import com.twitter.iago.processor.RecordProcessor
import com.twitter.io.Buf
import com.twitter.util.{Await, Time}
import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner

import scala.language.{existentials, implicitConversions, reflectiveCalls}
@RunWith(classOf[JUnitRunner])
class MemcacheTransportSpec extends ParrotTest with ServerFixture with FeederFixture {
  implicit def stringToBuf(s: String) = Buf.Utf8(s)

  if (integration) {
    "Memcache Transport" should {

      "send requests to a Memcache service" in {
        val mserver = new InProcessMemcached(new InetSocketAddress(0))
        val bs = mserver.start()

        val server = new TestServerApp {
          override lazy val parrot = new ParrotConfig[ParrotRequest, Response](this) {
            val transport = MemcacheTransportFactory(config)
            val recordProcessor = new RecordProcessor {
              // never used
              override def processLines(lines: Seq[String]) {}
            }
          }.asInstanceOf[ParrotConfig[ParrotRequest, Object]]
          def transport = parrot.transport
        }
        val hp = "localhost:" + bs.boundAddress.asInstanceOf[InetSocketAddress].getPort.toString
        server.nonExitingMain(
          Array("-victimHostPort=" + hp, "-service.port=:0", "-requestTimeout=500.milliseconds")
        )
        val transport = server.transport.asInstanceOf[MemcacheTransport]

        val script = List[(String, Response)](
          "set mah-key 0 0 8\r\nDEADBEEF" -> Stored(),
          "get mah-key\r\n" -> Values(List(Value("mah-key", "DEADBEEF", None, Some("0"))))
        )
        script.foreach {
          case (rawCommand, expectedResponse) =>
            val request = new ParrotRequest(rawLine = rawCommand)
            val resp: Response = Await.result(transport.sendRequest(request))
            resp must be(expectedResponse)
        }
        mserver.stop()
      }
    }

    "MemcacheCommandExtractor" should {
      "parse GET commands" in {
        MemcacheCommandExtractor.unapply("GET FOO\r\n") must be(Some(Get(Seq("FOO"))))
        MemcacheCommandExtractor.unapply("get foo\r\n") must be(Some(Get(Seq("foo"))))
        MemcacheCommandExtractor.unapply("get foo") must be(Some(Get(Seq("foo"))))
        MemcacheCommandExtractor.unapply("get  foo  \r\n") must be(Some(Get(Seq("foo"))))

        MemcacheCommandExtractor.unapply("get foo bar\r\n") must be(Some(Get(Seq("foo", "bar"))))
        MemcacheCommandExtractor.unapply("get foo bar") must be(Some(Get(Seq("foo", "bar"))))
        MemcacheCommandExtractor.unapply("get  foo  bar  \r\n") must be(
          Some(Get(Seq("foo", "bar")))
        )

        MemcacheCommandExtractor.unapply("get") must be(None)
        MemcacheCommandExtractor.unapply("get ") must be(None)
      }

      "parse GETS commands" in {
        MemcacheCommandExtractor.unapply("GETS FOO\r\n") must be(Some(Gets(Seq("FOO"))))
        MemcacheCommandExtractor.unapply("gets foo\r\n") must be(Some(Gets(Seq("foo"))))
        MemcacheCommandExtractor.unapply("gets foo") must be(Some(Gets(Seq("foo"))))
        MemcacheCommandExtractor.unapply("gets  foo  \r\n") must be(Some(Gets(Seq("foo"))))

        MemcacheCommandExtractor.unapply("gets FOO BAR\r\n") must be(Some(Gets(Seq("FOO", "BAR"))))
        MemcacheCommandExtractor.unapply("gets foo bar\r\n") must be(Some(Gets(Seq("foo", "bar"))))
        MemcacheCommandExtractor.unapply("gets foo bar") must be(Some(Gets(Seq("foo", "bar"))))
        MemcacheCommandExtractor.unapply("gets  foo  bar  \r\n") must be(
          Some(Gets(Seq("foo", "bar")))
        )

        MemcacheCommandExtractor.unapply("gets") must be(None)
        MemcacheCommandExtractor.unapply("gets ") must be(None)
      }

      "parse SET commands" in {
        MemcacheCommandExtractor.unapply("SET FOO 0 0 8\r\n12345678") must
          be(Some(Set("FOO", 0, Time.fromSeconds(0), "12345678")))

        MemcacheCommandExtractor.unapply("set foo 123 100 8\r\n12345678") must
          be(Some(Set("foo", 123, Time.fromSeconds(100), "12345678")))

        MemcacheCommandExtractor.unapply("set foo 123 100 10\r\n1234\r\n5678") must
          be(Some(Set("foo", 123, Time.fromSeconds(100), "1234\r\n5678")))

        MemcacheCommandExtractor.unapply("set foo 0 0 100\r\n12345678") must be(None)
        MemcacheCommandExtractor.unapply("set foo 0 0\r\n1234") must be(None)
      }
    }
  }
}
