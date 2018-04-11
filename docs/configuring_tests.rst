Configuring and Launching Your Test
-----------------------------------

To configure and execute your test, build the jvm application and launch it with ``com.twitter.iago.launcher.Main``.

There are several parameters to pass to ``com.twitter.iago.launcher.Main``. A good one to `figure out early <overview.rst#supported-services>`__ is ``transportScheme``.

The following example shows parameters for testing a Thrift service in Local mode (launches Iago feeder and server on your local machine):

.. code-block:: bash

  #!/usr/bin/env bash

  set -u
  set -x

  # Build your test bundle.
  mvn package

  java -cp target/myservice-loadtest-package-dist.jar \
    com.twitter.iago.launcher.Main \
    launch \
    -env.local \
    -requestRate=1 \
    -duration=5.minutes \
    -inputLog="data/logs.txt" \
    -reuseFile=true \
    -includeParrotHeader=false \
    -transportScheme=thrift \
    -thriftClientId=echo-client \
    -victimHostPort="localhost:8081" \
    -config=com.twitter.example.EchoLoadTestConfig \
    -jobName="myservice_loadtest" \
    -yes

Please also check the `example source code <https://github.com/twitter/iago2/tree/master/examples/echo>`__


.. note::

You can specify any of the following parameters:

Iago Feeder Parameters
~~~~~~~~~~~~~~~~~~~~~~

+-----------------------------------+------------+-------------------------------------------------------------+
| Parameter                         | Default    | Description                                                 |
|                                   | Value      |                                                             |
+===================================+============+=============================================================+
| ``cachedSeconds``                 | `5`        | How many seconds worth of data the Iago server attempts     |
|                                   |            | to cache. Setting this to `1` for large messages is         |
|                                   |            | recommended. The consequence is that, if the Iago feeder    |
|                                   |            | garbage-collects, there will be a corresponding pause in    |
|                                   |            | traffic to your service unless cachedSeconds is set to a    |
|                                   |            | value larger than a typical feeder gc. This author has never|
|                                   |            | observed a feeder gc exceeding a fraction of a second.      |
|                                   +------------+-------------------------------------------------------------+
|                                   | .. code-block:: bash                                                     |
|                                   |                                                                          |
|                                   |   -cachedSeconds=5                                                       |
+-----------------------------------+------------+-------------------------------------------------------------+
| ``cutoff``                        | `6`        | How many seconds the feeder waits for the server request    |
|                                   |            | queue to empty. Usually set this to 1.2 * ``cachedSeconds``.|
|                                   +------------+-------------------------------------------------------------+
|                                   | .. code-block:: bash                                                     |
|                                   |                                                                          |
|                                   |   -cutoff=6                                                              |
+-----------------------------------+------------+-------------------------------------------------------------+
| ``duration``                      | **N/A**    | A ``Duration`` value that specifies how long the test runs. |
|                                   | (required) |                                                             |
|                                   +------------+-------------------------------------------------------------+
|                                   | .. code-block:: bash                                                     |
|                                   |                                                                          |
|                                   |   -duration=5.minutes                                                    |
+-----------------------------------+------------+-------------------------------------------------------------+
| ``inputLog``                      | **N/A**    | A string value that specifies the complete path to the log  |
|                                   | (required) | you want Iago to replay. If -env.local then the log should  |
|                                   |            | be on your local file system.                               |
|                                   |            | If -env.aurora, then you should pack your log file as a     |
|                                   |            | resource into the bundle when attempts to make a package    |
|                                   |            | for Mesos, or store your log file on the distributed system |
|                                   |            | such as HDFS.                                               |
|                                   +------------+-------------------------------------------------------------+
|                                   | .. code-block:: bash                                                     |
|                                   |                                                                          |
|                                   |   -inputLog="logs/yesterday.log"                                         |
+-----------------------------------+------------+-------------------------------------------------------------+
| ``linesToSkip``                   | `0`        | Skip this many lines in the log file.                       |
|                                   +------------+-------------------------------------------------------------+
|                                   | .. code-block:: bash                                                     |
|                                   |                                                                          |
|                                   |   -linesToSkip=10                                                        |
+-----------------------------------+-----------------------------------------+--------------------------------+
| ``logSource``                     | `com.twitter.iago.feeder.LogSourceImpl` | Class used by the feeder to    |
|                                   |                                         | consume the input log.         |
|                                   +-----------------------------------------+--------------------------------+
|                                   | .. code-block:: bash                                                     |
|                                   |                                                                          |
|                                   |   -logSource=YourLogSourceImpl                                           |
+-----------------------------------+--------------------+-----------------------------------------------------+
| ``maxRequests``                   | `Integer.MaxValue` | An integer value that specifies the total number of |
|                                   |                    | requests to submit to your service.                 |
|                                   +--------------------+-----------------------------------------------------+
|                                   | .. code-block:: bash                                                     |
|                                   |                                                                          |
|                                   |   -maxRequests=10000                                                     |
+-----------------------------------+------------+-------------------------------------------------------------+
| ``numInstances``                  | `1`        | Number of Iago servers concurrently making requests to your |
|                                   |            | service.                                                    |
|                                   +------------+-------------------------------------------------------------+
|                                   | .. code-block:: bash                                                     |
|                                   |                                                                          |
|                                   |   -numInstances=1                                                        |
+-----------------------------------+------------+-------------------------------------------------------------+
| ``requestRate``                   | `1`        | An integer value that specifies the number of requests per  |
|                                   |            | second to submit to your service.                           |
|                                   |            |                                                             |
|                                   |            | If using multiple server instances, ``requestRate`` is per- |
|                                   |            | instance, not aggregate.                                    |
|                                   +------------+-------------------------------------------------------------+
|                                   | .. code-block:: bash                                                     |
|                                   |                                                                          |
|                                   |   -requestRate=10                                                        |
+-----------------------------------+------------+-------------------------------------------------------------+
| ``reuseFile``                     | `false`    | A boolean value that specifies whether or not to stop the   |
|                                   |            | test when the input log has been read through. Setting this |
|                                   |            | value to `true` will result in Iago starting back at the    |
|                                   |            | beginning of the log when it exhausts the contents. When it |
|                                   |            | is set to `false`, your log file should contain enough      |
|                                   |            | contents for your load test.                                |
|                                   +------------+-------------------------------------------------------------+
|                                   | .. code-block:: bash                                                     |
|                                   |                                                                          |
|                                   |   -reuseFile                                                             |
+-----------------------------------+--------------------------------------------------------------------------+


