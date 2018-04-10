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

import java.net.SocketAddress
import com.twitter.finagle.{ListeningServer, Memcached}
import com.twitter.finagle.memcached.util.AtomicMap
import com.twitter.finagle.memcached.{Entry, Interpreter, InterpreterService}
import com.twitter.io.Buf
import com.twitter.util.{Await, SynchronizedLruMap}

class InProcessMemcached(address: SocketAddress) {
  val concurrencyLevel = 16
  val slots = 500000
  val slotsPerLru = slots / concurrencyLevel
  val maps = (0 until concurrencyLevel) map { i =>
    new SynchronizedLruMap[Buf, Entry](slotsPerLru)
  }

  private[this] val service = {
    val interpreter = new Interpreter(new AtomicMap(maps))
    new InterpreterService(interpreter)
  }

  private[this] val serverSpec = Memcached.server.withLabel("finagle")

  private[this] var server: Option[ListeningServer] = None

  def start(): ListeningServer = {
    server = Some(serverSpec.serve(address, service))
    server.get
  }

  def stop(blocking: Boolean = false) {
    server.foreach { server =>
      if (blocking) Await.result(server.close())
      else server.close()
      this.server = None
    }
  }
}
