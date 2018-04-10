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

import com.twitter.finagle.{Dtab, Http}
import com.twitter.finagle.http._
import com.twitter.io.Buf
import com.twitter.iago._
import com.twitter.util._
import java.nio.ByteOrder.BIG_ENDIAN
import java.util.concurrent.TimeUnit

object FinagleTransportFactory extends ParrotTransportFactory[ParrotRequest, Response] {
  def apply(config: ParrotServerFlags) = {
    val builder = config.parrotClientBuilder.stack(Http.client).failFast(false)

    val builder2 = {
      if (config.transportScheme == TransportScheme.HTTPS)
        builder.tlsWithoutValidation()
      else builder
    }

    val builder3 = config.victim match {
      case HostPortListVictim(victims) => builder2.hosts(victims)
      case ServerSetVictim(cluster) => builder2.dest(cluster)
      case FinagleDestVictim(dest) => builder2.dest(dest)
    }

    val service = FinagleServiceFactory(builder3.buildFactory())

    new FinagleTransport(service, config.includeParrotHeaderF())
  }
}

class FinagleTransport(service: FinagleServiceAbstraction, includeParrotHeader: Boolean)
    extends ParrotTransport[ParrotRequest, Response] {

  var allRequests = 0
  override def stats(response: Response) = Some(Seq(response.statusCode.toString))

  override def sendRequest(request: ParrotRequest): Future[Response] = {
    val requestMethod = request.method match {
      case "POST" => Method.Post
      case _ => Method.Get
    }
    val httpRequest = if (request.isMultipart) {
      RequestBuilder()
        .url(
          "https://%s:%s%s"
            .format(request.hostHeader.get._1, request.hostHeader.get._2, request.uri.toString)
        )
        .add(elems = request.formElements)
        .buildFormPost(true)
    } else {
      Request(Version.Http11, requestMethod, request.uri.toString)
    }

    httpRequest.uri = request.uri.toString
    request.headers foreach {
      case (key, value) =>
        httpRequest.headerMap.set(key, value)
    }
    httpRequest.headerMap.set("Cookie", request.cookies map {
      case (name, value) => name + "=" + value
    } mkString (";"))
    if (!httpRequest.headerMap.contains("User-Agent")) {
      httpRequest.headerMap.set("User-Agent", "com.twitter.iago")
    }
    if (includeParrotHeader) {
      httpRequest.headerMap.set("X-Parrot", "true")
    }
    httpRequest.headerMap.set("X-Forwarded-For", randomIp)

    if (request.method == "POST" && request.body.length > 0) {
      val buffer = Buf.Utf8(request.body)
      httpRequest.headerMap.set(Fields.ContentLength, buffer.length.toString)
      httpRequest.content = buffer
    }

    allRequests += 1

    log.debug(
      """
===================== HttpRequest ======================
%s / %s
========================================================"""
        .format(httpRequest.toString, httpRequest.headerMap.get("User-Agent"))
    )

    service.send(httpRequest, request)
  }

  override def close(deadline: Time): Future[Unit] = service.close(deadline)
}
