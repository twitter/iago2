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

import com.twitter.app.{App, Flag, GlobalFlag}
import com.twitter.iago.{ParrotFeederFlags, ParrotServerFlags}
import com.twitter.server.logging.{Logging => JDK14Logging}
import com.twitter.server.Hooks
import scala.collection.mutable
import scala.sys.SystemProperties
import sys.process._


class DummyParrotFeeder extends App with ParrotFeederFlags with Hooks
class DummyParrotServer extends App with ParrotServerFlags with Hooks

object Main extends LauncherMain with LocalMode with AuroraMode

class LauncherMain extends App
  with ParrotServerFlags
  with ParrotFeederFlags
  with JDK14Logging
  with Hooks
{
  val systemProperties = new SystemProperties()
  val classPath: Flag[String] = flag(
    name = "env.local.classPath",
    default = currentLauncherClassPath,
    help =
      "Path expression for your Iago classes (default: finds the launcher class path using reflection)."
  )
  val yes: Flag[Boolean] = flag("yes", "All user input defaults to yes. Use with caution.")
  val modes: mutable.Buffer[Mode] = mutable.Buffer.empty

  val dummyParrotFeeder = new DummyParrotFeeder
  val dummyParrotServer = new DummyParrotServer

  def currentLauncherClassPath =
    getClass.getProtectionDomain.getCodeSource.getLocation.toString

  def definedFeederFlags = dummyParrotFeeder.flag.getAll().map { _.name }.toSet
  def definedServerFlags = dummyParrotServer.flag.getAll().map { _.name }.toSet

  def feederFlagsToSend =
    flag
      .getAll()
      .filter(_.isDefined)
      .filter(launcherFlag => definedFeederFlags.contains(launcherFlag.name))
      .toList
  def serverFlagsToSend =
    flag
      .getAll()
      .filter(_.isDefined)
      .filter(launcherFlag => definedServerFlags.contains(launcherFlag.name))
      .toList

  def flagsToFlagMap(flags: Seq[Flag[_]]): Map[String, String] = {
    flags.filter(_.getWithDefault.isDefined).map { flag =>
      val name = if (flag.isInstanceOf[GlobalFlag[_]]) {
        // In the case of GlobalFlag,
        // make sure that we pass the fully-qualified flag name to Server/Feeder
        flag.getClass.getName.stripSuffix("$")
      } else {
        flag.name
      }
      name -> flag().toString
    }.toMap
  }

  def flagMapToArgs(flagMap: Map[String, String]): List[String] = {
    flagMap.map { case (name, value) => s"-${name}=${value}" }.toList
  }

  def flagsToArgs(flags: Seq[Flag[_]]): List[String] = {
    flagMapToArgs(flagsToFlagMap(flags))
  }

  def missingFlagCheck(flagsToCheck: List[Flag[_]]): Unit = {
    val missingFlags = flagsToCheck.filter(_.getWithDefault.isEmpty)
    if (missingFlags.nonEmpty) {
      val missingFlagUsage = missingFlags.sortBy(_.name).map(_.usageString).mkString("\n")
      exitOnError(s"\nThe following flags are required, but were not specified:\n$missingFlagUsage")
    }
  }

  def runCmd(cmd: Seq[String], suppressOutput: Boolean = false): Int = {
    log.info(s"Running command: ${cmd.mkString(" ")}")
    if (suppressOutput) Process(cmd).!(ProcessLogger(out => ())) else Process(cmd).!
  }

  def runBackgroundCmd(cmd: Seq[String]): Process = {
    log.info(s"Running command: ${cmd.mkString(" ")}")
    Process(cmd).run
  }

  def runCmdWithExitOnError(cmd: Seq[String], errorMsg: String): Unit = {
    val rc = runCmd(cmd)
    if (rc != 0) { exitOnError(s"$errorMsg: non-zero exit code ($rc).") }
  }

  def runCmdWithConfirmationOrAbort(cmd: Seq[String], prompt: String, errorMsg: String): Unit = {
    if (yes.isDefined || {
        print(s"$prompt ")
        scala.io.StdIn.readBoolean
      }) {
      runCmdWithExitOnError(cmd, errorMsg)
    } else {
      exitOnError("Aborting...")
    }
  }

  def addMode(mode: Mode): Unit = {
    modes += mode
  }

  def getSpecifiedModes(modes: Seq[Mode]) = modes.filter(_.modeFlag.isDefined)

  def getMode(modes: Seq[Mode]): Mode = getSpecifiedModes(modes).head

  def checkModeFlags(modes: Seq[Mode]): Unit = {
    val availableModeFlags = modes.map(mode => s"-${mode.modeFlag.name}").mkString("\n")
    val specifiedModes = getSpecifiedModes(modes)
    if (specifiedModes.isEmpty) {
      exitOnError(
        s"\nError: exactly one of the following modes must be specified.\n$availableModeFlags"
      )
    } else if (specifiedModes.length > 1) {
      exitOnError(
        s"\nError: only one of the following modes may be specified.\n$availableModeFlags"
      )
    }
  }

  def launch(mode: Mode, feederFlags: Seq[Flag[_]], serverFlags: Seq[Flag[_]]): Unit = {
    mode.launch(feederFlags, serverFlags)
  }

  def kill(mode: Mode, feederFlags: Seq[Flag[_]], serverFlags: Seq[Flag[_]]): Unit = {
    mode.kill(feederFlags, serverFlags)
  }

  def adjust(mode: Mode, feederFlags: Seq[Flag[_]], serverFlags: Seq[Flag[_]]): Unit = {
    mode.adjust(feederFlags, serverFlags)
  }

  def main() {
    val mode = getMode(modes)

    if (args.length > 1) {
      exitOnError(s"\nError: extraneous arguments: ${args.tail.mkString(", ")}\n\n${flag.usage}")
    }

    args.headOption match {
      case None => exitOnError(s"\nError: no command provided.\n\n${flag.usage}")
      case Some(cmd: String) =>
        cmd match {
          case "launch" =>
            launch(mode, feederFlags = feederFlagsToSend, serverFlags = serverFlagsToSend)
          case "kill" =>
            kill(mode, feederFlags = feederFlagsToSend, serverFlags = serverFlagsToSend)
          case "adjust" =>
            adjust(mode, feederFlags = feederFlagsToSend, serverFlags = serverFlagsToSend)
          case _ =>
            mode.dispatch(cmd, feederFlagsToSend, serverFlagsToSend)
        }
    }
  }

  init {
    val subcommands = modes collect {
      case mode if mode.subCommandsToString() != "" =>
        s"${mode.modeFlag.name} sub commands: ${mode.subCommandsToString()}"
    } mkString "\n"

    flag.setCmdUsage(s"""|Iago Launcher
          |
          |The following commands are supported:
          |
          |launch - Launch the loadtest specified by the selected Iago configuration.
          |         All available flags are supported.
          |kill   - Kill the a running loadtest. Only flags starting with 'env' are necessary.
          |         These vary depending on the environment.
          |adjust - Adjust the request rate of a running loadtest.
          |         Only 'env' flags and 'requestRate' are necessary.
          |$subcommands
          |
          |Exactly one environment must be specified. Commands apply to the specified environment.
          |Environments are specified by flags that begin with env, e.g. -env.local
          |""".stripMargin)

  }

  premain {
    checkModeFlags(modes)
  }
}
