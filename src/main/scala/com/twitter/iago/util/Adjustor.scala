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

import com.twitter.app.App
import com.twitter.logging.Logger
import com.twitter.iago.ParrotFlags

object Main extends App with ParrotFlags {
  val log = Logger.get(getClass)
  val requestRateF =
    flag("requestRate", 1, "number of requests per second to submit to your service")

  def main() {
    val newRate = requestRateF()
    val cluster = new ParrotClusterImpl(this)
    val parrots = cluster.connectParrots()
    if (parrots.isEmpty) {
      log.error("Empty Parrots list: do you have access to Zookeeper? (ie, running in SMF1)")
    } else {
      parrots foreach { parrot =>
        parrot.setRate(newRate)
      }
    }
  }
}
