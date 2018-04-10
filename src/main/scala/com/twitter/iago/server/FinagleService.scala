/*
Copyright 2013 Twitter, Inc.

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

import com.twitter.finagle.Service
import com.twitter.finagle.ServiceFactory
import com.twitter.finagle.http.{Request, Response}
import com.twitter.util.Future
import com.twitter.util.Promise
import com.twitter.util.Time

private[server] sealed abstract class FinagleServiceAbstraction {
  def close(deadline: Time): Future[Unit]
  def send(httpRequest: Request, request: ParrotRequest): Future[Response]
  protected def send(
    httpRequest: Request,
    service: Service[Request, Response],
    request: ParrotRequest
  ): Future[Response] = {
    val result = service(httpRequest)
    val response = request.response.asInstanceOf[Promise[Response]]
    result proxyTo response
    result
  }
}

private[server] case class FinagleService(service: Service[Request, Response])
    extends FinagleServiceAbstraction {
  def close(deadline: Time): Future[Unit] = service.close(deadline)
  def send(httpRequest: Request, request: ParrotRequest): Future[Response] =
    send(httpRequest, service, request)
}

private[server] case class FinagleServiceFactory(factory: ServiceFactory[Request, Response])
    extends FinagleServiceAbstraction {
  def close(deadline: Time): Future[Unit] = factory.close(deadline)
  def send(httpRequest: Request, request: ParrotRequest): Future[Response] =
    send(httpRequest, factory.toService, request)
}
