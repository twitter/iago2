/* Copyright 2014 Twitter, Inc.

Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in
compliance with the License.  You may obtain a copy of the License at

http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software distributed under the License is
distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or
implied.  See the License for the specific language governing permissions and limitations under the
License.
 */

package com.twitter.iago

import java.util.concurrent.{Executors, TimeUnit}

import com.twitter.app.{App, FlagUsageError, Flaggable}
import com.twitter.conversions.time._
import com.twitter.finagle.builder.ClientBuilder
import com.twitter.finagle.client.DefaultPool
import com.twitter.finagle.http.Response
import com.twitter.finagle.service.ExpiringService
import com.twitter.finagle.stats.{DefaultStatsReceiver, StatsReceiver}
import com.twitter.finagle.zipkin.thrift.ZipkinTracer
import com.twitter.iago.feeder.LogSource
import com.twitter.iago.processor.{RecordProcessor, SimpleRecordProcessor}
import com.twitter.iago.server._
import com.twitter.iago.util._
import com.twitter.server.Stats
import com.twitter.util.{Duration, FuturePool}
import org.apache.thrift.protocol.TProtocolFactory

import scala.collection.{mutable, parallel}

/** Configuration for the Parrot server. To understand its parts, it helps to think about how they
 * relate to the internal Parrot server output queue: the RequestQueue. The log is sent to the
 * server, where it is translated to ParrotRequests and placed on the RequestQueue. The messages
 * are finally sent to the victim from the RequestQueue via the transport.
 *
 * This is meant to be Java compatible which is why it's a class rather than a trait. */
abstract class ParrotConfig[Req <: ParrotRequest, Rep](val config: ParrotServerFlags) {

  val statsReceiver: StatsReceiver = DefaultStatsReceiver.scope("iago")

  val futurePool: FuturePool =
    FuturePool.interruptible(Executors.newFixedThreadPool(parallel.availableProcessors))

  /** ParrotTransport is a subclass of com.twitter.finagle.Service. It is how requests are sent to
   * the victim from the RequestQueue. */
  val transport: ParrotTransport[Req, Rep]

  /** The request distribution over time: the *when* in when we take a message off of the
   * RequestQueue and send it to the victim. */
  def distribution(rate: Int): RequestDistribution = new PoissonProcess(rate)

  lazy val queue =
    new RequestQueue(
      consumer = new RequestConsumer(distribution, transport, futurePool, statsReceiver),
      transport = transport,
      statsReceiver = statsReceiver
    )

  /** ParrotService is a subclass of com.twitter.finagle.Service. It is how Parrot requests go from
   * your record processor to the RequestQueue. */
  lazy val service: ParrotService[Req, Rep] = transport.createService(queue)

  /** The record processor is how the log lines sent from the Parrot feeder are transformed into a
   * ParrotRequest before being placed on the RequestQueue. */
  val recordProcessor: RecordProcessor
}

/** A configuration using Finagle that is easy to extend for most use cases. **/
abstract class FinagleParrotConfig(config: ParrotServerFlags)
    extends ParrotConfig[ParrotRequest, Response](config) {
  val transport = FinagleTransportFactory(config)
}

/** A configuration using Finagle that is easy to extend for most use cases. **/
abstract class ThriftParrotConfig(config: ParrotServerFlags, useThriftMux: Boolean = true)
    extends ParrotConfig[ParrotRequest, Array[Byte]](config) {
  val transport = ThriftTransportFactory(config, useThriftMux)
}

/** The default Parrot configuration is good for load testing a web application. */
class DefaultParrotConfig(config: ParrotServerFlags) extends FinagleParrotConfig(config) {
  val recordProcessor = new SimpleRecordProcessor(service, config)
}

/** ParrotFlags are flags that both the Parrot feeder and the Parrot server need to know about */
trait ParrotFlags {
  self: App =>
  val zkHostNameF = flag("zkHostName", "?", "Zookeeper host Iago will register its servers with")
  val zkPortF = flag("zkPort", 2181, "Zookeeper port Iago will register its servers with")
  val zkNodeF = flag(
    "zkNode",
    "/example/service/parrot/disco",
    "Zookeeper node Iago will register its servers at"
  )
  val finagleTimeoutF = flag(
    "finagleTimeout",
    5.seconds,
    "How long the Iago Feeder will wait for a response from a Iago Server"
  )
  val parrotHostsF = flag(
    "parrotHosts",
    ":*",
    "A comma delimited string of hostname:port pairs. This is used when zkHostName " +
      "is \"?\": when we're not using zookeeper"
  )
  val jobNameF = flag("jobName", "parrot", "name of your test")
  var batchSizeF = flag(
    "batchSize",
    1000,
    "How many messages the parrot feeder sends at one time to the parrot server"
  )
}

