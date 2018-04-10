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

import com.twitter.finagle.thrift.{ClientId, ThriftClientRequest}
import com.twitter.finagle.{Service, Thrift, ThriftMux}
import com.twitter.iago._
import com.twitter.util.{Future, Promise, Time}

object ThriftTransportFactory extends ParrotTransportFactory[ParrotRequest, Array[Byte]] {
  def thriftmux(config: ParrotServerFlags) =
    apply(config, true)

  def apply(config: ParrotServerFlags) =
    apply(config, false)

  def apply(config: ParrotServerFlags, useThriftMux: Boolean) = {
    val thriftClientId =
      config.thriftClientIdF() match {
        case "" => None
        case id => Some(ClientId(id))
      }

    val thriftProtocolFactory = config.thriftProtocolFactory
    val builder = if (useThriftMux) {
      val client = ThriftMux.client
        .withProtocolFactory(thriftProtocolFactory)
        .configured(Thrift.param.ClientId(thriftClientId))
      config.parrotClientBuilder.stack(client)
    } else {
      val client = Thrift.client
        .withProtocolFactory(thriftProtocolFactory)
        .configured(Thrift.param.ClientId(thriftClientId))
      config.parrotClientBuilder.stack(client)
    }

    val builder2 = {
      if (config.transportScheme == TransportScheme.THRIFTS) builder.tlsWithoutValidation()
      else builder
    }

    val builder3 = {
      config.victim match {
        case HostPortListVictim(victims) => builder2.hosts(victims)
        case ServerSetVictim(cluster) => builder2.dest(cluster)
        case FinagleDestVictim(dest) => builder2.dest(dest)
      }
    }

    new ThriftTransport(new RefcountedService(builder3.build()))
  }
}

class ThriftTransport(service: Service[ThriftClientRequest, Array[Byte]])
    extends ParrotTransport[ParrotRequest, Array[Byte]] {

  override def createService(queue: RequestQueue[ParrotRequest, Array[Byte]]) =
    new ParrotThriftService(queue)
  override def close(deadline: Time): Future[Unit] = service.close(deadline)
  override def stats(response: Array[Byte]): Option[Seq[String]] = Some(List("thrift_success"))
  override def sendRequest(request: ParrotRequest): Future[Array[Byte]] = {
    val result = service(request.message)
    val response = request.response.asInstanceOf[Promise[Array[Byte]]]
    result proxyTo response
    result
  }
}
