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
package com.twitter.iago.feeder

import com.twitter.app.App
import com.twitter.iago.ParrotFeederFlags
import com.twitter.server.logging.{Logging => JDK14Logging}

trait FeederFixture {

  class TestFeederApp(list: Seq[String])
      extends App
      with ParrotFeederFlags
      with JDK14Logging {
    override lazy val logSource = new InMemoryLog(list)
    lazy val feeder = new ParrotFeeder(this)

    def main {
      log.info("Starting Parrot Feeder...")
      feeder.start()
    }
  }
}
