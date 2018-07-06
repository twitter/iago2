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

import com.twitter.app.{Flags, Flag}

trait AuroraMode extends LauncherMain {
  val localFlags = new Flags("auroraMode", includeGlobal = false, failFastUntilParsed = true)

  val auroraMode = localFlags[Boolean](
    "env.aurora",
    "Enable the Aurora environment. Exclusive with other environments."
  )
  val auroraConfig = localFlags[String](
    "env.aurora.config",
    "The aurora configuration file to use for feeder and server jobs."
  )
  val cluster =
    localFlags[String]("env.aurora.cluster", "The Aurora cluster in which to schedule jobs.")
  val role = localFlags[String]("env.aurora.role", "The Aurora role for which to schedule jobs.")
  val env = localFlags[String](
    "env.aurora.env",
    "The Aurora environment (e.g. devel, staging, prod) in which to schedule jobs."
  )
  val maxPerHost = localFlags[Int](
    "env.aurora.maxPerHost",
    "Limit feeder and server jobs to this number per mesos host."
  )
  val noKillBeforeLaunch = localFlags[Boolean](
    "env.aurora.noKillBeforeLaunch",
    false,
    "Do not kill any existing tasks before launch"
  )
  val feederNumInstances =
    localFlags[Int]("env.aurora.feederNumInstances", "Number of feeder jobs to schedule.")
  val feederNumCpus =
    localFlags[Double]("env.aurora.feederNumCpus", "Number of CPUs to use for feeder jobs.")
  val feederRamInBytes =
    localFlags[Long]("env.aurora.feederRamInBytes", "Amount of RAM in bytes to use for feeder jobs.")
  val feederDiskInBytes = localFlags[Long](
    "env.aurora.feederDiskInBytes",
    "Amount of disk in bytes to use for feeder jobs."
  )
  val serverNumCpus =
    localFlags[Double]("env.aurora.serverNumCpus", "Number of CPUs to use for server jobs.")
  val serverRamInBytes =
    localFlags[Long]("env.aurora.serverRamInBytes", "Amount of RAM in bytes to use for server jobs.")
  val serverDiskInBytes = localFlags[Long](
    "env.aurora.serverDiskInBytes",
    "Amount of disk in bytes to use for server jobs."
  )
  val jvmCmd = localFlags[String](
    "env.aurora.jvmCmd",
    "Java command used to launch Iago test jar, minus the main class name, e.g. –\n" +
      "java -cp 'dist/iago-core.jar'"
  )

  // These Lists are for customizing subclasses with your local requirements and Aurora configurations.
  // Add or remove flags from auroraModeFlags for them to be active or inactive.

  // These are the Launcher flags to be registered with the App.
  def auroraModeFlags: List[Flag[_]] = localFlags.getAll().toList

  // These are the flags that are required for any command.
  val auroraRequiredFlags: List[Flag[_]] = List(
    cluster,
    role,
    env,
    jobNameF
  )

  // These are the flags that are required for the launch command.
  val auroraRequiredFlagsForLaunch: List[Flag[_]] = auroraRequiredFlags ::: List(
    auroraConfig,
    maxPerHost,
    numInstancesF,
    serverNumCpus,
    serverRamInBytes,
    serverDiskInBytes,
    feederNumInstances,
    feederNumCpus,
    feederRamInBytes,
    feederDiskInBytes,
    jvmCmd
  )

  // These are the flags that are required for the adjust command.
  val auroraRequiredFlagsForAdjust: List[Flag[_]] = auroraRequiredFlags ::: List(
    requestRateF,
    jvmCmd
  )

  // These are the flags passed to the Aurora configuration via --bind.
  val auroraConfigBindingFlags: List[Flag[_]] = List(
    cluster,
    role,
    env,
    jobNameF,
    maxPerHost,
    numInstancesF,
    serverNumCpus,
    serverRamInBytes,
    serverDiskInBytes,
    feederNumInstances,
    feederNumCpus,
    feederRamInBytes,
    feederDiskInBytes
  )

  def auroraConfigFlagMap: Map[String, String] =
    flagsToFlagMap(auroraConfigBindingFlags).map {
      case (name, value) => (name.stripPrefix("env.aurora."), value)
    }

  def feederJobPath = s"${cluster()}/${role()}/${env()}/parrot_feeder_${jobNameF()}"
  def serverJobPath = s"${cluster()}/${role()}/${env()}/parrot_server_${jobNameF()}"

