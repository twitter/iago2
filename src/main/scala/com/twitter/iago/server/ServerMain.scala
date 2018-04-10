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

import com.twitter.iago.ParrotServerFlags
import com.twitter.server.{Lifecycle, TwitterServer}
import com.twitter.server.logging.{Logging => JDK14Logging}
import com.twitter.util.Await

object Main extends TwitterServer with JDK14Logging with Lifecycle.Warmup with AutoStats with ParrotServerFlags {
  def main() {
    try {
      val parrotServer =
        new ParrotServer(
          config = this,
          futurePool = parrot.futurePool,
          statsReceiver = statsReceiver.scope("iago")
        )
      closeOnExit(parrotServer)
      autostat("parrot_" + jobNameF())
      prebindWarmup()
      warmupComplete()
      log.info("Starting server.")
      parrotServer.start()
      log.info("Server started.")
      Await.ready(parrotServer.done)
    } catch {
      case e: Exception =>
        log.error(e, "Unexpected exception: %s", e.getMessage)
        log.error(flag.usage)
        System.exit(1)
    }
  }

}
