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

import com.twitter.app.Flag

case class Mode(
  modeFlag: Flag[Boolean],
  launch: (Seq[Flag[_]], Seq[Flag[_]]) => Unit,
  kill: (Seq[Flag[_]], Seq[Flag[_]]) => Unit,
  adjust: (Seq[Flag[_]], Seq[Flag[_]]) => Unit,
  subCommands: Map[String, SubCommand] = Map()
) {

  def dispatch(cmd: String, serverFlags: Seq[Flag[_]], clientFlags: Seq[Flag[_]]): Unit = {
    subCommands
      .getOrElse(
        cmd,
        throw new RuntimeException(
          s"$cmd is not defined. Available commands:\n${subCommandsToString()}"
        )
      )
      .run(serverFlags, clientFlags)
  }

  def subCommandsToString(): String = {
    subCommands map { case (_, subCmd) => subCmd.toString } mkString "\n"
  }
}
