package com.twitter.jexample;

import com.twitter.iago.processor.RecordProcessor;
import com.twitter.iago.FinagleParrotConfig;
import com.twitter.iago.ParrotServerFlags;
import com.twitter.iago.server.FinagleTransport;

public class WebLoadTestConfig extends FinagleParrotConfig {
  public WebLoadTestConfig(ParrotServerFlags config) {
    super(config);
  }

  @Override
  public FinagleTransport transport() {
    return super.transport();
  }

  @Override
  public RecordProcessor recordProcessor() {
    return new WebLoadTest(service());
  }
}
