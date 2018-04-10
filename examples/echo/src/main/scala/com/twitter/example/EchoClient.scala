package com.twitter.example

import java.net.InetSocketAddress
import org.apache.thrift.protocol.TBinaryProtocol
import com.twitter.finagle.{Address, Name, Service, ThriftMux}
import com.twitter.finagle.thrift.ThriftClientRequest
import thrift.EchoService

object EchoClient {
  def main(args: Array[String]) {
    // Create a raw Thrift client service. This implements the
    // ThriftClientRequest => Future[Array[Byte]] interface.
    val service: Service[ThriftClientRequest, Array[Byte]] = ThriftMux.client
      .newService(
        Name.bound(Address(new InetSocketAddress(EchoServer.port))),
        "echo-service"
      )

    // Wrap the raw Thrift service in a Client decorator. The client
    // provides a convenient procedural interface for accessing the Thrift
    // server.
    val client = new EchoService.FinagledClient(service, new TBinaryProtocol.Factory())

    client.echo("hello") onSuccess { response =>
      println("Received response: " + response)
    } ensure {
      service.close()
    }
  }
}