trait ParrotFeederFlags extends ParrotFlags {
  self: App =>
  flag.setCmdUsage("Parrot Feeder")
  private[this] val cachedSecondsDefault = 5
  val cachedSecondsF = flag(
    "cachedSeconds",
    cachedSecondsDefault,
    "How many seconds worth of data we hold in the server request queue"
  )
  val cutoffF = flag(
    "cutoff",
    cachedSecondsDefault * 1.2,
    "How many seconds the feeder waits for the server request queue to empty"
  )
  val durationF = flag("duration", Duration.Bottom, "How long to run the test")
  private[this] val inputLogF = flag[String]("inputLog", "Input log")
  val linesToSkipF = flag("linesToSkip", 0, "Skip this many lines after opening the log")
  val maxRequestsF =
    flag("maxRequests", Int.MaxValue, "Total number of requests to submit to your service")
  var pollInterval = Duration(1, TimeUnit.SECONDS)
  val requestRateF =
    flag("requestRate", 1, "Number of requests per second to submit to your service")
  var reuseFileF = flag("reuseFile", false, "true => reread file, false => stop at end of log")
  val numInstancesF =
    flag("numInstances", 1, "Number of Iago servers concurrently making requests to your service")
  private[this] val logSourceF = flag(
    "logSource",
    "com.twitter.iago.feeder.LogSourceImpl",
    "Class used by the feeder to consume the input log, default is com.twitter.iago.feeder.LogSourceImpl"
  )
  lazy val logSource = Class
    .forName(logSourceF())
    .getConstructor(classOf[String])
    .newInstance(inputLogF())
    .asInstanceOf[LogSource]
}

sealed case class TransportScheme(val name: String) {
  override def toString = name
  TransportScheme.map.put(name, this)
}

object TransportScheme {
  private[iago] val map = mutable.Map.empty[String, TransportScheme]

  val HTTP = new TransportScheme("http")
  val HTTPS = new TransportScheme("https")
  val THRIFT = new TransportScheme("thrift")
  val THRIFTS = new TransportScheme("thrifts")

  def unapply(s: String): Option[TransportScheme] = map.get(s)
}

sealed abstract class Victim
case class HostPortListVictim(hostnamePortCombinations: String) extends Victim
case class ServerSetVictim(cluster: String) extends Victim
case class FinagleDestVictim(dest: String) extends Victim

object ServerSetVictim {
  def apply(
    path: String,
    zk: String,
    zkPort: Int
  ): ServerSetVictim =
    ServerSetVictim(s"zk!$zk:$zkPort!/$path")
}

trait ParrotServerFlags extends ParrotFlags with Stats {
  self: App =>

  flag.setCmdUsage("Parrot Server")

  private[this] val victimDestF = flag[String]("victimDest", "a Finagle destination name")
  private[this] val victimServerSetF = flag[String]("victimServerSet", "a server set name")
  private[this] val victimHostPortF = flag[String](
    "victimHostPort",
    "A space or comma separated list of host:port pairs we are to load test. Omit the host to get" +
      " localhost."
  )
  private[this] val victimZkF = flag(
    "victimZk",
    "zookeeper.example.com",
    "The host name of the zookeeper where your serverset is registered"
  )
  private[this] val victimZkPortF =
    flag("victimZkPort", 2181, "The port of the zookeeper where your serverset is registered")

  lazy val victim =
    if (victimDestF.isDefined) FinagleDestVictim(victimDestF())
    else if (victimHostPortF.isDefined) HostPortListVictim(victimHostPortF())
    else if (victimServerSetF.isDefined)
      ServerSetVictim(victimServerSetF(), victimZkF(), victimZkPortF())
    else
      throw new FlagUsageError(
        "Must specify a victim. One of victimDest, victimServerSet, or victimHostPort"
      )

