Iago Overview
-------------

Iago is a load generation tool that replays production or synthetic traffic against a given target. Among other things, it differs from other load generation tools in that it attempts to hold constant the transaction rate. For example, if you want to test your service at 100K requests per minute, Iago attempts to achieve that rate.

Because Iago replays traffic, you must specify the source of the traffic. You use a transaction log as the source of traffic, in which each transaction generates a *request* to your service that your service processes.

Replaying transactions at a fixed rate enables you to study the behavior of your service under an anticipated load. Iago also allows you to identify bottlenecks or other issues that may not be easily observable in a production environment in which your maximum anticipated load occurs only rarely.

Philosophy
~~~~~~~~~~
Iago has a specific point of view about load testing. Understanding
that point of view will help you to decide whether to use it.

Iago is primarily a load generation library. It is designed
for software engineers familiar with a JVM language such as
Java or Scala. If you want to test an API under load, Iago is great.
While you can use Iago as a stand-alone load testing application,
this is not its strong suit today; if that is what you want, you will probably be
happier with other tools.

That said, if you are a programmer who wants to test an API under load, Iago is very easy
to use. Stand-alone load-test applications are great if you want to repeatedly get
http://example.com/, but if you instead want to repeatedly exercise an API, they
probably don't do what you want. Iago shines here.

If you've already written a test client for your API, you've probably already written
the code that Iago can use to exercise that API. It usually requires
more code to write a test client than it does to write a load test using Iago. Indeed,
the code from a test client is usually just what you need to write your load test. This
is deliberate, because we believe that people who most need Iago are those who find
themselves writing their own load tests late at night after a release has gone bad.

Iago accurately replicates production traffic. It models open systems,
systems which recieve requests independent of their ability to service them.
Typical load generators measure the
time it takes for `M` threads to make `N` requests, waiting for a
response to each request before sending the next; if your system slows down under load,
these load testers thus mercifully slow down their pace to match.
That's a fine thing to measure; many systems behave this way. But maybe your
service isn't such a system; maybe it's exposed on the internet.
Maybe you want to know how your system behaves when `N`
requests per second come in with no "mercy" if it slows down.

Iago focuses on requests per second and has built-in support for statistical
models such as an exponential distribution (used to model a Poisson Process) and uniform
distributions. You can also write your own request distribution, either
in terms of sums of distribution or your own implementation. Iago
reliably meets the arrival times your distribution specifies,
even when rates and wait times are high.

Iago supports arbitrarily high rates of traffic via
built-in support for creating clusters: if one machine can't generate the load you need,
then Iago can launch jobs on more machines. At Twitter, engineers regularly run load tests
that generate in excess of 100K requests per second or more. Single instances of Iago
can easily achieve anywhere from 1K to 10K rps from commodity hardware, only limited by
the particulars of your protocol needs.

You can extend or replace Iago's components. Because our target users are other
engineers, it is critical that every knob be available to turn. You
can write your own protocols, data sources, management interfaces or
whatever else you can imagine.

Supported Services
~~~~~~~~~~~~~~~~~~

Iago can generate service requests that travel the net in different ways and are in different formats. The code that does this is in a Transport, a class that extends ``ParrotTransport``. Iago comes with several Transports already defined. When you configure your test, you will need to set some parameters; to understand which of those parameters are used and how they are used, you probably want to look at the source code for your test's Transport class.

* HTTP: Use `FinagleTransport <https://github.com/twitter/iago2/tree/master/src/main/scala/com/twitter/iago/server/FinagleTransport.scala>`__
* Thrift: Use `ThriftTransport <https://github.com/twitter/iago2/tree/master/src/main/scala/com/twitter/iago/server/ThriftTransport.scala>`__
* Memcached: Use `MemcacheTransport <https://github.com/twitter/iago2/tree/master/src/main/scala/com/twitter/iago/server/MemcacheTransport.scala>`__
* UDP: Use `ParrotUdpTransport <https://github.com/twitter/iago2/tree/master/src/main/scala/com/twitter/iago/server/ParrotUdpTransport.scala>`__

Your service is typically an HTTP or Thrift service written in either Scala or Java.


Transaction Requirements
~~~~~~~~~~~~~~~~~~~~~~~~

For replay, Iago recommends you scrub your logs to only include requests which meet the following requirements:

* **Idempotent**, meaning that re-execution of a transaction any number of times yields the same result as the initial execution.
* **Commutative**, meaning that transaction order is not important. Although transactions are initiated in replay order, Iago's internal behavior may change the actual execution order to guarantee the transaction rate. Also, transactions that implement ``Future`` responses are executed asynchronously. You can achieve ordering, if required, by using Iago as a library and initiating new requests in response to previous ones.


Sources of Transactions
~~~~~~~~~~~~~~~~~~~~~~~

Transactions typically come from logs, such as the following:

* Web server logs capture HTTP transactions.
* Proxy server logs can capture transactions coming through a server. You can place a proxy server in your stack to capture either HTTP or Thrift transactions.
* Network sniffers can capture transactions as they come across a physical wire. You can program the sniffer to create a log of transactions you identify for capture.

In some cases, transactions do not exist. For example, transactions for your service may not yet exist because they are part of a new service, or you are obligated not to use transactions that contain sensitive information. In such cases, you can provide *synthetic* transactions, which are transactions that you create to model the operating environment for your service. When you create synthetic transactions, you must statistically distribute your transactions to match the distribution you expect when your service goes live.
