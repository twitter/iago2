/* Copyright 2015 Twitter, Inc.

Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in
compliance with the License.  You may obtain a copy of the License at

http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software distributed under the License is
distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or
implied.  See the License for the specific language governing permissions and limitations under the
License.
 */
package com.twitter.iago.server

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.scala.DefaultScalaModule
import com.twitter.app.{App, Flag}
import com.twitter.common.metrics.{Metrics, MetricProvider}
import com.twitter.conversions.storage._
import com.twitter.finagle._
import com.twitter.logging._
import com.twitter.util.{Future, FuturePool, Time}
import scala.collection.JavaConverters._
import scala.collection.Map
import com.twitter.finagle.zipkin.thriftscala.{LogEntry, Scribe}

/** Report stats directly to scribe or directly to a log without being polled via the admin
 * interface. This was inspired by com.twitter.svcsvc.ingester.Stats */
trait AutoStats {
  self: App =>

  private[this] val statsScribeF: Flag[String] =
    flag("stats.scribe", "inet!localhost:1463", "Wily name to scribe stats to")

  private[this] val statsSourceF: Flag[String] =
    flag("stats.source", "", "Stats source name (like a hostname or instance id)")

  private[this] val logLocallyF =
    flag("stats.logLocally", false, "if true then log statistics locally, otherwise log to scribe")

  private[this] val localStatsLogNameF =
    flag("stats.localStatsLogName", "parrot-server-stats.log", "name for local statistics log")

  @volatile
  private[this] var go = true

  private[this] val lg = Logger(getClass.getSimpleName)

  private[this] val mapper = new ObjectMapper
  mapper.registerModule(DefaultScalaModule)

  private[this] type MetricMap = Map[String, Number]

  private[this] def holdTheNaN(metrics: MetricMap): MetricMap =
    metrics.filterNot {
      case (_, n) =>
        n.equals(Double.NaN) || n.equals(Float.NaN)
    }

  private[this] case class Sample(service: String, source: String, time: Time, metrics: MetricMap) {

    def toCuckoo: Map[String, Any] =
      metrics ++ Map("service" -> service, "source" -> source, "timestamp" -> time.inSeconds)

    def toJson: String =
      mapper.writeValueAsString(toCuckoo)
  }

  private[this] def sample[Rsp](
    service: String,
    source: String,
    metrics: MetricProvider = Metrics.root
  ): Filter[Unit, Rsp, Sample, Rsp] =
    Filter.mk {
      case (_, store) =>
        val stats = metrics.sample().asScala
        val sample = Sample(service, source, Time.now, holdTheNaN(stats))
        store(sample)
    }

  private[this] def toScribe(
    scribe: Scribe.FutureIface,
    category: String = "cuckoo_json",
    pool: FuturePool = FuturePool.immediatePool
  ): Service[Sample, Unit] = Service.mk { sample =>
    pool {
      LogEntry(category, sample.toJson) :: Nil
    }.flatMap {
      scribe.log(_) map { _ =>
        } onFailure {
        lg.error(_, "scribe")
      }
    }
  }

  private[this] def toFile(): () => Service[Sample, Unit] = {
    val statsLogger = Logger.get("stats")
    () =>
      Service.mk { sample =>
        statsLogger.log(Level.INFO, "%s", sample.toJson)
        Future.value(())
      }
  }

  private[this] def ignoreErrors[Req]: Filter[Req, Unit, Req, Unit] =
    Filter.mk {
      case (req, service) =>
        service(req) handle {
          case e: ChannelWriteException =>
          case e: RequestException =>
          case e: TimeoutException =>
        }
    }

  private[this] def loop(serviceName: String) {
    val statsSource = statsSourceF()
    val logLocally = logLocallyF()

    val logIt: () => Service[Sample, Unit] =
      if (logLocally) {
        Logger.configure(
          List(
            new LoggerFactory(
              node = "stats",
              level = Some(Level.INFO),
              useParents = false,
              handlers = List(
                FileHandler(
                  filename = localStatsLogNameF(),
                  rollPolicy = Policy.MaxSize(10.megabytes),
                  rotateCount = 6
                )
              )
            )
          )
        )
        toFile()
      } else {
        val scribeDest = "scribe=" + statsScribeF()
        lg.info("the scribe destination for statistics collection is %s", scribeDest)
        () =>
          toScribe(Thrift.client.newIface[Scribe.FutureIface](scribeDest))
      }

    var startTime = System.currentTimeMillis()
    val sleepInterval = 1000 * (if (logLocally) 1 else 60)
    while (go) {

      // sleep for an interval, correcting for drift

      startTime += sleepInterval
      val delta = startTime - System.currentTimeMillis()
      if (delta > 0) {

        // write a sample

        val stats = sample(serviceName, statsSource) andThen
          ignoreErrors[Sample] andThen
          logIt()
        stats()
        Thread.sleep(delta)
      }
    }
  }

  /** make a loop in its own thread to log statistics */
  def autostat(serviceName: String) {
    if (logLocallyF()) {
      val t = new Thread(new Runnable {
        def run() {
          try {
            loop(serviceName)
          } catch {
            case t: Throwable =>
              lg.error(t, "while attempting to log statistics")
          }
        }
      })
      t.start()
    }
  }

  postmain {
    go = false
  }
}