Iago Server Parameters
~~~~~~~~~~~~~~~~~~~~~~

Iago calls the service under test "victim".

Victims may be:

1. A single host:port pair, or a list of host:port pairs (specify ``victimHostPort``)
2. A zookeeper serverset (specify ``victimServerSet``)
3. Any finagle-resolvable destination (specify ``victimDest``)

.. note::

  1. One of the ``victimDest``, ``victimServerSet`` or ``victimHostPort`` must be defined.
  2. ParrotUdpTransport can only handle a single host:port pair. The other transports that come with Iago, being Finagle based, do not have this limitation.


+-----------------------------------+------------+-------------------------------------------------------------+
| Parameter                         | Default    | Description                                                 |
|                                   | Value      |                                                             |
+===================================+============+=============================================================+
| ``config``                        | **N/A**    | The class name of a concrete ParrotConfig to use for the    |
|                                   | (required) | load test.                                                  |
|                                   +------------+-------------------------------------------------------------+
|                                   | .. code-block:: bash                                                     |
|                                   |                                                                          |
|                                   |   -config=com.twitter.myservice.loadtest.MyServiceLoadTestConfig         |
+-----------------------------------+------------+-------------------------------------------------------------+
| ``connectTimeout``                | `5.seconds`| The timeout period within which a Finagle transport may     |
|                                   |            | establish a connection to the victim.                       |
|                                   +------------+-------------------------------------------------------------+
|                                   | .. code-block:: bash                                                     |
|                                   |                                                                          |
|                                   |   -connectTimeout=5.seconds                                              |
+-----------------------------------+------------+-------------------------------------------------------------+
| ``hostConnectionCoresize``        | `1`        | Number of connections per host that will be kept open, once |
|                                   |            | established, until they hit max idle time or max lifetime.  |
|                                   +------------+-------------------------------------------------------------+
|                                   | .. code-block:: bash                                                     |
|                                   |                                                                          |
|                                   |   -hostConnectionCoresize=1                                              |
+-----------------------------------+------------+-------------------------------------------------------------+
| ``hostConnectionIdleTime``        | `5.seconds`| For any connection > coreSize, maximum amount of time       |
|                                   |            | between requests we allow before shutting down the          |
|                                   |            | connection.                                                 |
|                                   +------------+-------------------------------------------------------------+
|                                   | .. code-block:: bash                                                     |
|                                   |                                                                          |
|                                   |   -hostConnectionIdleTimeInMs=5.seconds                                  |
+-----------------------------------+---------------------+----------------------------------------------------+
| ``hostConnectionLimit``           | `Integer.MAX_VALUE` | Limit on the number of connections per host.       |
|                                   +---------------------+----------------------------------------------------+
|                                   | .. code-block:: bash                                                     |
|                                   |                                                                          |
|                                   |   -hostConnectionLimit=4                                                 |
+-----------------------------------+------------+-------------------------------------------------------------+
| ``hostConnectionMaxIdleTime``     | `5.seconds`| The maximum time that any connection (including within core |
|                                   |            | size) can stay idle before shutdown.                        |
|                                   +------------+-------------------------------------------------------------+
|                                   | .. code-block:: bash                                                     |
|                                   |                                                                          |
|                                   |   -hostConnectionMaxIdleTimeInMs=5.seconds                               |
+-----------------------------------+------------------+-------------------------------------------------------+
| ``hostConnectionMaxLifeTime``     | `Duration.Top`   | The maximum duration that a connection will be kept   |
|                                   |                  | open.                                                 |
|                                   +------------------+-------------------------------------------------------+
|                                   | .. code-block:: bash                                                     |
|                                   |                                                                          |
|                                   |   -hostConnectionMaxLifeTime=10.seconds                                  |
+-----------------------------------+------------+-------------------------------------------------------------+
| ``includeParrotHeader``           | `true`     | Add a X-Parrot=true header when using FinagleTransport      |
|                                   +------------+-------------------------------------------------------------+
|                                   | .. code-block:: bash                                                     |
|                                   |                                                                          |
|                                   |   -includeParrotHeader=false                                             |
+-----------------------------------+--------------+-----------------------------------------------------------+
| ``requestTimeout``                | `30.seconds` | The request timeout is the time given to a *single*       |
|                                   |              | request (if there are retries, they each get a fresh      |
|                                   |              | request timeout). The timeout is applied only after a     |
|                                   |              | connection has been acquired. That is: it is applied to   |
|                                   |              | the interval between the dispatch of the request and the  |
|                                   |              | receipt of the response.                                  |
|                                   |              | Note that Iago servers will not shut down until every     |
|                                   |              | response from every victim has come in. If you've modified|
|                                   |              | your record processor to write test summaries this can be |
|                                   |              | an issue.                                                 |
|                                   +--------------+-----------------------------------------------------------+
|                                   | .. code-block:: bash                                                     |
|                                   |                                                                          |
|                                   |   -requestTimeout=30.seconds                                             |
+-----------------------------------+------------+-------------------------------------------------------------+
| ``service.port``                  | `":9991"`  | Port on which the Iago server listens.                      |
|                                   +------------+-------------------------------------------------------------+
|                                   | .. code-block:: bash                                                     |
|                                   |                                                                          |
|                                   |   -service.port=":9991"                                                  |
+-----------------------------------+------------+-------------------------------------------------------------+
| ``thriftClientId``                | `""`       | If you are making Thrift requests, your ``clientId``.       |
|                                   +------------+-------------------------------------------------------------+
|                                   | .. code-block:: bash                                                     |
|                                   |                                                                          |
|                                   |   -thriftClientId="myservice.staging"                                    |
+-----------------------------------+------------+-------------------------------------------------------------+
| ``transportScheme``               | `http`     | The kind of transport protocol to the server. Must be one of|
|                                   |            | http, https, thrift, or thrifts.                            |
|                                   +------------+-------------------------------------------------------------+
|                                   | .. code-block:: bash                                                     |
|                                   |                                                                          |
|                                   |   -transportScheme=thrift                                                |
+-----------------------------------+------------+-------------------------------------------------------------+
| ``victimDest``                    | **N/A**    | A string value that specifies the Finagle destination.      |
|                                   | (required) |                                                             |
|                                   +------------+-------------------------------------------------------------+
|                                   | *Finagle variant*::                                                      |
|                                   |                                                                          |
|                                   |   -victimDest="/s/role/service"                                          |
+-----------------------------------+------------+-------------------------------------------------------------+
| ``victimHostPort``                | **N/A**    | A string value that specifies the host:port pair.           |
|                                   | (required) |                                                             |
|                                   +------------+-------------------------------------------------------------+
|                                   | *Host:port variant*::                                                    |
|                                   |                                                                          |
|                                   |   -victimHostPort="example.com:80"                                       |
+-----------------------------------+------------+-------------------------------------------------------------+
| ``victimServerSet``               | **N/A**    | A string value that specifies the zookeeper serverset path. |
|                                   | (required) |                                                             |
|                                   +------------+-------------------------------------------------------------+
|                                   | *Zookeeper variant*::                                                    |
|                                   |                                                                          |
|                                   |   -victimServerSet="/some/zookeeper/path"                                |
+-----------------------------------+---------------------------+----------------------------------------------+
| ``victimZk``                      | `"zookeeper.example.com"` | The host name of the zookeeper where your    |
|                                   |                           | serverset is registered.                     |
|                                   +---------------------------+----------------------------------------------+
|                                   | *Zookeeper variant*::                                                    |
|                                   |                                                                          |
|                                   |   -victimZk="myzookeeper.company.com"                                    |
+-----------------------------------+------------+-------------------------------------------------------------+
| ``victimZkPort``                  | `2181`     | The port of the zookeeper where your serverset is           |
|                                   |            | registered.                                                 |
|                                   +------------+-------------------------------------------------------------+
|                                   | *Zookeeper variant*::                                                    |
|                                   |                                                                          |
|                                   |   -victimZkPort=2182                                                     |
+-----------------------------------+--------------------------------------------------------------------------+


