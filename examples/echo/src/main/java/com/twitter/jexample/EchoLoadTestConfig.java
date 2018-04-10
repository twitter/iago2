package com.twitter.jexample;

import com.twitter.iago.ParrotServerFlags;
import com.twitter.iago.ThriftParrotConfig;
import com.twitter.iago.processor.RecordProcessor;

public class EchoLoadTestConfig extends ThriftParrotConfig {
  public EchoLoadTestConfig(ParrotServerFlags config) {
    super(config, true);
  }

  @Override
  public RecordProcessor recordProcessor() {
    return new EchoLoadTest(service());
  }
}
