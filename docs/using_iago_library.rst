Using Iago2 as a Library
------------------------

While Iago provides everything you need to target your API with a large distributed loadtest with just a small log processor,
it also exposes a library of classes for log processing, traffic replay, & load generation. These can be used in your Iago configuration or incorporated in your application as a library.

iago/server
~~~~~~~~~~~

``ParrotRequest``
  Iago's internal representation of a request

``ParrotTransport`` (``FinagleTransport``, ``MemcacheTransport``, ``ParrotUdpTransport``, ``ThriftTransport``)
  Interchangeable transport layer for requests to be sent. Iago contains transport implementations for the following protocols: HTTP (``FinagleTransport``), Memcache, raw UDP and Thrift.

``RequestConsumer``
  Queues ``ParrotRequest``\ s and sends them out on a ``ParrotTransport`` at a rate determined by requestDistribution

``RequestQueue``
  A wrapper/control layer for ``RequestConsumer``

``ParrotService`` (``ParrotThriftService``)
  Enqueues ``ParrotRequest``\ s to a ``RequestQueue``. ``ParrotThriftService`` implements finagle's ``Service`` interface for use with finagle thrift clients.


iago/util
~~~~~~~~~

``RequestDistribution``
  A function specifying the time to arrival of the next request, used to control the request rate.

Instances of ``RequestDistribution`` include:

``UniformDistribution``
  Sends requests at a uniform rate

``PoissonProcess``
  Sends requests at approximatly constant rate randomly varying using a poisson process. This is the default.

``SinusoidalPoissonProcess``
  Like ``PoissonProcess`` but varying the rate sinusoidally.

``SlowStartPoissonProcess``
  Same as ``PoissonProcess`` but starting with a gradual ramp from initial rate to final rate. It will then hold steady at the final rate until time runs out.

``InfiniteRampPoissonProcess``
  a two staged ramped distribution. Ideal for services that need a warm-up period before ramping up. The rate continues to increase until time runs out.

``InfiniteSteppedPoissonProcess``
  A two staged distribution.  Includes a warmup ramp and then continues to increase the request rate by stepRate every stepDuration until time runs out.  Useful in determining sustained rps thresholds.

``PiecewiseDistribution``
  A distribution that is composed from a sequence of distributions which are run sequentially.

You may also find the ``LogSource`` and ``RequestProcessor`` interfaces useful.