Common Parameters
~~~~~~~~~~~~~~~~~

Both the Iago feeder and the Iago server need to know about the following parameters:

+-----------------------------------+---------------+----------------------------------------------------------+
| Parameter                         | Default Value | Description                                              |
+===================================+===============+==========================================================+
| ``batchSize``                     | `1000`        | How many messages the Iago feeder sends at one time to   |
|                                   |               | Iago server. For large messages, setting this to `1` is  |
|                                   |               | recommended.                                             |
|                                   +---------------+----------------------------------------------------------+
|                                   | .. code-block:: bash                                                     |
|                                   |                                                                          |
|                                   |   -batchSize=1                                                           |
+-----------------------------------+---------------+----------------------------------------------------------+
| ``finagleTimeout``                | `5.seconds`   | How long the Iago Feeder will wait for a response from a |
|                                   |               | Iago Server                                              |
|                                   +---------------+----------------------------------------------------------+
|                                   | .. code-block:: bash                                                     |
|                                   |                                                                          |
|                                   |   -finagleTimeout=5.seconds                                              |
+-----------------------------------+---------------+----------------------------------------------------------+
| ``jobName``                       | `parrot`      | Name of your load test.                                  |
|                                   +---------------+----------------------------------------------------------+
|                                   | .. code-block:: bash                                                     |
|                                   |                                                                          |
|                                   |   -jobName=myservice_loadtest                                            |
+-----------------------------------+--------------------------------------------------------------------------+


