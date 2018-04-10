Iago Architecture Overview
--------------------------

Iago consists of *feeders* and *servers*. A *feeder* reads your transaction source. A *server* formats and delivers requests to the service you want to test. The feeder contains a ``Poller`` object, which is responsible for guaranteeing *cachedSeconds* worth of transactions in the pipeline to the Iago servers.

Metrics are available in logs and in graphs as described in `Metrics <metrics.html>`__.

The Iago servers generate requests to your service. Together, all Iago servers generate the specified number of requests per minute. A Iago server's ``RecordProcessor`` object executes your service and maps the transaction to the format required by your service.

The feeder polls its servers to see how much data they need to maintain *cachedSeconds* worth of data. That is how we can have many feeders that need not coordinate with each other.

Ensuring that we go through every last message is important when we are writing traffic summaries in the record processor, especially when the data set is small. The parrot feeder shuts down due to running out of time, running out of data, or both. When the feeder runs out of data we

* make sure that all the data in parrot feeder's internal queues are sent to the parrot server
* make sure all the data held in the parrot servers cache is sent
* wait until we get a response for all pending messages or until the reads time out

When the parrot feeder runs out of time (the duration configuration) the data in the feeder's internal queues are ignored, otherwise the same process as above occurs.
