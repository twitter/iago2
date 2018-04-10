package com.twitter.example

import org.apache.thrift.protocol.TBinaryProtocol

import com.twitter.iago.processor.ThriftRecordProcessor
import com.twitter.iago.server.{ParrotRequest, ParrotService}
import com.twitter.logging.Logger

import thrift.EchoService

class EchoLoadTest(parrotService: ParrotService[ParrotRequest, Array[Byte]])
    extends ThriftRecordProcessor(parrotService) {
  val client = new EchoService.FinagledClient(service, new TBinaryProtocol.Factory())
  val log = Logger.get(getClass)

  override def processLine(line: String) {
    client.echo(line) respond { rep =>
      if (rep == "hello") {
        client.echo("OMIGOD IT'S TALKING TO US")
      }
      log.info("response: " + rep)
    }
  }
}