Launcher Parameters
~~~~~~~~~~~~~~~~~~~

+-----------------------------------+---------------+----------------------------------------------------------+
| Parameter                         | Default Value | Description                                              |
+===================================+===============+==========================================================+
| ``yes``                           | `false`       | If -yes is defined, all user input defaults to yes. In   |
|                                   |               | other words, the test will be launched without user's    |
|                                   |               | confirmation. Use with caution.                          |
|                                   +---------------+----------------------------------------------------------+
|                                   | .. code-block:: bash                                                     |
|                                   |                                                                          |
|                                   |   -yes                                                                   |
+-----------------------------------+--------------------------------------------------------------------------+

.. note::

  In addition to the above standard parameters, you can choose one set of launching mode parameters from the following sets of parameters depending on where you want the Iago test jobs to run.
  The launching modes are exclusive with each other (You should choose only one set of parameters from one of the following modes).

Local Mode Launcher Parameters
==============================

You can launch your test locally by specifying `-env.local`.

+-----------------------------------+---------------+----------------------------------------------------------+
| Parameter                         | Default Value | Description                                              |
+===================================+===============+==========================================================+
| ``env.local``                     | **N/A**       | A boolean value that enables the local environment.      |
|                                   | (required)    | Exclusive with other environments.                       |
|                                   +---------------+----------------------------------------------------------+
|                                   | .. code-block:: bash                                                     |
|                                   |                                                                          |
|                                   |   -env.local                                                             |
+-----------------------------------+---------------+----------------------------------------------------------+
| ``env.local.feederAdminPort``     | `9993`        | Port on which to listen for the Iago feeder job's admin  |
|                                   |               | interface.                                               |
|                                   +---------------+----------------------------------------------------------+
|                                   | .. code-block:: bash                                                     |
|                                   |                                                                          |
|                                   |   -env.local.feederAdminPort=9993                                        |
+-----------------------------------+---------------+----------------------------------------------------------+
| ``env.local.serverAdminPort``     | `9992`        | Port on which to listen for the Iago server job's admin  |
|                                   |               | interface.                                               |
|                                   +---------------+----------------------------------------------------------+
|                                   | .. code-block:: bash                                                     |
|                                   |                                                                          |
|                                   |   -env.local.serverAdminPort=9992                                        |
+-----------------------------------+---------------+----------------------------------------------------------+
| ``healthCheckDuration``           | `1.minute`    | The duration to run health check.                        |
|                                   +---------------+----------------------------------------------------------+
|                                   | .. code-block:: bash                                                     |
|                                   |                                                                          |
|                                   |   -healthCheckDuration=2.minute                                          |
+-----------------------------------+--------------------------------------------------------------------------+

