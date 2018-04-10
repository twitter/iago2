package com.twitter.example

import com.twitter.finagle.http.Response
import com.twitter.iago.processor.RecordProcessor
import com.twitter.iago.server.{ParrotService, ParrotRequest}
import com.twitter.iago.util.Uri
import com.twitter.logging.Logger

class WebLoadTest(service: ParrotService[ParrotRequest, Response]) extends RecordProcessor {
  val log = Logger(getClass)

  override def processLine(line: String) {
    val request = new ParrotRequest(
      uri = Uri(line, Nil)
    )

    service(request).onSuccess { response =>
      response.statusCode match {
        case x if 200 until 300 contains x => log.info("%s OK".format(response.statusCode))
        case _ => log.error("%s: %s".format(response.statusCode, response.status.reason))
      }
    }.onFailure { thrown =>
      log.error(thrown, "%s: %s".format("URL", thrown.getMessage))
    }
  }
}
