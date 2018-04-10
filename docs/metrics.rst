Metrics
-------

Iago uses `Common Metrics <https://github.com/twitter/commons/tree/master/src/java/com/twitter/common/metrics>`__ to record its metrics. If you are using env.local, then the default place for this is

http://localhost:9992/admin/metrics.json?pretty=true

Request latency is the time it takes to queue the request for sending until the response is received. See the `Finagle User Guide <http://twitter.github.io/finagle/guide/Metrics.html>`__ for more about the individual metrics.

Other metrics of interest:

+-------------------------------+-------------------------------------------------------------+
| Statistic                     | Description                                                 |
+===============================+=============================================================+
| ``connection_duration``       | duration of a connection from established to closed?        |
+-------------------------------+-------------------------------------------------------------+
| ``connection_received_bytes`` | bytes received per connection                               |
+-------------------------------+-------------------------------------------------------------+
| ``connection_requests``       | Number of connection requests that your client did, ie. you |
|                               | can have a pool of 1 connection and the connection can be   |
|                               | closed 3 times, so the "connection_requests" would be 4     |
|                               | (even if connections = 1)                                   |
+-------------------------------+-------------------------------------------------------------+
| ``connection_sent_bytes``     | bytes send per connection                                   |
+-------------------------------+-------------------------------------------------------------+
| ``connections``               | is the current number of connections between client and     |
|                               | server                                                      |
+-------------------------------+-------------------------------------------------------------+
| ``handletime_us``             | time to process the response from the server (ie. execute   |
|                               | all the chained map/flatMap)                                |
+-------------------------------+-------------------------------------------------------------+
| ``pending``                   | Number of pending requests (ie. requests without responses) |
+-------------------------------+-------------------------------------------------------------+
| ``request_concurrency``       | is the current number of connections being processed by     |
|                               | finagle                                                     |
+-------------------------------+-------------------------------------------------------------+
| ``request_latency_ms``        | the time of everything between request/response.            |
+-------------------------------+-------------------------------------------------------------+
| ``request_queue_size``        | Number of requests waiting to be handled by the server      |
+-------------------------------+-------------------------------------------------------------+

