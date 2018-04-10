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

import java.util.concurrent.LinkedBlockingQueue

import org.junit.runner.RunWith
import org.scalatest.BeforeAndAfter
import org.scalatest.OneInstancePerTest
import org.scalatest.{WordSpec, MustMatchers}
import org.scalatest.concurrent.Eventually
import org.scalatest.junit.JUnitRunner

import com.google.common.collect.ImmutableSet
import com.twitter.common.application.ShutdownRegistry
import com.twitter.common.zookeeper.testing.ZooKeeperTestServer
import com.twitter.thrift.ServiceInstance
import com.twitter.iago.{ParrotServerFlags, ParrotFlags, ParrotTest}
import com.twitter.util.RandomSocket
import com.twitter.iago.server.ServerFixture

@RunWith(classOf[JUnitRunner])
class ParrotClusterSpec
    extends ParrotTest
    with Eventually
    with OneInstancePerTest
    with BeforeAndAfter
    with ServerFixture {
  val shutdownRegistry = new ShutdownRegistry.ShutdownRegistryImpl
  val zkTestServer = new ZooKeeperTestServer(0, shutdownRegistry)
  val zkNode = "/twitter/service/parrot/testNode"

  private[this] def ephemeral() = RandomSocket.nextPort()

  zkTestServer.startNetwork()

  /**
   * The Zookeeper libraries provide a way to create a local client directly using the in-memory
   * server. We want to use this method for testing, but it doesn't correspond at all to how the
   * client is created in production. Therefore the odd signature here.
   */
  def createDiscovery = { new Discovery("?", -1, zkNode, Some(zkTestServer.createClient)) }
  val config = new TestServerApp

  if (integration) "Discovery" should {

    after {
      shutdownRegistry.execute()
    }

    "let us join a ZK cluster" in {
      val disco = createDiscovery.join(ephemeral())
      disco.connected must be(true)

      disco.shutdown()
    }

    /* This test is DEPRECATED -- calling get on the client reconnects us */
    "let us leave a ZK cluster" in {
      val disco = createDiscovery.join(ephemeral())
      disco.shutdown()
      disco.connected must be(true) // should be false! calling connected reconnects us

      disco.shutdown() // yes, we just called it. oh well.
    }

    "let us monitor a ZK cluster we're joined to" in {
      val cluster = new ParrotClusterImpl(config) {
        override def handleClusterEvent(set: ImmutableSet[ServiceInstance]) = ()
      }
      val disco = createDiscovery.join(ephemeral()).monitor(cluster)

      cluster.instances.size must be(1)

      disco.shutdown()
    }

    "let us monitor a ZK cluster we're a client of" in {
      val cluster = new ParrotClusterImpl(config) {
        override def handleClusterEvent(set: ImmutableSet[ServiceInstance]) = ()
      }
      val server = createDiscovery.join(ephemeral())
      val client = createDiscovery.monitor(cluster)

      cluster.instances.size must be(1)

      server.shutdown()
      client.shutdown()
    }

    "notice when a member enters" in {
      val queue = new LinkedBlockingQueue[ImmutableSet[ServiceInstance]]
      val cluster = new ParrotClusterImpl(config) {
        override def handleClusterEvent(set: ImmutableSet[ServiceInstance]) {
          queue.offer(set)
        }
      }
      val server1 = createDiscovery.join(ephemeral())
      val client = createDiscovery.monitor(cluster)

      queue.take.size must be(1)

      val server2 = createDiscovery.join(ephemeral())

      queue.take.size must be(2)

      server1.shutdown()
      server2.shutdown()
      client.shutdown()
    }

    "notice when a member leaves" in {
      val queue = new LinkedBlockingQueue[ImmutableSet[ServiceInstance]]
      val cluster = new ParrotClusterImpl(config) {
        override def handleClusterEvent(set: ImmutableSet[ServiceInstance]) {
          queue.offer(set)
        }
      }
      val server = createDiscovery.join(ephemeral())
      val client = createDiscovery.monitor(cluster)

      queue.take.size must be(1)

      server.shutdown()

      queue.take.size must be(0)

      client.shutdown()
    }

    "see members that are joined before us" in {
      val queue = new LinkedBlockingQueue[ImmutableSet[ServiceInstance]]
      val cluster = new ParrotClusterImpl(config) {
        override def handleClusterEvent(set: ImmutableSet[ServiceInstance]) {
          queue.offer(set)
        }
      }
      val server1 = createDiscovery.join(ephemeral())
      val server2 = createDiscovery.join(ephemeral())
      val client = createDiscovery.monitor(cluster)

      queue.take.size must be(2)

      server1.shutdown()
      server2.shutdown()
      client.shutdown()
    }
  }
}
