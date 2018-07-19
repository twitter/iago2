<a name="Top"></a>

# Iago, A Load Generator

## Documentation

* <a href="https://github.com/twitter/iago2/blob/master/docs/quick_start.rst">Getting Started</a>
* <a href="https://github.com/twitter/iago2/blob/master/docs/overview.rst">Overview</a>
* <a href="https://github.com/twitter/iago2/blob/master/docs/architecture.rst">Architecture</a>
* <a href="https://github.com/twitter/iago2/blob/master/docs/implementing_tests.rst">Implementing Your Test</a>
* <a href="https://github.com/twitter/iago2/blob/master/docs/configuring_tests.rst">Configuring and Launching Your Test</a>
* <a href="https://github.com/twitter/iago2/blob/master/docs/weighted_requests.rst">Weighted Requests</a>
* <a href="https://github.com/twitter/iago2/blob/master/docs/metrics.rst">Metrics</a>
* <a href="https://github.com/twitter/iago2/blob/master/docs/tracing.rst">Tracing</a>
* <a href="https://github.com/twitter/iago2/blob/master/docs/using_iago_library.rst">Using Iago2 as a Library</a>
* <a href="https://github.com/twitter/iago2/blob/master/docs/migration.rst">Iago to Iago2 Migration Guide</a>
* <a href="https://github.com/twitter/iago2/blob/master/docs/contributing.rst">Contributing to Iago2</a>
* <a href="https://github.com/twitter/iago2/blob/master/docs/faq.rst">FAQ</a>

## Reporting Issues

* Sensitive security issues: please submit your report to <a href="https://hackerone.com/twitter">Twitter HackerOne</a>
* Non-security issues: please open an issue via <a href="https://github.com/twitter/iago2/issues">github</a>

## Contributing to Iago2

Iago2 is open source, hosted on Github <a href="https://github.com/twitter/iago2">here</a>.
If you have a contribution to make, please fork the repo and submit a pull request.
And please check out our Code of Conduct <a href="https://github.com/twitter/code-of-conduct/blob/master/code-of-conduct.md">here</a>.

## License

Iago2 is under <a href="https://github.com/twitter/iago2/blob/master/LICENSE">Apache License 2.0</a>

## ChangeLog

2018-07-23

* removed RecordProcessor.processLines

2018-07-06

* replaced remaining com.twitter.logging.Logging with com.twitter.server.logging.Logging
* modified the types of feederRamInBytes, feederDiskInBytes, serverRamInBytes and serverDiskInBytes to "Long" in iago launcher AuroraMode
* properly close file source in com.twitter.iago.feeder.LogSourceImpl (default LogSource implementation)
* added documentation table of contents and license link in README.md for easier access

2018-05-31

* removed Kestrel transport
* removed dependency com.twitter.util-logging
* added an example of aurora mode launching script
* updated the documentation

2018-04-10

* initial iago2 source code import

[Top](#Top)
