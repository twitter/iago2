/* Copyright 2014 Twitter, Inc.

Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in
compliance with the License.  You may obtain a copy of the License at

http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software distributed under the License is
distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or
implied.  See the License for the specific language governing permissions and limitations under the
License.
 */

package com.twitter.iago.processor

import com.twitter.finagle.thrift.ThriftClientRequest
import com.twitter.finagle.tracing.Trace
import com.twitter.logging.Logger
import com.twitter.iago.server.{ParrotRequest, ParrotService}
import com.twitter.iago.util.ThriftFixture

class TestThriftRecordProcessor(pService: ParrotService[ParrotRequest, Array[Byte]])
    extends ThriftRecordProcessor(pService)
    with ThriftFixture {
  private val log = Logger.get(getClass)
  var response = ""

  override def processLine(line: String) {
    Trace.traceService("Parrot", "TestThriftRecordProcessor.processLine") {
      val future = service(new ThriftClientRequest(serialize("echo", "message", "hello"), false))
      future onSuccess { bytes =>
        response = bytes.toString
        log.debug("ThriftRecordProcessorSpec: response: %s", response)
      }
      future onFailure { throwable =>
        log.error(throwable, "ThriftRecordProcessorSpec: failure response")
      }
    }
  }
}
