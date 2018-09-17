/* Copyright 2015 Twitter, Inc.

Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in
compliance with the License.  You may obtain a copy of the License at

http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software distributed under the License is
distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or
implied.  See the License for the specific language governing permissions and limitations under the
License.
 */
package com.twitter.iago.integration

import com.twitter.finagle.http.{Request, Response, Status, Version}
import com.twitter.finagle.{Http, ListeningServer, Service, SimpleFilter}
import com.twitter.util.{Await, Future}
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicInteger

/**
 * This is a simple HTTP server that simply returns "hello world" and counts
 * how many requests it receives for the purposes of integration testing. It
 * also handles exceptions just in case odd things happen.
 */
class HttpServer {
  val requestCount = new AtomicInteger(0)
  private var server: ListeningServer = _

  class HandleExceptions extends SimpleFilter[Request, Response] {
    def apply(request: Request, service: Service[Request, Response]) = {

      // `handle` asynchronously handles exceptions.
      service(request) handle {
        case error =>
          val statusCode = error match {
            case _: IllegalArgumentException =>
              Status.Forbidden
            case _ =>
              Status.InternalServerError
          }
          val errorResponse = Response(Version.Http11, statusCode)
          errorResponse.contentString = error.getStackTrace.toString

          errorResponse
      }
    }
  }

  /**
   * The service itself. Simply echos back "hello world"
   */
  class Respond extends Service[Request, Response] {
    def apply(request: Request) = {
      val response = new Response.Ok
      response.contentString = "hello world"
      requestCount.incrementAndGet
      Future.value(response)
    }
  }

  def serve(address: InetSocketAddress): ListeningServer = {
    val handleExceptions = new HandleExceptions
    val respond = new Respond

    // compose the Filters and Service together:
    val myService: Service[Request, Response] = handleExceptions andThen respond

    server = Http.server
      .withLabel("httpserver")
      .serve(address, myService)
    server
  }

  def boundAddress: InetSocketAddress = server.boundAddress.asInstanceOf[InetSocketAddress]

  def close(): Unit = {
    Await.result(server.close())
  }

  def getAndResetRequests(): Int = {
    requestCount.getAndSet(0)
  }
}
