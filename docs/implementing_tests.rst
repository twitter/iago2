Implementing Your Test
----------------------

The following sections show examples of implementing your test in both Scala and Java. See `Code Annotations for the Examples <#code-annotations-for-the-examples>`__ for information about either example.


Scala Example for Http Load Test
~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~

To implement a load test in Scala, you must extend the Iago server's ``RecordProcessor`` class to specify how to map transactions into the requests that the Iago server delivers to your service. The following example shows a ``RecordProcessor`` subclass that implements a load test on a HTTP service:

.. code-block:: scala

  package com.twitter.example

  import com.twitter.finagle.http.Response
  import com.twitter.iago.processor.RecordProcessor                                       // 1
  import com.twitter.iago.server.{ParrotService, ParrotRequest}                           // 2
  import com.twitter.iago.util.Uri
  import com.twitter.logging.Logger

  class WebLoadTest(service: ParrotService[ParrotRequest, Response]) extends RecordProcessor {
    val log = Logger(getClass)

    override def processLine(line: String) {                                              // 5
      val request = new ParrotRequest(                                                    // 4
        uri = Uri(line, Nil)
      )

      service(request) onSuccess { response =>                                            // 6
        response.statusCode match {                                                       // 8
          case x if 200 until 300 contains x => log.info("%s OK".format(response.statusCode))
          case _ => log.error("%s: %s".format(response.statusCode, response.status.reason))
        }
      } onFailure { thrown =>
        log.error(thrown, "%s: %s".format("URL", thrown.getMessage))
      }
    }
  }

Then you must extend the Iago's ``FinagleParrotConfig`` class and override ``recordProcessor`` with your ``RecordProcessor`` implementation:

.. code-block:: scala

  package com.twitter.example

  import com.twitter.iago.util.{RequestDistribution, SlowStartPoissonProcess}
  import com.twitter.iago.processor.RecordProcessor
  import com.twitter.iago.{FinagleParrotConfig, ParrotServerFlags}
  import com.twitter.util.TimeConversions._

  class WebLoadTestConfig(config: ParrotServerFlags) extends FinagleParrotConfig(config) {
    override val recordProcessor: RecordProcessor = new WebLoadTest(service)
    override def distribution(rate: Int): RequestDistribution = new SlowStartPoissonProcess(rate, 1.minute)  // 9
  }


Scala Example for Thrift Load Test
~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~

To implement a Thrift load test in Scala, you must extend the Iago server's ``ThriftRecordProcessor`` class to specify how to map transactions into the requests that the Iago server delivers to your service. The following example shows a ``ThriftRecordProcessor`` subclass that implements a load test on an ``EchoService`` Thrift service:

.. code-block:: scala

  package com.twitter.example

  import com.twitter.finagle.RichClientParam
  import com.twitter.finagle.stats.DefaultStatsReceiver
  import com.twitter.iago.processor.ThriftRecordProcessor                                 // 1
  import com.twitter.iago.server.{ParrotRequest, ParrotService}                           // 2
  import com.twitter.logging.Logger

  import thrift.EchoService

  class EchoLoadTest(parrotService: ParrotService[ParrotRequest, Array[Byte]]) extends ThriftRecordProcessor(parrotService) {
    private val statsReceiver = DefaultStatsReceiver.scope("echoservice_loadtest")
    val client = new EchoService.FinagledClient(                                          // 3
      service,
      RichClientParam(clientStats = statsReceiver)
    )
    val log = Logger.get(getClass)

    override def processLine(line: String) {                                              // 5
      client.echo(line) respond { rep =>                                                  // 6
        if (rep == "hello") {
          client.echo("IT'S TALKING TO US")                                               // 7
        }
        log.info("response: " + rep)                                                      // 8
      }
    }
  }

Then you must extend the Iago's ``ThriftParrotConfig`` class and override ``recordProcessor`` with your ``ThriftRecordProcessor`` implementation:

.. code-block:: scala

  import com.twitter.iago.{ThriftParrotConfig, ParrotServerFlags}

  class EchoLoadTestConfig(config: ParrotServerFlags) extends ThriftParrotConfig(config) {
    override val recordProcessor = new EchoLoadTest(service)
  }


Java Example for Http Load Test
~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~

