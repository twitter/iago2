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
package com.twitter.iago.util

import java.net.{InetAddress, InetSocketAddress, Socket}
import java.util.concurrent.{CountDownLatch, TimeUnit}
import com.google.common.collect.ImmutableSet
import com.twitter.app.GlobalFlag
import com.twitter.common.net.pool.DynamicHostSet.HostChangeMonitor
import com.twitter.common.quantity.{Amount, Time}
import com.twitter.common.zookeeper.ServerSet.EndpointStatus
import com.twitter.common.zookeeper.{ServerSetImpl, ZooKeeperClient}
import com.twitter.finagle.util.{DefaultTimer, InetSocketAddressUtil}
import com.twitter.logging.{Level, Logger}
import com.twitter.iago.ParrotFlags
import com.twitter.thrift.{Endpoint, ServiceInstance, Status}
import com.twitter.util.{Await, Duration, Future, FuturePool}
import java.io.IOException
import scala.collection.JavaConverters._
import scala.collection.mutable
import scala.language.postfixOps

trait ParrotCluster {
  def start(port: Int)
  def runningParrots: Set[RemoteParrot]
  def pausedParrots: Set[RemoteParrot]
  def parrots: Set[RemoteParrot]
  def pause()
  def resume()
  def shutdown()
}

class Discovery(
  zkHostName: String,
  zkPort: Int,
  zkNode: String,
  providedClient: Option[ZooKeeperClient] = None
) {
  val log = Logger.get(getClass.getName)

  log.debug("Discovery: zkHostName = %s, zkPort = %s, zkNode = %s", zkHostName, zkPort, zkNode)

  val zkCluster: Array[InetSocketAddress] = zkHostName match {
    case "?" => Array()
    case host =>
      try {
        InetAddress.getAllByName(host).map(new InetSocketAddress(_, zkPort))
      } catch {
        case t: Throwable => {
          log.error(
            "Error getting Zookeeper address. zkHostName = %s, zkPort = %s, zkNode = %s",
            zkHostName,
            zkPort,
            zkNode
          )
          throw (t)
        }
      }
  }

  private[this] lazy val zkClient = providedClient.getOrElse(
    new ZooKeeperClient(Amount.of(1000, Time.MILLISECONDS), zkCluster.toIterable.asJava)
  )
  private[this] lazy val zkServerSet = new ServerSetImpl(zkClient, zkNode)
  private[this] var zkStatus: Option[EndpointStatus] = None

  def join(port: Int, shardId: Int = 0): Discovery = {
    if (connected) {
      zkStatus = Some(
        zkServerSet.join(
          InetSocketAddress.createUnresolved(InetAddress.getLocalHost.getHostName, port),
          Map[String, InetSocketAddress]() asJava,
          shardId
        )
      )
      log.logLazy(Level.DEBUG, "zkStatus is " + zkStatus)
    }
    this
  }

  def monitor(monitor: HostChangeMonitor[ServiceInstance]) = {
    if (connected) {
      zkServerSet.monitor(monitor)
    }
    this
  }

  def shutdown() {
    zkStatus map (_.leave())
    zkClient.close()
  }

  def pause() {}

  def resume() {}

  def connected: Boolean = {
    try {
      log.logLazy(
        Level.DEBUG,
        "providedClient.isEmpty && zkCluster.isEmpty = " +
          providedClient.isEmpty + " && " + zkCluster.isEmpty
      )
      // if a client was provided, we don't care about the zkCluster values.
      // Otherwise, we need to not call get on a lazy client, because ZKClient
      // complains if you give it an empty list of zk servers
      if (providedClient.isEmpty && zkCluster.isEmpty) {
        false
      } else {
        zkClient.get(Amount.of(10, Time.SECONDS))
        true
      }
    } catch {
      case t: Throwable => {
        log.error(t, "Error connecting to Zookeeper: %s", t.getClass.getName)
        false
      }
    }
  }
}

/**
 * This class does Zookeeper discovery and creates RemoteParrots to wire them up.
 */
