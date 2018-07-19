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
package com.twitter.iago.processor

import com.twitter.finagle.http.Response
import com.twitter.logging.Logger
import com.twitter.iago.server.ParrotRequest
import com.twitter.iago.server.ParrotService
import com.twitter.iago.util.UriParser
import com.twitter.util.{Future, Return, Throw}
import com.twitter.finagle.tracing.Trace
import com.twitter.iago.ParrotServerFlags
import com.twitter.common.stats.Stats

/**
 * This processor just takes a line-separated list of URIs and turns them into requests, for instance:
 * /search.json?q=%23playerofseason&since_id=68317051210563584&rpp=30
 * needs to have the target scheme, host, and port included, but otherwise is a complete URL
 * Empty lines and lines starting with '#' will be ignored.
 */
class SimpleRecordProcessor(
  service: ParrotService[ParrotRequest, Response],
  config: ParrotServerFlags
) extends RecordProcessor {
  private[this] val log = Logger.get(getClass)
  private[this] var exceptionCount = 0
  private[this] val hostHeader = Some((config.httpHostHeaderF(), config.httpHostHeaderPortF()))

  val badlines = config.statsReceiver.counter("bad_lines")

  override def processLine(line: String) {
    Trace.traceService("Parrot", "SimpleRecordProcessor.processLine") {
      val p = UriParser(line)
      log.trace("SimpleRecordProcessor.processLine: line is %s", line)
      UriParser(line) match {
        case Return(uri) =>
          if (!uri.path.isEmpty && !line.startsWith("#"))
            service(new ParrotRequest(hostHeader, Nil, uri, line))
        case Throw(t) =>
          if (exceptionCount < 3)
            log.warning("exception\n\t%s\nwhile processing line\n\t%s", t.getMessage(), line)
          else if (exceptionCount == 3) log.warning("more exceptions ...")
          exceptionCount += 1
          badlines.incr()
          config.statsReceiver.counter("bad_lines/" + t.getClass.getName).incr()
      }
    }
  }

  override def shutdown() = {
    log.trace("SimpleRecordProcessor.shutdown: shutting down")
  }
}