To implement a load test in Java, you must extend the Iago server's ``LoadTest`` class to specify how to map transactions into the requests that the Iago server delivers to your service. The ``LoadTest`` class provides Java-friendly type mappings for the underlying Scala internals. The following example shows a ``LoadTest`` subclass that implements a load test on a HTTP service:

.. code-block:: java

  package com.twitter.jexample;

  import java.util.List;

  import scala.Tuple2;
  import scala.collection.mutable.ArrayBuffer;

  import com.twitter.finagle.http.FormElement;
  import com.twitter.finagle.http.Response;
  import com.twitter.finagle.thrift.ThriftClientRequest;
  import com.twitter.iago.processor.LoadTest;                                             // 1
  import com.twitter.iago.server.ParrotRequest;                                           // 2
  import com.twitter.iago.server.ParrotService;                                           // 2
  import com.twitter.iago.util.Uri;
  import com.twitter.util.Future;
  import com.twitter.util.FutureEventListener;
  import com.twitter.util.Promise;

  public class WebLoadTest extends LoadTest {
    private ParrotService<ParrotRequest, Response> service = null;

    public WebLoadTest(ParrotService<ParrotRequest, Response> parrotService) {
      this.service = parrotService;
    }

    public void processLine(String line) {                                                // 5
      ParrotRequest request = new ParrotRequest(                                          // 4
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
      Future<Response> future = service.apply(request);                                   // 6
      future.addEventListener(new FutureEventListener<Response>() {                       // 8
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

Then you must extend the Iago's ``FinagleParrotConfig`` class and override ``recordProcessor`` with your ``LoadTest`` implementation:

.. code-block:: java

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


Java Example for Thrift Load Test
~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~

To implement a Thrift load test in Java, you must extend the Iago server's ``ThriftLoadTest`` class to specify how to map transactions into the requests that the Iago server delivers to your service. The ``ThriftLoadTest`` class provides Java-friendly type mappings for the underlying Scala internals. The following example shows a ``ThriftLoadTest`` subclass that implements a load test on an ``EchoService`` Thrift service:

.. code-block:: java

  package com.twitter.jexample;

  import com.twitter.example.thrift.EchoService;
  import com.twitter.finagle.stats.NullStatsReceiver;
  import com.twitter.iago.processor.ThriftLoadTest;                                       // 1
  import com.twitter.iago.server.ParrotRequest;                                           // 2
  import com.twitter.iago.server.ParrotService;                                           // 2
  import com.twitter.util.Future;
  import com.twitter.util.FutureEventListener;

  import org.apache.thrift.protocol.TBinaryProtocol;

  import java.util.List;

  public class EchoLoadTest extends ThriftLoadTest {
    EchoService.FinagledClient client = null;

    public EchoLoadTest(ParrotService<ParrotRequest, byte[]> parrotService) {
      super(parrotService);
      client = new EchoService.FinagledClient(                                            // 3
          service(),
          new TBinaryProtocol.Factory(),
          "EchoService",
          new NullStatsReceiver());
    }

    public void processLine(String line) {                                                // 5
      Future<String> future = client.echo(line);                                          // 6
      future.addEventListener(new FutureEventListener<String>() {                         // 8
        public void onSuccess(String msg) {
          System.out.println("response: " + msg);
        }

        public void onFailure(Throwable cause) {
          System.out.println("Error: " + cause);
        }
      });
    }
  }

Then you must extend the Iago's ``ThriftParrotConfig`` class and override ``recordProcessor`` with your ``ThriftLoadTest`` implementation:

.. code-block:: java

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


Code Annotations for the Examples
~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~

You define your Iago subclass to execute your service and map transactions to requests for your service:

1. Import ``com.twitter.iago.processor.RecordProcessor`` (Scala) or ``LoadTest`` (Java), whose instance will be executed by a Iago server.
2. Import ``com.twitter.iago.server.ParrotService`` and ``com.twitter.iago.server.ParrotRequest``
3. For Thrift service, create an instance of your service to be placed under test.
4. For Http service, create an instance of ParrotRequest to contain your http request.
5. Override ``processLine`` method to format the request and execute your service.
6. Iago adds the request to its request queue.
7. Optionally, you can initiate a new request based on the response to a previous one.
8. Optionally, do something with the response. In this example, the response is logged.
9. Optionally, overrides the ``distribution`` method to change the traffic pattern of the load test.
