Weighted Requests
-----------------

Some applications must make bulk requests to their service. In other words, a single meta-request in the input log may result in several requests being satisfied by the victim. A weight field to ``ParrotRequest`` was added so that the ``RecordProcessor`` can set and use that weight to control the send rate in the ``RequestConsumer``. For example, a request for 17 messages would be given a weight of `17`, which would cause the ``RequestConsumer`` to sample the request distribution 17 times yielding a consistent distribution of load on the victim.
