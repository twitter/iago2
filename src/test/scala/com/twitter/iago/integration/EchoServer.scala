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

import org.apache.thrift.protocol.TBinaryProtocol
import java.net.InetSocketAddress
import com.twitter.finagle.{ListeningServer, ThriftMux}
import com.twitter.util.{Await, Future}
import com.twitter.logging.Logger
import com.twitter.iago.thriftscala.EchoService
import com.twitter.iago.thriftscala.EchoService.FutureIface
import java.util.concurrent.atomic.AtomicInteger

object EchoServer {
  val log = Logger.get(getClass)
  val requestCount = new AtomicInteger(0)
  var server: ListeningServer = null

  def serve() {
    // Implement the Thrift Interface
    val processor = new FutureIface {
      def echo(message: String) = {
        log.info("echoing message: %s", message)
        requestCount.incrementAndGet
        Future.value(message)
      }
    }

    // Convert the Thrift Processor to a Finagle Service
    val service = new EchoService.FinagledService(processor, new TBinaryProtocol.Factory())

    server = ThriftMux.server
      .withLabel("thriftserver")
      .serve(new InetSocketAddress(0), service)
  }

  def address =
    "localhost:" +
      server.boundAddress.asInstanceOf[InetSocketAddress].getPort.toString

  def close = Await.result(server.close())

  def getRequestCount = requestCount.get
}
