package com.twitter.example

import com.twitter.iago.{ParrotServerFlags, ThriftParrotConfig}
import com.twitter.iago.util.{RequestDistribution, SlowStartPoissonProcess}
import com.twitter.util.TimeConversions._

class EchoLoadTestConfig(config: ParrotServerFlags) extends ThriftParrotConfig(config) {
  override val recordProcessor = new EchoLoadTest(service)
  override def distribution(rate: Int): RequestDistribution = new SlowStartPoissonProcess(rate, 1.minute)
}
