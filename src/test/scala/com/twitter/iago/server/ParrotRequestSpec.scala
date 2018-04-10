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

import org.junit.runner.RunWith
import org.scalatest.{MustMatchers, WordSpec}
import org.scalatest.junit.JUnitRunner

@RunWith(classOf[JUnitRunner])
class ParrotRequestSpec extends WordSpec with MustMatchers {

  "ParrotRequest.headers" should {
    "no headers, no host header" in {
      assert(new ParrotRequest().headers == Nil)
    }
    "no headers, host header" in {
      assert(new ParrotRequest(Some("bar" -> 2345)).headers == Seq("Host" -> "bar:2345"))
    }
    "headers, no host header" in {
      val headers = Seq("foo" -> "bar", "baz" -> "blarg")
      assert(new ParrotRequest(None, headers).headers == headers)
    }
    "headers, host header" in {
      val request = new ParrotRequest(Some("bar" -> 2345), Seq("foo" -> "bar"))
      assert(request.headers == Seq("Host" -> "bar:2345", "foo" -> "bar"))
    }
  }
}
