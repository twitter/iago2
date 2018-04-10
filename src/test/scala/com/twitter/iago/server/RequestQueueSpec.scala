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

import com.twitter.conversions.time._
import com.twitter.finagle.util.DefaultTimer
import com.twitter.iago.ParrotTest
import com.twitter.logging.Logger
import com.twitter.util.{Await, Future}
import org.junit.runner.RunWith
import org.scalatest.OneInstancePerTest
import org.scalatest.junit.JUnitRunner

@RunWith(classOf[JUnitRunner])
class RequestQueueSpec extends ParrotTest with ServerFixture with OneInstancePerTest {

  val log = Logger.get(getClass.getName)

  val server = dumbTestServer

  val parrot = server.parrot
  val queue = parrot.queue

  if (integration) "RequestQueue" should {
    //XXX: This is not a good test because it'd dependent on real time. Convert to test timers.
    "achieve the specified requestRate when it's in excess of 500rps" in {
      val seconds = 10
      val rate = 666
      val numRequests = seconds * rate * 2

      Await.result(queue.setRate(rate), 1.second)
      Await.result(
        Future.join(for (i <- 1 until numRequests) yield queue.addRequest(new ParrotRequest)._2),
        10.seconds
      )

      Await.result(
        queue.start().before(DefaultTimer.twitter.doLater(seconds.seconds) { queue.shutdown() }),
        timeout = (seconds + 1).seconds
      )

      val transport = parrot.transport.asInstanceOf[DumbTransport]

      transport.sent.toDouble must be >= (seconds * rate * 0.95)
      transport.sent.toDouble must be <= (seconds * rate * 1.05)
    }

    "accurately report queue_depth" in {
      val seconds = 10
      val rate = 100
      val numRequests = seconds * rate

      Await.result(queue.setRate(rate), 1.second)
      Await.result(
        Future.join(for (i <- 0 until numRequests) yield queue.addRequest(new ParrotRequest)._2),
        10.seconds
      )

      log.info("queue depth is %d", queue.queueDepth)
      queue.queueDepth must be(numRequests)
    }
  }
}
