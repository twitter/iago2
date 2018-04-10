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

import com.twitter.finagle.{CodecFactory, Service}
import com.twitter.finagle.client.StackBasedClient
import com.twitter.iago.{FinagleDestVictim, ServerSetVictim, HostPortListVictim, ParrotServerFlags}
import com.twitter.util.Time
import com.twitter.util.{Promise, Future}

trait MemcacheLikeCommandExtractor[T] {
  def unapply(rawCommand: String): Option[T]
}

abstract class MemcacheLikeTransportFactory[Req, Rep]
    extends ParrotTransportFactory[ParrotRequest, Rep] {

  protected def stack(): StackBasedClient[Req, Rep]

  protected def fromService(service: Service[Req, Rep]): MemcacheLikeTransport[Req, Rep]

  def apply(config: ParrotServerFlags) = {

    val builder = config.parrotClientBuilder.stack(stack)

    val builder2 = {
      config.victim match {
        case HostPortListVictim(victims) => builder.hosts(victims)
        case ServerSetVictim(cluster) => builder.dest(cluster)
        case FinagleDestVictim(dest) => builder.dest(dest)
      }
    }

    fromService(new RefcountedService(builder2.build))
  }
}

class MemcacheLikeTransport[Req, Rep](
  commandExtractor: MemcacheLikeCommandExtractor[Req],
  service: Service[Req, Rep]
) extends ParrotTransport[ParrotRequest, Rep] {

  override def sendRequest(request: ParrotRequest): Future[Rep] = {

    val command = request.rawLine match {
      case commandExtractor(command) => command
      case _ =>
        throw new IllegalArgumentException("could not parse command {%s}".format(request.rawLine))
    }

    log.debug("sending request: %s", command)

    try {
      val result = service(command)
      val response = request.response.asInstanceOf[Promise[Rep]]
      result proxyTo response
      result
    } catch {
      case e: Throwable =>
        log.error(e, "error executing request %s", command)
        throw e
    }
  }

  override def close(deadline: Time): Future[Unit] = service.close(deadline)
}