Aurora Mode Launcher Parameters
===============================

In Aurora mode the launcher will deploy Iago feeder and server jobs to Mesos.

+-----------------------------------+---------------+----------------------------------------------------------+
| Parameter                         | Default Value | Description                                              |
+===================================+===============+==========================================================+
| ``env.aurora``                    | **N/A**       | A boolean value that enables the Aurora environment.     |
|                                   | (required)    | Exclusive with other environments.                       |
|                                   +---------------+----------------------------------------------------------+
|                                   | .. code-block:: bash                                                     |
|                                   |                                                                          |
|                                   |   -env.aurora                                                            |
+-----------------------------------+---------------+----------------------------------------------------------+
| ``env.aurora.config``             | **N/A**       | The aurora configuration file for deploying feeder and   |
|                                   | (required)    | server jobs.                                             |
|                                   |               | `"src/main/resources/iago.aurora"` is an example aurora  |
|                                   |               | config file to use. You can also define your own aurora  |
|                                   |               | configuration file based on it.                          |
|                                   +---------------+----------------------------------------------------------+
|                                   | .. code-block:: bash                                                     |
|                                   |                                                                          |
|                                   |   -env.aurora.config="/path/to/iago.aurora"                              |
+-----------------------------------+---------------+----------------------------------------------------------+
| ``env.aurora.cluster``            | **N/A**       | The Aurora cluster (or "data center") in which to        |
|                                   | (required)    | schedule Iago jobs.                                      |
|                                   +---------------+----------------------------------------------------------+
|                                   | .. code-block:: bash                                                     |
|                                   |                                                                          |
|                                   |   -env.aurora.cluster="dc"                                               |
+-----------------------------------+---------------+----------------------------------------------------------+
| ``env.aurora.env``                | **N/A**       | The Aurora environment (e.g. devel, staging, prod)       |
|                                   | (required)    | in which to schedule Iago jobs.                          |
|                                   +---------------+----------------------------------------------------------+
|                                   | .. code-block:: bash                                                     |
|                                   |                                                                          |
|                                   |   -env.aurora.env="devel"                                                |
+-----------------------------------+---------------+----------------------------------------------------------+
| ``env.aurora.maxPerHost``         | **N/A**       | Limit feeder and server jobs to this number per Mesos    |
|                                   | (required)    | host.                                                    |
|                                   +---------------+----------------------------------------------------------+
|                                   | .. code-block:: bash                                                     |
|                                   |                                                                          |
|                                   |   -env.aurora.maxPerHost=1                                               |
+-----------------------------------+---------------+----------------------------------------------------------+
| ``env.aurora.noKillBeforeLaunch`` | **N/A**       | Do not kill any existing tasks before launch.            |
|                                   | (required)    |                                                          |
|                                   +---------------+----------------------------------------------------------+
|                                   | .. code-block:: bash                                                     |
|                                   |                                                                          |
|                                   |   -env.aurora.noKillBeforeLaunch=false                                   |
+-----------------------------------+---------------+----------------------------------------------------------+
| ``env.aurora.feederDiskInBytes``  | **N/A**       | Amount of disk in bytes to use for feeder jobs.          |
|                                   | (required)    |                                                          |
|                                   +---------------+----------------------------------------------------------+
|                                   | .. code-block:: bash                                                     |
|                                   |                                                                          |
|                                   |   -env.aurora.feederDiskInBytes=536870912                                |
+-----------------------------------+---------------+----------------------------------------------------------+
| ``env.aurora.feederNumCpus``      | **N/A**       | Number of CPUs to use for feeder jobs.                   |
|                                   | (required)    |                                                          |
|                                   +---------------+----------------------------------------------------------+
|                                   | .. code-block:: bash                                                     |
|                                   |                                                                          |
|                                   |   -env.aurora.feederNumCpus=1                                            |
+-----------------------------------+---------------+----------------------------------------------------------+
| ``env.aurora.feederNumInstances`` | **N/A**       | Number of feeder jobs to schedule.                       |
|                                   | (required)    |                                                          |
|                                   +---------------+----------------------------------------------------------+
|                                   | .. code-block:: bash                                                     |
|                                   |                                                                          |
|                                   |   -env.aurora.feederNumInstances=1                                       |
+-----------------------------------+---------------+----------------------------------------------------------+
| ``env.aurora.feederRamInBytes``   | **N/A**       | Amount of RAM in bytes to use for feeder jobs.           |
|                                   | (required)    |                                                          |
|                                   +---------------+----------------------------------------------------------+
|                                   | .. code-block:: bash                                                     |
|                                   |                                                                          |
|                                   |   -env.aurora.feederRamInBytes=536870912                                 |
+-----------------------------------+---------------+----------------------------------------------------------+
| ``env.aurora.jvmCmd``             | **N/A**       | Java command used to launch Iago test jar, minus the     |
|                                   | (required)    | main class name.                                         |
|                                   +---------------+----------------------------------------------------------+
|                                   | .. code-block:: bash                                                     |
|                                   |                                                                          |
|                                   |   -env.aurora.jvmCmd="java -cp 'target/iago-test.jar'"                   |
+-----------------------------------+---------------+----------------------------------------------------------+
| ``env.aurora.role``               | **N/A**       | The Aurora role for which to schedule Iago jobs.         |
|                                   | (required)    |                                                          |
|                                   +---------------+----------------------------------------------------------+
|                                   | .. code-block:: bash                                                     |
|                                   |                                                                          |
|                                   |   -env.aurora.role="myrole"                                              |
+-----------------------------------+---------------+----------------------------------------------------------+
| ``env.aurora.serverDiskInBytes``  | **N/A**       | Amount of disk in bytes to use for server jobs.          |
|                                   | (required)    |                                                          |
|                                   +---------------+----------------------------------------------------------+
|                                   | .. code-block:: bash                                                     |
|                                   |                                                                          |
|                                   |   -env.aurora.serverDiskInBytes=536870912                                |
+-----------------------------------+---------------+----------------------------------------------------------+
| ``env.aurora.serverNumCpus``      | **N/A**       | Number of CPUs to use for server jobs.                   |
|                                   | (required)    |                                                          |
|                                   +---------------+----------------------------------------------------------+
|                                   | .. code-block:: bash                                                     |
|                                   |                                                                          |
|                                   |   -env.aurora.serverNumCpus=1                                            |
+-----------------------------------+---------------+----------------------------------------------------------+
| ``env.aurora.serverRamInBytes``   | **N/A**       | Amount of RAM in bytes to use for server jobs.           |
|                                   | (required)    |                                                          |
|                                   +---------------+----------------------------------------------------------+
|                                   | .. code-block:: bash                                                     |
|                                   |                                                                          |
|                                   |   -env.aurora.serverRamInBytes=536870912                                 |
+-----------------------------------+--------------------------------------------------------------------------+


Sending Large Messages
~~~~~~~~~~~~~~~~~~~~~~

By default, the Iago feeder sends 1000 messages at a time to each connected Iago server until the server has 5 seconds worth of data. This is a good strategy when messages are small (less than a kilobyte). When messages are large, the Iago server will run out of memory. Consider an average message size of 100k, then the feeder will be maintaining an output queue for each connected Iago server of 100 million bytes. For the Iago server, consider a request rate of 2000, then 2000 * 5 * 100k = 1 gigabytes (at least). The following parameters help with large messages:

1. ``batchSize`` in `Common Parameters <#common-parameters>`__
2. ``cachedSeconds`` in `Iago Feeder Parameters <#iago-feeder-parameters>`__

