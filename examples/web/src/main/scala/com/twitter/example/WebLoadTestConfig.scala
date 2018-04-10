package com.twitter.example

import com.twitter.iago.processor.RecordProcessor
import com.twitter.iago.{FinagleParrotConfig, ParrotServerFlags}

class WebLoadTestConfig(config: ParrotServerFlags) extends FinagleParrotConfig(config) {
  override val recordProcessor: RecordProcessor = new WebLoadTest(service)
}
