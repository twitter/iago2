Iago2 Migration Guide
=====================

Iago, despite being a vital piece of the application development and testing lifecycle, was written long ago and contains many outmoded design patterns. This includes parsing and eval-ing Scala config files, unnecessarily complex build and deployment steps, not running on TwitterServer or any modern application framework, and lots of blocking code and performance issues.

To solve these issues, Iago2 was developed based on Iago. It eliminates the old eval-ing Scala configuration syntax, and adopts TwitterServer with all its goodies such as metrics, async worker pools, and flag-based configuration.

Migrate Iago test to Iago2 test
-------------------------------

#. Download and unpack the Iago2 distribution. "mvn install -Dmaven.test.skip=true" to install Iago2 in your local maven repository.

#. Add

   .. code-block:: xml

   <dependency>
      <groupId>com.twitter</groupId>
      <artifactId>iago2</artifactId>
      <version>2.0.0</version>
    </dependency>

   to your load test project's dependencies list. `Here <https://github.com/twitter/iago2/tree/master/examples/echo>`__ is an example.

#. Import "com.twitter.iago.?" instead of "com.twitter.parrot.?" in your code.

#. If you defined custom logic of processing lines before, e.g. a test class extends ``RecordProcessor``, make sure to create a config that extends ``FinagleParrotConfig`` (for Http service) or ``ThriftParrotConfig`` (for Thrift service), and specify it using the ``config`` flag in your bash script. See `Implementing Your Test <implementing_tests.html>`__

#. Convert your launcher config (the file implements ``ParrotLauncherConfig``) to a script. Iag2 uses Flag instead of Eval to take parameters. See `Configuring and Launching Your Test <configuring_tests.html>`__

   * Below is a table for mapping some of the Iago parameters to Iago2 parameters.

   +-------------------------------------------+-------------------------------------------+
   | Iago Launcher Config Parameter            | Iago2 Flag                                |
   +===========================================+===========================================+
   | log                                       | -inputLog                                 |
   +-------------------------------------------+-------------------------------------------+
   | numFeederInstances                        | -env.aurora.feederNumInstances            |
   +-------------------------------------------+-------------------------------------------+
   | createDistribution                        | Please override the distribution method of|
   |                                           | FinagleParrotConfig or ThriftParrotConfig |
   +-------------------------------------------+-------------------------------------------+
   | victims                                   | -victimDest, -victimServerSet,            |
   |                                           | -victimHostPort, -victimZk, -victimZkPort |
   +-------------------------------------------+-------------------------------------------+
   | customLogSource                           | -logSource                                |
   +-------------------------------------------+-------------------------------------------+
   | traceLevel                                | -log.level                                |
   +-------------------------------------------+-------------------------------------------+

   * `Here <https://github.com/twitter/iago2/tree/master/examples/echo/src/scripts/echo-loadtest.sh>`__ is an example script.
