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

import com.twitter.common.metrics.Metrics
import com.twitter.conversions.time.intToTimeableNumber
import com.twitter.finagle.RequestTimeoutException
import com.twitter.iago.ParrotConfig
import com.twitter.iago.feeder.FeederFixture
import com.twitter.iago.processor.RecordProcessor
import com.twitter.iago.{HostPortListVictim, ParrotTest}
import com.twitter.util.Await
import com.twitter.util.RandomSocket
import java.nio.charset.{StandardCharsets => Charsets}
import java.net.InetSocketAddress
import java.util.concurrent.Executors
import org.jboss.netty.bootstrap.ConnectionlessBootstrap
import org.jboss.netty.channel.Channel
import org.jboss.netty.channel.ChannelFuture
import org.jboss.netty.channel.ChannelFutureListener
import org.jboss.netty.channel.ChannelHandlerContext
import org.jboss.netty.channel.ChannelPipeline
import org.jboss.netty.channel.ChannelPipelineFactory
import org.jboss.netty.channel.Channels
import org.jboss.netty.channel.MessageEvent
import org.jboss.netty.channel.SimpleChannelUpstreamHandler
import org.jboss.netty.channel.socket.oio.OioDatagramChannelFactory
import org.jboss.netty.handler.codec.string.StringDecoder
import org.jboss.netty.handler.codec.string.StringEncoder
import org.junit.runner.RunWith
import org.scalatest.concurrent.Eventually
import org.scalatest.junit.JUnitRunner
import org.scalatest.OneInstancePerTest
import org.scalatest.time.Millis
import org.scalatest.time.Seconds
import org.scalatest.time.Span
@RunWith(classOf[JUnitRunner])
class ParrotUdpTransportSpec
    extends ParrotTest
    with OneInstancePerTest
    with Eventually
    with FeederFixture
    with ServerFixture {

  import java.net.InetSocketAddress

  "Parrot UDP Transport" should {

    "send requests to an 'echo' service" in {

      val victimPort = RandomSocket.nextPort()
      val serverConfig = makeServerConfig(victimPort)
      val transport = serverConfig.parrot.transport.asInstanceOf[ParrotUdpTransport[String]]

      val victim = new UdpEchoServer(victimPort)
      victim.start()

      val script = List("abc", "is as easy as", "123")
      script.foreach { request =>
        val parrotRequest = new ParrotRequest(rawLine = request)
        val response: String = Await.result(transport.sendRequest(parrotRequest))

        response must be("echo<" + request + ">")
      }

      transport.shutdown()
      victim.stop()
    }

    if (integration) {
      "timeout if the service does not respond quickly enough" in {

        val victimPort = RandomSocket.nextPort()
        val serverConfig = makeServerConfig(victimPort)
        val transport = serverConfig.parrot.transport.asInstanceOf[ParrotUdpTransport[String]]

        val victim = new UdpEchoServer(victimPort, true)
        victim.start()

        val parrotRequest = new ParrotRequest(rawLine = "data")
        val map = Metrics.root.sample()
        val startCount = Metrics.root.sample().get("udp_transport/udp_request_timeout").longValue()
        intercept[RequestTimeoutException] {
          Await.result(transport.sendRequest(parrotRequest), 1.minute)
        }
        (Metrics.root
          .sample()
          .get("udp_transport/udp_request_timeout")
          .longValue() - startCount) must be(1L)

        transport.shutdown()
        victim.stop()
      }
    }

    if (integration) {
      "work in the context of feeder and server" in {
        val victimPort = RandomSocket.nextPort()
        val serverConfig = new TestServerApp {
          override lazy val victim = HostPortListVictim(":" + victimPort)
          override lazy val parrot = new ParrotConfig[ParrotRequest, String](this) {
            val transport =
              new TestParrotUdpTransport(new InetSocketAddress("localhost", victimPort))
            val recordProcessor = new RecordProcessor {
              override def processLine(line: String) {
                Some(service(new ParrotRequest(None, Nil, null, line)))
              }
            }
          }.asInstanceOf[ParrotConfig[ParrotRequest, Object]]
          val transport = parrot.transport.asInstanceOf[TestParrotUdpTransport]
        }

        val victim = new UdpEchoServer(victimPort)
        victim.start()

        val requestStrings = List("a", "square peg", "cannot fit into a round hole")

        val port = RandomSocket.nextPort()
        serverConfig.main(
          Array(
            "-requestTimeout=500.milliseconds",
            "-service.port=:" + port,
            "-thriftClientId=parrot"
          )
        )

        val feederConfig = new TestFeederApp(requestStrings)
        feederConfig.main(
          Array("-batchSize=1", "-cachedSeconds=1", "-parrotHosts=:" + port, "-requestRate=1000")
        )

        try {
          eventually(timeout(Span(5, Seconds)), interval(Span(500, Millis))) {
            val requests = serverConfig.transport.allRequests.get
            requests must be(requestStrings.size)
          }
        } finally {
          // The Feeder will shut down the Server for us
          if (!feederConfig.feeder.done.isDefined) { feederConfig.feeder.shutdown() }
        }
      }
    }
  }

  class TestParrotUdpTransport(victim: InetSocketAddress)
      extends ParrotUdpTransport[String](victim) {
    requestTimeout = Some(100.milliseconds)
    val requestEncoder = Some(new StringEncoder(Charsets.UTF_8))
    val responseDecoder = Some(new StringDecoder(Charsets.UTF_8))
  }

  def makeServerConfig(victimPort: Int): TestServerApp = {
    new TestServerApp {
      override lazy val victim = HostPortListVictim(":" + victimPort)
      override lazy val parrot = new ParrotConfig[ParrotRequest, String](this) {
        val transport = new TestParrotUdpTransport(new InetSocketAddress("localhost", victimPort))
        val recordProcessor = new RecordProcessor {
          override def processLine(line: String): Unit = ()
        }
      }.asInstanceOf[ParrotConfig[ParrotRequest, Object]]
    }
  }
}

class UdpEchoHandler(val blackHole: Boolean) extends SimpleChannelUpstreamHandler {
  override def messageReceived(ctx: ChannelHandlerContext, e: MessageEvent) {
    val input = e.getMessage.asInstanceOf[String]
    if (!blackHole) {
      // NIO: this will fail with NioDatagramChannelFactory (see NIO below)
      val writeFuture = e.getChannel.write("echo<" + input + ">", e.getRemoteAddress)
      writeFuture.addListener(new ChannelFutureListener {
        def operationComplete(f: ChannelFuture) {
          if (!f.isSuccess) {
            println("response write failed: " + f.getCause.toString)
            f.getCause.printStackTrace
          }
        }
      })
    }
  }
}

class UdpEchoServer(val port: Int, val blackHole: Boolean = false) {
  // NIO: Netty 3.4.0.Alpha1 NioDatagramChannelFactory seems broken (see NIO above)
  val factory = new OioDatagramChannelFactory(Executors.newCachedThreadPool());

  @volatile var channel: Option[Channel] = None

  def start() {
    val bootstrap = new ConnectionlessBootstrap(factory);

    // Configure the pipeline factory.
    bootstrap.setPipelineFactory(new ChannelPipelineFactory() {
      override def getPipeline(): ChannelPipeline = {
        Channels.pipeline(
          new StringEncoder(Charsets.UTF_8),
          new StringDecoder(Charsets.UTF_8),
          new UdpEchoHandler(blackHole)
        )
      }
    });

    channel = Some(bootstrap.bind(new InetSocketAddress(port)))
  }

  def stop() {
    channel.foreach {
      _.close()
    }
    factory.releaseExternalResources()
  }
}
