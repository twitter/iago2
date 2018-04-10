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

import com.twitter.app.App
import com.twitter.conversions.time.intToTimeableNumber
import com.twitter.finagle.kestrel.protocol.{
  Abort,
  Close,
  CloseAndOpen,
  Get,
  Open,
  Peek,
  Response,
  Set,
  Stored,
  Value,
  Values
}
import com.twitter.iago.integration.InProcessMemcached
import com.twitter.iago.processor.RecordProcessor
import com.twitter.iago.{HostPortListVictim, ParrotConfig, ParrotServerFlags, ParrotTest}
import com.twitter.io.Buf
import com.twitter.util.{Await, Time}
import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner

import scala.language.{existentials, implicitConversions}

@RunWith(classOf[JUnitRunner])
class KestrelTransportSpec extends ParrotTest {
  implicit def stringToBuf(s: String) = Buf.Utf8(s)

  "Kestrel Transport" should {

    "send requests to a 'Kestrel' service" in {
      val mserver = new InProcessMemcached(new InetSocketAddress(0))
      val bs = mserver.start()
      val server = new App with ParrotServerFlags {
        val self = this
        override lazy val parrot = new ParrotConfig[ParrotRequest, Response](self) {
          val recordProcessor = new RecordProcessor {
            override def processLines(lines: Seq[String]): Unit = ()
          }
          override lazy val transport = KestrelTransportFactory(self)
        }.asInstanceOf[ParrotConfig[ParrotRequest, Object]]
      }
      val hp = "localhost:" + bs.boundAddress.asInstanceOf[InetSocketAddress].getPort.toString
      server.nonExitingMain(
        Array("-victimHostPort=" + hp, "-service.port=:0", "-requestTimeout=500.milliseconds")
      )
      val transport = server.parrot.transport.asInstanceOf[KestrelTransport]

      val script = List[(String, Response)](
        "set mah-key 0 0 8\r\nDEADBEEF" -> Stored(),
        "set mah-other-key 0 0 8\r\nABADCAFE" -> Stored(),
        "get mah-key\r\n" -> Values(List(Value(Buf.Utf8("mah-key"), Buf.Utf8("DEADBEEF"))))
      )
      script.foreach {
        case (rawCommand, expectedResponse) =>
          val request = new ParrotRequest(rawLine = rawCommand)
          val rep: Response = Await.result(transport.sendRequest(request))
          rep must be(expectedResponse)
      }
      mserver.stop()
    }
  }

  "KestrelCommandExtractor" should {
    "parse GET commands" in {
      KestrelCommandExtractor.unapply("GET FOO\r\n") must be(Some(Get(Buf.Utf8("FOO"), None)))
      KestrelCommandExtractor.unapply("get foo\r\n") must be(Some(Get(Buf.Utf8("foo"), None)))
      KestrelCommandExtractor.unapply("get foo") must be(Some(Get(Buf.Utf8("foo"), None)))
      KestrelCommandExtractor.unapply("get  foo  \r\n") must be(Some(Get(Buf.Utf8("foo"), None)))

      KestrelCommandExtractor.unapply("get foo/t=100\r\n") must be(
        Some(Get(Buf.Utf8("foo"), Some(100.milliseconds)))
      )

      KestrelCommandExtractor.unapply("get foo bar\r\n") must be(None)
      KestrelCommandExtractor.unapply("get") must be(None)
      KestrelCommandExtractor.unapply("get ") must be(None)
    }

    "parse GET command flags" in {
      KestrelCommandExtractor.unapply("get q/abort") must be(Some(Abort(Buf.Utf8("q"), None)))
      KestrelCommandExtractor.unapply("get q/close") must be(Some(Close(Buf.Utf8("q"), None)))
      KestrelCommandExtractor.unapply("get q/open") must be(Some(Open(Buf.Utf8("q"), None)))
      KestrelCommandExtractor.unapply("get q/peek") must be(Some(Peek(Buf.Utf8("q"), None)))
      KestrelCommandExtractor.unapply("get q/close/open") must be(
        Some(CloseAndOpen(Buf.Utf8("q"), None))
      )
      KestrelCommandExtractor.unapply("get q/open/close") must be(
        Some(CloseAndOpen(Buf.Utf8("q"), None))
      )

      val timeout = Some(100.milliseconds)
      KestrelCommandExtractor.unapply("get q/abort/t=100") must be(Some(Abort(Buf.Utf8("q"), None)))
      KestrelCommandExtractor.unapply("get q/close/t=100") must be(Some(Close(Buf.Utf8("q"), None)))
      KestrelCommandExtractor.unapply("get q/open/t=100") must be(
        Some(Open(Buf.Utf8("q"), timeout))
      )
      KestrelCommandExtractor.unapply("get q/peek/t=100") must be(
        Some(Peek(Buf.Utf8("q"), timeout))
      )
      KestrelCommandExtractor.unapply("get q/close/open/t=100") must be(
        Some(CloseAndOpen(Buf.Utf8("q"), timeout))
      )
      KestrelCommandExtractor.unapply("get q/open/close/t=100") must be(
        Some(CloseAndOpen(Buf.Utf8("q"), timeout))
      )

      KestrelCommandExtractor.unapply("get q/say-what-now") must be(None)
    }

    "parse SET commands" in {
      KestrelCommandExtractor.unapply("SET FOO 0 0 8\r\n12345678") must
        be(Some(Set(Buf.Utf8("FOO"), Time.fromSeconds(0), Buf.Utf8("12345678"))))

      KestrelCommandExtractor.unapply("set foo 123 100 8\r\n12345678") must
        be(Some(Set(Buf.Utf8("foo"), Time.fromSeconds(100), Buf.Utf8("12345678"))))

      KestrelCommandExtractor.unapply("set foo 123 100 10\r\n1234\r\n5678") must
        be(Some(Set(Buf.Utf8("foo"), Time.fromSeconds(100), Buf.Utf8("1234\r\n5678"))))

      KestrelCommandExtractor.unapply("set foo 0 0 100\r\n12345678") must be(None)
      KestrelCommandExtractor.unapply("set foo 0 0\r\n1234") must be(None)
    }
  }
}
