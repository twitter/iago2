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
import com.twitter.finagle.param.Tracer
import com.twitter.finagle.tracing.DefaultTracer
import com.twitter.finagle.{ListeningServer, ThriftMux}
import com.twitter.logging.Logger
import com.twitter.iago.thriftscala.{ParrotState, ParrotStatus}
import java.net.InetSocketAddress

import com.twitter.util.Future
import org.apache.thrift.protocol.TBinaryProtocol

trait ThriftServer {
  def start(server: ParrotServer, address: InetSocketAddress)
  def shutdown(): Future[Unit]
}

class ThriftServerImpl extends ThriftServer {
  private[this] val log = Logger.get(getClass.getName)
  val unknownStatus = ParrotStatus(status = Some(ParrotState.Unknown), linesProcessed = Some(0))

  var server: ListeningServer = null

  def start(parrotServer: ParrotServer, address: InetSocketAddress) {
    try {
      server = ThriftMux.server
        .configured(Tracer(DefaultTracer))
        .withProtocolFactory(new TBinaryProtocol.Factory())
        .serveIface(address, parrotServer)
      log.trace("created an iago server on port %d", address.getPort)
      log.trace("bound address is %s", server.boundAddress.toString)
    } catch {
      case e: Exception =>
        e.printStackTrace()
        log.error(e, "Unexpected exception: %s", e.getMessage)
    }
  }

  override def shutdown(): Future[Unit] = {
    if (server != null) {
      server.close(1.second)
    } else { Future.Unit }
  }
}
