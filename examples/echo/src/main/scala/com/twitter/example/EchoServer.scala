package com.twitter.example

import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicInteger
import org.apache.thrift.protocol.TBinaryProtocol
import com.twitter.finagle.ThriftMux
import com.twitter.logging.Logger
import com.twitter.util.{Await, Future}
import thrift.EchoService

object EchoServer {
  var port = 8081
  val log = Logger.get(getClass)
  val requestCount = new AtomicInteger(0)

  def main(args: Array[String]) {
    args foreach { arg =>
      val splits = arg.split("=")
      if (splits(0) == "thriftPort") {
        port = splits(1).toInt
      }
    }
    serve(port)
  }

  def serve(port: Int) {
    // Implement the Thrift Interface
    val processor = new EchoService.FutureIface {
      def echo(message: String) = {
        log.info("echoing message: %s", message)
        requestCount.incrementAndGet
        Future.value(message)
      }
    }

    // Convert the Thrift Processor to a Finagle Service
    val service = new EchoService.FinagledService(processor, new TBinaryProtocol.Factory())

    val address = new InetSocketAddress(port)

    val server = ThriftMux.server
      .withLabel("echo-server")
      .serve(address, service)

    Await.result(server)
  }

  def getRequestCount = requestCount.get
}