  def auroraKill(feederFlags: Seq[Flag[_]], serverFlags: Seq[Flag[_]]): Unit = {
    def statusCmd(jobPath: String) = List("aurora", "job", "status", s"$jobPath")
    def killCmd(jobPath: String) = List("aurora", "job", "killall", "--no-batching", s"$jobPath")

    def killJobs(jobPath: String, jobShortName: String) = {
      val jobStatusCmd = statusCmd(feederJobPath)
      val rc = runCmd(jobStatusCmd, suppressOutput = true)
      // check if jobs are running before trying to kill them
      rc match {
        case 0 =>
          val jobKillCmd = killCmd(jobPath)
          runCmdWithConfirmationOrAbort(
            jobKillCmd,
            prompt = s"Really kill all jobs in ($jobPath) (y/n)?",
            errorMsg = s"Failed to kill $jobShortName jobs"
          )
        case 6 => log.info(s"No jobs present in $jobPath to kill.")
        case _ => exitOnError(s"Couldn't obtain the status of $jobPath")
      }
    }

    killJobs(feederJobPath, "feeder")
    killJobs(serverJobPath, "server")
  }

  // Override this method with site-specific values.
  def auroraLaunchOverrideFlags(
    feederFlags: Seq[Flag[_]],
    serverFlags: Seq[Flag[_]]
  ): (Map[String, String], Map[String, String]) = {
    val feederFlagMap = flagsToFlagMap(feederFlags)
    val serverFlagMap = flagsToFlagMap(serverFlags)
    (feederFlagMap, serverFlagMap)
  }

  def auroraLaunch(feederFlags: Seq[Flag[_]], serverFlags: Seq[Flag[_]]): Unit = {
    def launchCmd(jobPath: String) = List("aurora", "job", "create", s"$jobPath")

    // pass flags needed by Aurora configuration as Pystachio bindings
    def auroraArgs =
      auroraConfigFlagMap.flatMap { case (name, value) => List("--bind", s"flags.$name=$value") }.toList

    def launchJobs(jobPath: String, jobShortName: String, jobFlagMap: Map[String, String]) = {
      val jobLaunchCmd = launchCmd(jobPath) ::: List(auroraConfig())
      // pass job-specific flags as a single string
      val jobArgs =
        List("--bind", s"flags.${jobShortName}Args=${flagMapToArgs(jobFlagMap).mkString(" ")}")
      val jobLaunchCmdWithArgs = jobLaunchCmd ::: auroraArgs ::: jobArgs
      runCmdWithExitOnError(jobLaunchCmdWithArgs, errorMsg = s"Failed to launch ${jobShortName}[s]")
    }

    missingFlagCheck(auroraRequiredFlagsForLaunch)
    val (feederFlagMap, serverFlagMap) = auroraLaunchOverrideFlags(feederFlags, serverFlags)

    if (!noKillBeforeLaunch()) {
      // kill existing jobs if present
      auroraKill(feederFlags, serverFlags)
    }

    launchJobs(feederJobPath, "feeder", feederFlagMap)
    launchJobs(serverJobPath, "server", serverFlagMap)
  }

  def auroraAdjustLauncherCmd = s"${jvmCmd()} com.twitter.iago.launcher.Main"

  def auroraAdjust(feederFlags: Seq[Flag[_]], serverFlags: Seq[Flag[_]]): Unit = {
    missingFlagCheck(auroraRequiredFlagsForAdjust)

    val launcherArgs = flagMapToArgs(
      flagsToFlagMap(List(requestRateF, servicePortF)) +
        (servicePortF.name -> {
          if (servicePortF.isDefined) servicePortF() else ":{{thermos.ports[thrift]}}"
        })
    )

    // call launcher adjust -env.local on server jobs via aurora task run
    val feederAdjustCmd = List(
      "aurora",
      "task",
      "run",
      "--threads",
      "4",
      s"$serverJobPath",
      s"$auroraAdjustLauncherCmd adjust -env.local ${launcherArgs.mkString(" ")}"
    )
    runCmdWithExitOnError(feederAdjustCmd, errorMsg = "Failed to adjust feeder jobs")
  }

  addMode(Mode(auroraMode, launch = auroraLaunch, kill = auroraKill, adjust = auroraAdjust))

  init {
    // Add our flags to the App's flags. This is the only way to define flags without registering them
    // at the same time. This is so subclasses can remove flags if they aren't necessary.
    auroraModeFlags.filter(_.name != "help").foreach { flagToAdd =>
      flag.add(flagToAdd)
    }
  }

  premain {
    if (getMode(modes).modeFlag == auroraMode) { missingFlagCheck(auroraRequiredFlags) }
  }
}
