/*
Copyright 2012 Twitter, Inc.

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
 */
package com.twitter.iago.server

import java.io.{File, FileReader, IOException}
import java.util.concurrent.Executors

import com.twitter.conversions.time._
import com.twitter.finagle.stats.{DefaultStatsReceiver, StatsReceiver}
import com.twitter.finagle.util.{DefaultTimer, InetSocketAddressUtil}
import com.twitter.finagle.{Namer, Path}
import com.twitter.iago.thriftscala.{ParrotLog, ParrotServerService, ParrotState, ParrotStatus}
import com.twitter.iago.util.{ParrotClusterImpl, PrettyDuration}
import com.twitter.iago.{FinagleDestVictim, ParrotServerFlags}
import com.twitter.logging.{Level, Logger}
import com.twitter.util._

import scala.Some
import scala.collection.{mutable, parallel}
import scala.xml.{Elem, Node}

object ParrotServer {
  private[this] val numThreads = parallel.availableProcessors
  protected val futurePool = FuturePool.interruptible(Executors.newFixedThreadPool(numThreads))

  implicit class ChainableFuture(val f: Future[Unit]) extends AnyVal {
    def andThen(next: Future[Unit]): Future[Unit] = f.transform { case _ => next }
    def andThenRun(next: => Unit): Future[Unit] =
      f.transform { case _ => FuturePool.immediatePool(next) }
    def whenDoneRun(next: => Unit): Future[Unit] =
      f.before { FuturePool.immediatePool(next) }
  }
}

