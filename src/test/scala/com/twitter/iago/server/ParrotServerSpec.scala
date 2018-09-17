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
import java.util.concurrent.TimeUnit

import com.twitter.iago.ParrotTest
import com.twitter.iago.thriftscala.{ParrotState, ParrotStatus}
import com.twitter.logging.Logger
import com.twitter.util._
import org.junit.runner.RunWith
import org.scalatest.OneInstancePerTest
import org.scalatest.concurrent.Eventually
import org.scalatest.junit.JUnitRunner

import scala.language.{existentials, implicitConversions, reflectiveCalls}

@RunWith(classOf[JUnitRunner])
class ParrotServerSpec
    extends ParrotTest
    with OneInstancePerTest
    with Eventually
    with ServerFixture {
  val log = Logger.get(getClass.getName)
  val servicePort = { new InetSocketAddress(0) }.getPort.toString

  override def beforeEach(): Unit = {
    super.beforeEach()
    dumbTestServer.nonExitingMain(
      Array[String]("-service.port=:" + servicePort, "-victimHostPort=" + testVictim)
    )
  }

  def parrotServer = dumbTestServer.parrotServer
  def transport = dumbTestServer.parrot.transport.asInstanceOf[DumbTransport]

  "ParrotServer" should {
    val emptyLines = Seq[String]()
    val defaultLines =
      Seq[String]("/search.json?q=%23playerofseason&since_id=68317051210563584&rpp=30")

    def resolve(future: Future[ParrotStatus]): ParrotStatus =
      Await.result(future, Duration(1000, TimeUnit.MILLISECONDS))

    "not try to do anything with an empty list" in {
      val response = resolve(parrotServer.sendRequest(emptyLines))
      response.linesProcessed must be(Some(0))
    }

    "allow us to send a single, valid request" in {
      val response = resolve(parrotServer.sendRequest(defaultLines))
      response.linesProcessed must be(Some(1))
      eventually {
        transport.sent must be(1)
      }
    }
    /*
        "support being paused and resumed" in {
          parrotServer.pause()
          val response = resolve(parrotServer.sendRequest(defaultLines))
          response.status must be(Some(ParrotState.Paused))
          transport.sent must be(0)

          parrotServer.resume()
          eventually(timeout(Span(2, Seconds))) {
            transport.sent must be(1)
          }
        }
     */
    "support being shutdown" in {
      parrotServer.shutdown()

      val response = resolve(parrotServer.sendRequest(defaultLines))
      response.status must be(Some(ParrotState.Shutdown))
      // transport.sent must be(1)
    }
  }
}
