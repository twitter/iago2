/* Copyright 2015 Twitter, Inc.

Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in
compliance with the License.  You may obtain a copy of the License at

http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software distributed under the License is
distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or
implied.  See the License for the specific language governing permissions and limitations under the
License.
 */

package com.twitter.iago.launcher

import java.util.concurrent.CancellationException

import com.fasterxml.jackson.databind.ObjectMapper
import com.twitter.app.Flag
import com.twitter.conversions.time._
import com.twitter.finagle.http.{Request, Response}
import com.twitter.finagle.{Service, Http, Thrift}
import com.twitter.finagle.service.TimeoutFilter
import com.twitter.finagle.util.DefaultTimer
import com.twitter.iago.thriftscala.ParrotServerService
import com.twitter.util._

trait LocalMode extends LauncherMain {
  val localMode =
    flag[Boolean]("env.local", "Enable the local environment. Exclusive with other environments.")
  val feederAdminPort = flag[Int](
    "env.local.feederAdminPort",
    9993,
    "Port on which to listen for the local feeder job's admin interface."
  )
  val serverAdminPort = flag[Int](
    "env.local.serverAdminPort",
    9992,
    "Port on which to listen for the local server job's admin interface."
  )
  val serverServicePort = flag[Int](
    "env.local.serverServicePort",
    "Port on which to listen for the local server job's service (Thrift) interface. Integer alias for -service.port"
  )
  val healthCheckDurationF = flag(
    "healthCheckDuration",
    1.minute,
    "The duration to run health check. Only used in local mode."
  )

  def localFeederFlagMap = Map("admin.port" -> s":${feederAdminPort()}")
  def localServerFlagMap =
    Map("admin.port" -> s":${serverAdminPort()}")

  def localLaunch(feederFlags: Seq[Flag[_]], serverFlags: Seq[Flag[_]]): Unit = {
    missingFlagCheck(List(servicePortF, classPath))

    // override any flags here
    val feederFlagMap = flagsToFlagMap(feederFlags) +
      (numInstancesF.name -> 1.toString) +
      (parrotHostsF.name -> servicePortF())
    val serverFlagMap = flagsToFlagMap(serverFlags)

    val futurePool = FuturePool.interruptibleUnboundedPool

    // launch single local server instance
    val serverCmd =
      List("java", "-cp", classPath(), "com.twitter.iago.server.Main")
    val localServerArgs = flagMapToArgs(localServerFlagMap)
    val serverArgs = flagMapToArgs(serverFlagMap)
    val serverCmdWithArgs = serverCmd ::: localServerArgs ::: serverArgs
    val serverProcess = runBackgroundCmd(serverCmdWithArgs)
    val serverFuture = futurePool { serverProcess.exitValue() }
      .onSuccess { rc =>
        log.info(s"Server terminated with exit code: $rc")
      }
      .onFailure { e =>
        serverProcess.destroy()
      }

    // launch single local feeder instance
    val feederCmd =
      List("java", "-cp", classPath(), "com.twitter.iago.feeder.Main")
    val localFeederArgs = flagMapToArgs(localFeederFlagMap)
    val feederArgs = flagMapToArgs(feederFlagMap)
    val feederCmdWithArgs = feederCmd ::: localFeederArgs ::: feederArgs
    val feederProcess = runBackgroundCmd(feederCmdWithArgs)
    val feederFuture = futurePool { feederProcess.exitValue() }
      .onSuccess { rc =>
        log.info(s"Feeder terminated with exit code: $rc")
      }
      .onFailure { e =>
        feederProcess.destroy()
      }

    val firstExit = Future.select(List(serverFuture, feederFuture)).onSuccess {
      case (_, running: Seq[Future[_]]) => running.foreach { _.raise(new CancellationException()) }
    }

    Await.result(firstExit)
  }

  def localKill(feederFlags: Seq[Flag[_]], serverFlags: Seq[Flag[_]]): Unit = {
    missingFlagCheck(List(feederAdminPort, serverAdminPort))

    // send abortabortabort to feeder's admin interface
    val killFeederCmd = List("curl", s"http://localhost:${feederAdminPort()}/abortabortabort")
    runCmd(killFeederCmd)

    // send abortabortabort to server's admin interface
    val killServerCmd = List("curl", s"http://localhost:${serverAdminPort()}/abortabortabort")
    runCmd(killServerCmd)

    // TODO: wait then kill still-running processes
  }

  def localAdjust(feederFlags: Seq[Flag[_]], serverFlags: Seq[Flag[_]]): Unit = {
    missingFlagCheck(List(requestRateF, servicePortF))

    val localServerIface = Thrift.client.newServiceIface[ParrotServerService.ServiceIface](
      dest = servicePortF(),
      label = "local_feeder"
    )

    val setRate = new TimeoutFilter(10.seconds, DefaultTimer.twitter) andThen localServerIface.setRate
    Await.result(
      setRate(ParrotServerService.SetRate.Args(requestRateF()))
        .onSuccess { _ =>
          log.info(s"Adjusted rate [to ${requestRateF()}].")
        }
        .onFailure { e =>
          log.error(s"Failed to adjust rate [to ${requestRateF()}]:\n$e")
        }
    )
  }

  def localHealthCheck(serverFlags: Seq[Flag[_]], feederFlags: Seq[Flag[_]]) = {
    val iagoServerClient = Http.newService(s"localhost:${serverAdminPort()}")
    val iagoFeederClient = Http.newService(s"localhost:${feederAdminPort()}")

    val healthCheckDuration = healthCheckDurationF()
    val healthCheckDeadline = healthCheckDuration.fromNow

    while (Time.now < healthCheckDeadline) {
      checkJvmUptime(iagoServerClient, iagoFeederClient)
      log.info(
        s"Metric jvm/uptime looks healthy for both Iago server and feeder. ${healthCheckDeadline - Time.now} left..."
      )
      // Todo: add health checks for other metrics. e.g. gc, success rate, request rate, queue depth etc.
      Thread.sleep(2000L)
    }

    log.info("Finished. Health check pass. This load test is running healthily.")
  }

  private def checkJvmUptime(
    iagoServerClient: Service[Request, Response],
    iagoFeederClient: Service[Request, Response]
  ) {

    val request = Request("admin/metrics", ("m", "jvm/uptime"))

    def checkJvmUptimeUtil(client: Service[Request, Response]) = {
      val responseStr = client(request).map(_.getContentString())
      val mapper = new ObjectMapper()
      val node = responseStr.map { mapper.readTree(_).get(0) }
      val jvmUptime = node.map { n =>
        Option(n.get("value"))
      }
      jvmUptime.transform {
        case Return(None) => throw new RuntimeException("jvm/uptime is not defined")
        case Return(Some(value)) if value.asInt() == 0 =>
          throw new RuntimeException("jvm/uptime is down to 0")
      }
    }

    // Check Iago server
    checkJvmUptimeUtil(iagoServerClient)
    // Check Iago feeder
    checkJvmUptimeUtil(iagoFeederClient)
  }

  val subCmds = Map(
    "isHealthy" -> SubCommand(
      "isHealthy",
      "Check if this load test is running healthily.",
      localHealthCheck
    )
  )

  addMode(
    Mode(
      localMode,
      launch = localLaunch,
      kill = localKill,
      adjust = localAdjust,
      subCommands = subCmds
    )
  )

  premain {
    if (serverServicePort.isDefined) { servicePortF.parse(s":${serverServicePort()}") }
  }
}
