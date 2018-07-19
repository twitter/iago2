/*
Copyright 2013 Twitter, Inc.

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
package com.twitter.iago.processor

import com.twitter.finagle.tracing.Trace
import com.twitter.logging.Logger
import com.twitter.iago.ParrotServerFlags
import com.twitter.iago.server.ParrotRequest
import com.twitter.iago.server.ParrotService
import com.twitter.iago.util.UriParser
import com.twitter.util.Return
import com.twitter.util.Throw
import com.twitter.finagle.http.Response

/**
 * This processor just takes a line-separated list of URIs and turns them into requests, for instance:
 * /search.json?q=%23playerofseason&since_id=68317051210563584&rpp=30
 * needs to have the target scheme, host, and port included, but otherwise is a complete URL
 * Empty lines and lines starting with '#' will be ignored.
 */
class TestRecordProcessor(
  service: ParrotService[ParrotRequest, Response],
  config: ParrotServerFlags
) extends RecordProcessor {
  private[this] val log = Logger.get(getClass)
  private[this] var exceptionCount = 0
  private[this] val hostHeader = Some((config.httpHostHeaderF(), config.httpHostHeaderPortF()))
  private[this] val badLines = config.statsReceiver.counter("bad_lines")
  var responded = 0
  var properlyShutDown = false

  override def processLine(line: String) {
    Trace.traceService("Parrot", "TestRecordProcessor.processLine") {
      val p = UriParser(line)
      log.trace("SimpleRecordProcessor.processLine: line is %s", line)
      UriParser(line) match {
        case Return(uri) =>
          if (!uri.path.isEmpty && !line.startsWith("#"))
            service(new ParrotRequest(hostHeader, Nil, uri, line)) respond { response =>
              log.debug("response was %s", response.toString)
              this.synchronized { responded += 1 }
            }
        case Throw(t) =>
          if (exceptionCount < 3)
            log.warning("exception\n\t%s\nwhile processing line\n\t%s", t.getMessage(), line)
          else if (exceptionCount == 3) log.warning("more exceptions ...")
          this.synchronized { exceptionCount += 1 }
          badLines.incr()
          config.statsReceiver.counter("bad_lines/" + t.getClass.getName).incr()
      }
    }
  }
  override def shutdown() {
    properlyShutDown = true
  }
}
