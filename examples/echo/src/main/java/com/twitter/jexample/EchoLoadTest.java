package com.twitter.jexample;

import com.twitter.example.thrift.EchoService;
import com.twitter.finagle.stats.NullStatsReceiver;
import com.twitter.iago.processor.ThriftLoadTest;
import com.twitter.iago.server.ParrotRequest;
import com.twitter.iago.server.ParrotService;
import com.twitter.util.Future;
import com.twitter.util.FutureEventListener;

import org.apache.thrift.protocol.TBinaryProtocol;

import java.util.List;

public class EchoLoadTest extends ThriftLoadTest {
  EchoService.FinagledClient client = null;

  public EchoLoadTest(ParrotService<ParrotRequest, byte[]> parrotService) {
    super(parrotService);
    client = new EchoService.FinagledClient(
        service(),
        new TBinaryProtocol.Factory(),
        "EchoService",
        new NullStatsReceiver());
  }

  public void processLine(String line) {
    Future<String> future = client.echo(line);
    future.addEventListener(new FutureEventListener<String>() {
      public void onSuccess(String msg) {
        System.out.println("response: " + msg);
      }

      public void onFailure(Throwable cause) {
        System.out.println("Error: " + cause);
      }
    });
  }
}