class ParrotClusterImpl(config: ParrotFlags)
    extends HostChangeMonitor[ServiceInstance]
    with ParrotCluster {
  val log = Logger.get(getClass.getName)
  private[this] val members = new CountDownLatch(1)
  private[this] lazy val discovery =
    new Discovery(config.zkHostNameF(), config.zkPortF(), config.zkNodeF())

  @volatile
  var instances = Set[ServiceInstance]()

  private[this] val _runningParrots = mutable.HashSet[RemoteParrot]()
  private[this] val _pausedParrots = mutable.HashSet[RemoteParrot]()

  def connectParrots(): Seq[RemoteParrot] = {
    log.info("Connecting to Parrots")
    if (config.zkHostNameF() == "?")
      connectParrotsFromConfig
    else
      connectParrotsFromZookeeper
  }

  def runningParrots: Set[RemoteParrot] = _runningParrots.toSet

  def pausedParrots: Set[RemoteParrot] = _pausedParrots.toSet

  def parrots: Set[RemoteParrot] = runningParrots ++ pausedParrots

  /**
   * This isn't efficient for large clusters. We would want to use a different
   * data structure for this, since membership changes would be frequent
   * and scanning the large lists with an O(n2) algorithm would be poor.
   */
  private[this] def updateParrots(hostSet: ImmutableSet[ServiceInstance]) {
    hostSet.asScala.foreach { instance =>
      val endpoint = instance.getServiceEndpoint
      if (instance.getStatus == Status.STOPPING && !isPaused(endpoint)) {
        pauseParrot(endpoint)
      } else if (instance.getStatus == Status.ALIVE && isPaused(endpoint)) {
        resumeParrot(endpoint)
      } else {
        updateParrotMembership(endpoint)
      }
    }
  }

  private[this] def findRunning(endpoint: Endpoint): Option[RemoteParrot] = {
    val endpointAddress = new InetSocketAddress(endpoint.host, endpoint.port)
    _runningParrots.find(p => p.address == endpointAddress)
  }

  private[this] def updateParrotMembership(endpoint: Endpoint) {
    findRunning(endpoint) match {
      case Some(parrot) => {
        if (!isParrotConnected(parrot)) {
          log.info("Removing parrot %s", parrot.address)
          _runningParrots -= parrot
        }
      }
      case None => {
        if (!isPaused(endpoint)) {
          addNewParrot(endpoint)
        }
      }
    }
  }

  private[this] def addNewParrot(endpoint: Endpoint) {
    connectParrot(endpoint.host, endpoint.port) match {
      case Some(parrot) => {
        _runningParrots += parrot
        log.debug(
          "added a new parrot: %s, paused=%d unpaused=%d",
          parrot.address,
          _pausedParrots.size,
          _runningParrots.size
        )
      }
      case None => ()
    }
  }

  private[this] def findPaused(endpoint: Endpoint): Option[RemoteParrot] = {
    val endpointAddress = new InetSocketAddress(endpoint.host, endpoint.port)
    _pausedParrots.find(p => p.address == endpointAddress)
  }

  private[this] def isPaused(endpoint: Endpoint) = findPaused(endpoint).isDefined

  private[this] def resumeParrot(endpoint: Endpoint) {
    for (paused <- findPaused(endpoint)) {
      _pausedParrots -= paused
      _runningParrots += paused
      log.debug(
        "unpaused parrot %s, paused=%d running=%d",
        paused.address,
        _pausedParrots.size,
        _runningParrots.size
      )
    }
  }

  private[this] def pauseParrot(endpoint: Endpoint) {
    for (paused <- findPaused(endpoint)) {
      _runningParrots -= paused
      _pausedParrots += paused
      log.debug(
        "paused parrot %s, paused=%d running=%d",
        paused.address,
        _pausedParrots.size,
        _runningParrots.size
      )
    }
  }

  private[this] def isParrotConnected(parrot: RemoteParrot) = {
    parrot.isConnected
  }

  private[this] def connectParrotsFromZookeeper: List[RemoteParrot] = {
    var result = List[RemoteParrot]()

    discovery.monitor(this)
    waitForMembers(timeout = Duration(10000, TimeUnit.MILLISECONDS))

    instances foreach { instance =>
      val endpoint = instance.getServiceEndpoint
      result = connectParrot(endpoint.host, endpoint.port) match {
        case Some(parrot) => {
          if (instance.getStatus == Status.STOPPING) {
            log.info("Found a paused Parrot on startup: %s:%d", endpoint.host, endpoint.port)
            _pausedParrots += parrot
            result
          } else {
            parrot :: result
          }
        }
        case None => result
      }
    }
    result
  }

  private[this] def connectParrotsFromConfig: Seq[RemoteParrot] =
    InetSocketAddressUtil.parseHosts(config.parrotHostsF()) flatMap { address: InetSocketAddress =>
      log.info("Connecting to parrot server at %s", address)

      try {
        val parrot = new RemoteParrot(
          new InternalCounter(),
          address,
          config.finagleTimeoutF(),
          config.batchSizeF()
        )
        checkParrotServer(address, Duration(30, TimeUnit.SECONDS))
        _runningParrots += parrot
        Some(parrot)
      } catch {
        case t: Throwable =>
          log.error(t, "Error connecting to %s: %s", address, t.getMessage)
          None
      }
    }

  private[this] def checkParrotServer(address: InetSocketAddress, timeout: Duration): Unit = {
    val futurePool = FuturePool.interruptibleUnboundedPool
    val timer = DefaultTimer.twitter

    def isConnectable(address: InetSocketAddress): Boolean = {
      val socket = new Socket()
      try {
        socket.connect(address, 1000)
        true
      } catch { case e: IOException =>
        log.info("Parrot server at %s is not available yet: %s", address, e.getMessage)
        false
      } finally {
        socket.close()
      }
    }

    def checkConnectivity(address: InetSocketAddress): Future[Unit] = {
      futurePool(isConnectable(address))
        .delayed(Duration(1000, TimeUnit.MILLISECONDS))(timer).flatMap {
        case true => Future.Unit
        case _ => checkConnectivity(address)
      }
    }

    Await.result(
      checkConnectivity(address)
        .raiseWithin(
          timer,
          timeout,
          new RuntimeException(s"Timeout waiting for parrot server at $address")
        )
    )
  }

  private[this] def connectParrot(host: String, port: Int): Option[RemoteParrot] = {

    val address = new InetSocketAddress(host, port)
    log.info("Connecting to parrot server at %s", address)

    try Some(
      new RemoteParrot(
        new InternalCounter(),
        address,
        config.finagleTimeoutF(),
        config.batchSizeF()
      )
    )
    catch {
      case t: Throwable =>
        log.error(t, "Error connecting to %s: %s", address, t.getMessage)
        None
    }
  }

  def waitForMembers(timeout: Duration) {
    if (instances.isEmpty) {
      members.await(timeout.inMilliseconds, TimeUnit.MILLISECONDS)
    }
  }

  def onChange(hostSet: ImmutableSet[ServiceInstance]) {
    handleClusterEvent(hostSet)
    members.countDown()
    instances = Set.empty ++ hostSet.asScala
  }

  /**
   * This is here to support testing in isolation.
   * ParrotClusterSpec overrides it in ways that either prevent
   * the side effect of trying to connected to a parrot, or create
   * new side effects that allow for the appearance of synchronous
   * network logic when the calls are really async.
   */
  def handleClusterEvent(set: ImmutableSet[ServiceInstance]) {
    updateParrots(set)
  }

  def start(port: Int) {
    log.logLazy(Level.DEBUG, "starting ParrotClusterImpl")
    discovery.join(port, shardId())
  }

  def shutdown() {
    log.logLazy(Level.DEBUG, "ParrotClusterImpl: shutting down")
    if (config.zkHostNameF() != "?") discovery.shutdown()

    val allParrots = parrots
    allParrots foreach { parrot =>
      try {
        parrot.shutdown()
        log.info("connection to parrot server %s successfully shut down".format(parrot.address))
      } catch {
        case t: Throwable => log.error(t, "Error shutting down Parrot: %s", t.getClass.getName)
      }
    }
    _runningParrots.clear()
    _pausedParrots.clear()
  }

  def pause() {
    discovery.pause()
  }

  def resume() {
    discovery.resume()
  }
}

/** The following definition of shardId is stolen from twitter-internal -- we can't use
 * twitter-internal as this is an open source project. The shard ID is, of course, the identifier
 * of the machine running this program in a server set. */
object shardId extends GlobalFlag(0, "ShardId for use in the serverset")
