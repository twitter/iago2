package com.twitter.jexample;

import java.util.List;

import scala.Tuple2;
import scala.collection.mutable.ArrayBuffer;

import com.twitter.finagle.http.FormElement;
import com.twitter.finagle.http.Response;
import com.twitter.finagle.thrift.ThriftClientRequest;
import com.twitter.iago.processor.LoadTest;
import com.twitter.iago.server.ParrotRequest;
import com.twitter.iago.server.ParrotService;
import com.twitter.iago.util.Uri;
import com.twitter.util.Future;
import com.twitter.util.FutureEventListener;
import com.twitter.util.Promise;

public class WebLoadTest extends LoadTest {
  private ParrotService<ParrotRequest, Response> service = null;

  public WebLoadTest(ParrotService<ParrotRequest, Response> parrotService) {
    this.service = parrotService;
  }

  public void processLine(String line) {
    ParrotRequest request = new ParrotRequest(
        scala.Some.apply(new Tuple2<String, Object>("www.google.com", 80)),
        new ArrayBuffer<Tuple2<String, String>>(),
        new Uri(line, new ArrayBuffer<Tuple2<String, String>>()),
        "",
        scala.Option.apply(null),
        new ThriftClientRequest(new byte[1], false),
        new Promise(),
        new ArrayBuffer<Tuple2<String, String>>(),
        "GET",
        "",
        false,
        new ArrayBuffer<FormElement>(),
        1
    );
    Future<Response> future = service.apply(request);
    future.addEventListener(new FutureEventListener<Response>() {
      public void onSuccess(Response resp) {
        if(resp.statusCode() >= 200 && resp.statusCode() < 300) {
          System.out.println(String.valueOf(resp.statusCode()) + " OK");
        } else {
          System.out.println("Error: " + String.valueOf(resp.statusCode()) + " " + resp.status().reason());
        }
      }

      public void onFailure(Throwable cause) {
        System.out.println("Error: " + cause.toString());
      }
    });
  }
}