  implicit val ofTransportScheme =
    Flaggable.mandatory[TransportScheme] {
      case TransportScheme(scheme) => scheme
      case _ =>
        val schemes = TransportScheme.map.keys.mkString(", ")
        throw new IllegalArgumentException(s"transportScheme must be one of: $schemes")
    }
  private[this] val transportSchemeF = flag[TransportScheme](
    "transportScheme",
    TransportScheme.HTTP,
    "Scheme portion of a URI: http, https, thrift, or thrifts"
  )
  lazy val transportScheme = transportSchemeF()
  val servicePortF = flag("service.port", ":9991", "Thrift service port")
  lazy val thriftServer = new ThriftServerImpl

  private[this] val parrotF = flag[String](
    "config",
    "The class name of a concrete ParrotConfig to use for the load test." +
      "Use com.twitter.iago.DefaultParrotConfig if hitting webapp endpoints."
  )

  lazy val parrot = Class
    .forName(parrotF())
    .getConstructor(classOf[ParrotServerFlags])
    .newInstance(this)
    .asInstanceOf[ParrotConfig[ParrotRequest, Object]]

  val httpHostHeaderF = flag("httpHostHeader", "", "HTTP Host header")
  val httpHostHeaderPortF = flag("httpHostHeaderPort", 80, "Default port for the HTTP Host header")
  private[this] val hostConnectionCoresizeF = flag(
    "hostConnectionCoresize",
    1,
    "Number of connections per host that will be kept open, once established, " +
      "until they hit max idle time or max lifetime"
  )
  private[this] val hostConnectionIdleTimeF = flag(
    "hostConnectionIdleTime",
    5.seconds,
    "For any connection > coreSize, the maximum amount of time between requests we allow before " +
      "shutting down the connection"
  )
  private[this] val hostConnectionLimitF =
    flag("hostConnectionLimit", Integer.MAX_VALUE, "Limit on the number of connections per host")
  private[this] val hostConnectionMaxIdleTimeF = flag(
    "hostConnectionMaxIdleTime",
    5.seconds,
    "The maximum time that any connection (including within core size) can stay idle " +
      "before shutdown"
  )

  private[this] val connectTimeoutF = flag(
    "connectTimeout",
    5.seconds,
    "The timeout period within which a Finagle transport may establish a connection to the victim"
  )

  private[this] val hostConnectionMaxLifeTimeF = flag(
    "hostConnectionMaxLifeTime",
    Duration.Top,
    "The maximum time that a connection will be kept open"
  )
  private[this] val requestTimeoutF = flag(
    "requestTimeout",
    30.seconds,
    "The maximum interval between the dispatch of the request and the receipt of the response"
  )
  lazy val requestTimeout = requestTimeoutF()
  val thriftClientIdF =
    flag("thriftClientId", "", "If you are making Thrift requests, your clientId")
  private[this] val thriftProtocolFactoryF = flag(
    "thriftProtocolFactory",
    "org.apache.thrift.protocol.TBinaryProtocol$Factory",
    "Thrift protocol factory"
  )

  lazy val thriftProtocolFactory =
    Class
      .forName(thriftProtocolFactoryF())
      .newInstance()
      .asInstanceOf[TProtocolFactory]

  val parrotClientStatsReceiver = statsReceiver.scope("parrot_client")

  val includeParrotHeaderF =
    flag("includeParrotHeader", true, "add a X-Parrot=true header when using FinagleTransport")

  def parrotClientBuilder = {
    val cb = ClientBuilder()
      .daemon(true)
      .hostConnectionCoresize(hostConnectionCoresizeF())
      .hostConnectionLimit(hostConnectionLimitF())
      .requestTimeout(requestTimeoutF())
      .connectTimeout(connectTimeoutF())
      .keepAlive(true)
      .reportTo(parrotClientStatsReceiver)
      .tracer(ZipkinTracer.mk(statsReceiver))
      .noFailureAccrual

    val defaultPool = cb.params[DefaultPool.Param].copy(idleTime = hostConnectionIdleTimeF())

    val expiringSvc = cb
      .params[ExpiringService.Param]
      .copy(idleTime = hostConnectionMaxIdleTimeF(), lifeTime = hostConnectionMaxLifeTimeF())
    cb.configured(defaultPool).configured(expiringSvc)
  }
}