class ParrotServer(
  val config: ParrotServerFlags,
  val futurePool: FuturePool = ParrotServer.futurePool,
  val statsReceiver: StatsReceiver = DefaultStatsReceiver
) extends ParrotServerService.FutureIface
    with Closable {
  import ParrotServer._

  private[this] val log = Logger.get(getClass)
  private[this] var status: ParrotStatus = ParrotStatus(status = Some(ParrotState.Unknown))
  private[this] val shuttingDown = new Promise[Unit]
  val done = new Promise[Unit]

  private[this] lazy val thriftServer = config.thriftServer
  private[this] lazy val clusterService = new ParrotClusterImpl(config)
  private[this] lazy val transport = config.parrot.transport
  private[this] lazy val queue = config.parrot.queue
  private[this] val scopedStats = statsReceiver.scope("server")
  private[this] val recordsRead = scopedStats.counter("records_read")

  def start(): Future[Unit] = {
    wilyCheck()
    status = status.copy(status = Some(ParrotState.Starting))
    transport.start()
    // TODO convert the rest of these startup functions to futures/async.
    queue.start() whenDoneRun {
      log.info("using record processor %s", config.parrot.recordProcessor.getClass().getName())
      // recordProcessor should be started before starting the thrift server port, otherwise
      // the feeder will start sending requests even before recordProcessor is ready.
      config.parrot.recordProcessor.start()
      val serviceAddress = InetSocketAddressUtil.parseHosts(config.servicePortF()).head
      thriftServer.start(this, serviceAddress)
      clusterService.start(serviceAddress.getPort)
      status = status.copy(status = Some(ParrotState.Running))
    }
  }

  /** indicate when the first record has been received */
  def firstRecordReceivedFromFeeder: Future[Unit] = queue.firstRecordReceivedFromFeeder

  def close(deadline: Time)(implicit timer: Timer = DefaultTimer.twitter): Future[Unit] = {
    shutdown().raiseWithin(timeout = deadline - Time.now)(timer = timer)
  }

  def close(deadline: Time): Future[Unit] = {
    shutdown().raiseWithin(timeout = deadline - Time.now)(timer = DefaultTimer.twitter)
  }

  def shutdown(): Future[Unit] = {
    if (shuttingDown.updateIfEmpty(Return.Unit)) {
      status = status.copy(status = Some(ParrotState.Stopping))
      log.setLevel(Level.ALL)
      log.trace("ParrotServer shutting down.")
      queue
        .shutdown()
        .onFailure { e =>
          log.error(s"Error shutting down queue:\n${e}")
        }
        .ensure {
          log.trace("Transport shutting down.")
        }
        .andThen {
          shutdownTransport
        }
        .andThenRun {
          status = status.copy(status = Some(ParrotState.Shutdown))
          log.trace("RecordProcessor shutting down.")
          config.parrot.recordProcessor.shutdown()
        }
        .ensure {
          log.trace("ThriftServer shutting down.")
        }
        .andThen {
          thriftServer.shutdown()
        }
        .andThenRun {
          log.trace("ParrotServer shut down.")
          done.setDone()
        }
    }
    done
  }

  private[this] def shutdownTransport: Future[Unit] = {
    val elapsed = Stopwatch.start()
    transport.shutdown().ensure {
      log.trace("transport shut down in %s", PrettyDuration(elapsed()))
    }
  }

  def setRate(newRate: Int): Future[Unit] = {
    log.info("setting rate %d RPS", newRate)
    queue.setRate(newRate)
  }

  def sendRequest(lines: Seq[String]): Future[ParrotStatus] = {
    futurePool {
      log.info(s"Processing ${lines.size} new lines.")
      lines.par.foreach { line =>
        config.parrot.recordProcessor.processLine(line)
      }
      recordsRead.incr(lines.size)
    }.flatMap { _ =>
      getStatus.map(_.copy(linesProcessed = Some(lines.size)))
    }
  }

  def getStatus(): Future[ParrotStatus] = Future {
    ParrotStatus(
      queueDepth = Some(queue.queueDepth.toDouble),
      status = status.status,
      totalProcessed = Some(queue.totalProcessed)
    )
  }

  def pause(): Future[Unit] = {
    log.debug("pausing server")
    queue.pause()
    status = status.copy(status = Some(ParrotState.Paused))
    Future.Unit
  }

  def resume(): Future[Unit] = {
    log.debug("resuming server")
    queue.resume()
    status = status.copy(status = Some(ParrotState.Running))
    Future.Unit
  }

  def getRootThreadGroup = {
    var result = Thread.currentThread.getThreadGroup
    while (result.getParent != null) {
      result = result.getParent
    }
    result
  }

  def listThreads(group: ThreadGroup, container: Node): Node = {
    val result = <g name={group.getName} class={group.getClass.toString}/>
    val threads = new Array[Thread](group.activeCount * 2 + 10)
    val nt = group.enumerate(threads, false)
    var threadNodes = mutable.ListBuffer[Elem]()
    for (i <- 0 until nt) {
      val t = threads(i)
      var methods = mutable.ListBuffer[Elem]()
      t.getStackTrace foreach { e =>
        methods += <method>
          {e}
        </method>
      }
      threadNodes += <foo name={t.getName} class={t.getClass.toString}>
        {methods}
      </foo>
    }
    var ng = group.activeGroupCount
    val groups = new Array[ThreadGroup](ng * 2 + 10)
    ng = group.enumerate(groups, false)
    for (i <- 0 until ng) {
      listThreads(groups(i), result)
    }
    <g name={group.getName} class={group.getClass.toString}>
      {result}
    </g>
  }

  def fetchThreadStacks(): Future[String] = {
    val result = <root/>
    listThreads(getRootThreadGroup, result)
    Future.value(result.toString)
  }

  private def wandering(fileName: String): Boolean = {
    val f = new File(fileName)
    val p = f.getParent()
    if (p == null) return false
    val there = try {
      new File(p).getCanonicalPath
    } catch {
      case e: IOException =>
        log.info("bad log file requested: %s: %s", fileName, e)
        throw new RuntimeException("Can't find parent of log file %s".format(fileName), e)
    }
    there == new File(".").getCanonicalPath
  }

  def getLog(offset: Long, length: Int, fileName: java.lang.String): Future[ParrotLog] = Future {

    if (wandering(fileName))
      throw new RuntimeException("can only reference files at the top of this sandbox")

    val fl = new File(fileName)
    val sz = fl.length()

    // an offset of -1 means please position me near the end
    val tail = 5000
    val (theLen, theOffset) = if (offset == -1) (tail, sz - tail) else (length, offset)

    val fr = new FileReader(fl)
    val off = math.min(sz, math.max(0, theOffset))
    fr.skip(off)
    val len = math.max(0, math.min(theLen, math.min(Int.MaxValue.toLong, sz - off).toInt))
    val buf = new Array[Char](len)
    fr.read(buf)
    fr.close()
    ParrotLog(off, len, new String(buf))
  }

  private def wilyCheck(): Unit = {
    // log the victim destination looked up against the installed dtab, if applicable
    config.victim match {
      case FinagleDestVictim(dest) =>
        try {
          val boundDest = Namer.resolve(Path.read(dest))
          log.info(s"Victim destination: ${boundDest.sample()}")
        } catch {
          case e: java.lang.IllegalArgumentException =>
            log.error(s"Malformed victim finagle destination: $dest")
        }
      case _ =>
    }
  }
}
