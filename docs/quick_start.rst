Getting Started
---------------

Please join `iago-users@googlegroups.com <https://groups.google.com/d/forum/iago-users>`__ for updates and to ask questions.

If you are already familiar with the Iago Load Generation tool, follow these steps to get started; otherwise, start with the `Iago Philosophy <http://twitter.github.com/iago/philosophy.html>`__, also known as "Why Iago?". For questions, please contact `iago-users@googlegroups.com <https://groups.google.com/d/forum/iago-users>`__.


Iago Prerequisites
~~~~~~~~~~~~~~~~~~

1. Download and unpack the Iago2 distribution. We support Scala 2.11 and recommend you clone the latest `master <https://github.com/twitter/iago2/zipball/master>`__.

2. Read the documentation on `github <https://github.com/twitter/iago2/tree/master/docs>`__.


Preparing Your Test
~~~~~~~~~~~~~~~~~~~

1. Identify your transaction source; see `Transaction Requirements <overview.rst#transaction-requirements>`__ and `Sources of Transactions <overview.rst#sources-of-transactions>`__ for more information.

2. In Scala, extend the Iago server's ``RecordProcessor`` or ``ThriftRecordProcessor`` class, or in Java, extend ``LoadTest`` or ``ThriftLoadTest``; then extend ``FinagleParrotConfig`` or ``ThriftParrotConfig`` class; see `Implementing Your Test <implementing_tests.rst>`__ for more information.

3. Create a shell script to build and execute your test with appropriate arguments; see `Configuring and Launching Your Test <configuring_tests.rst>`__ for more information.


Executing Your Test
~~~~~~~~~~~~~~~~~~~

Launch Iago with::
  
  java -cp IAGO_JAR com.twitter.iago.launcher.Main launch YOUR_ARGS

This will create the Iago processes for you and configure it to use your transactions. To kill a running job, use `kill` cmd instead::

  java -cp IAGO_JAR com.twitter.iago.launcher.Main kill YOUR_ARGS

See `Configuring and Launching Your Test <configuring_tests.rst>`__ for more information.
