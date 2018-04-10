/*
Copyright 2014 Twitter, Inc.

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
package com.twitter.iago.util

import com.twitter.conversions.time._
import com.twitter.util.{Duration, Time}
import java.lang.IllegalArgumentException
import java.util.concurrent.TimeUnit
import org.apache.commons.math.distribution.ExponentialDistributionImpl
import scala.util.Random

/** A two staged distribution. Ideal for services that need a warm-up period before
beginning a stepped load test.  Increase the request rate by stepRate every stepDuration
for the length of the test.  Very useful in determining sustained rps thresholds.

For example, the following configuration parameters would cause Iago to send messages starting at
the rate of 0 messages per second and then to gradually increase to 2500 messages per second over 15
minutes. Then, it will increase by 300 requests per second every 5 minutes for the next two hours.

  imports = """
  import com.twitter.conversions.time._
  import com.twitter.iago.util.InfiniteRampPoissonProcess"""

  requestRate = 2500
  createDistribution = "createDistribution = { rate => new InfiniteRampPoissonProcess(rate, 15.minutes, 1, 300, 5.minutes ) }"
  duration = 135
  timeUnit = "MINUTES"
 */
class InfiniteSteppedPoissonProcess(
  finalWarmupRate: Int,
  warmupDuration: Duration,
  initialWarmupRate: Int = 1,
  stepRate: Int,
  stepDuration: Duration = 1.minute
) extends RequestDistribution {
  if (finalWarmupRate < 1) {
    throw new IllegalArgumentException("final warmup rate must be >= 1 per second")
  }

  if (warmupDuration < 1.millisecond) {
    throw new IllegalArgumentException("warmup duration must be >= 1 millisecond")
  }

  if (initialWarmupRate <= 0) {
    throw new IllegalArgumentException("initial warmup rate must be >= 1 per second")
  }

  if (stepRate <= 0) {
    throw new IllegalArgumentException("step rate must be >= 1 per second")
  }

  if (stepDuration < 1.millisecond) {
    throw new IllegalArgumentException("step duration must be >= 1 millisecond")
  }

  private[this] val rand = new Random(Time.now.inMillis)
  private[this] var dist = new ExponentialDistributionImpl(1000000000.0 / initialWarmupRate)
  private[this] val warmupStepPerMilli =
    (finalWarmupRate - initialWarmupRate).toDouble / warmupDuration.inMilliseconds.toDouble
  private[this] var totalArrivals = 0
  private[this] var currentArrivalsPerSecond = initialWarmupRate.toDouble

  private[this] def finishedWarmup: Boolean = currentRate >= finalWarmupRate

  private[this] def stepPerMilli = warmupStepPerMilli

  private[this] def increaseStepPoint: Double =
    (currentArrivalsPerSecond / 1000.0) + (stepPerMilli / 2.0)
  private[this] var nextStepPoint = increaseStepPoint

  def currentRate: Int = currentArrivalsPerSecond.toInt

  // Accumulated duration spent in the current stepDuration
  private[this] var accumulatedDuration: Duration = Duration.Zero

  def timeToNextArrival(): Duration = {
    if (!finishedWarmup) {
      totalArrivals += 1
      if (totalArrivals >= nextStepPoint.toInt) {
        // Compute when to next update rate (numerically integrate number of arrivals over the next ms).
        // Loops handles the case where step points increase more quickly than arrivals.
        while (totalArrivals >= nextStepPoint.toInt) {
          nextStepPoint += increaseStepPoint
          currentArrivalsPerSecond += stepPerMilli
        }

        // clamp rate to final arrival rate and only update distribution if it has changed (by 1+ RPS)
        val mean = 1000000000.0 / currentRate
        if (dist.getMean != mean) {
          dist = new ExponentialDistributionImpl(mean)
        }
      }

      val nanosToNextArrival = dist.inverseCumulativeProbability(rand.nextDouble())
      Duration(nanosToNextArrival.toLong, TimeUnit.NANOSECONDS)
    } else {
      val nanosToNextArrival = 1000000000.0 / currentArrivalsPerSecond
      val nextArrival = Duration(nanosToNextArrival.toLong, TimeUnit.NANOSECONDS)
      if (accumulatedDuration < stepDuration) {
        accumulatedDuration += nextArrival
      } else {
        // Time to step up the rate again
        accumulatedDuration = Duration.Zero
        currentArrivalsPerSecond += stepRate
      }
      return nextArrival
    }
  }

}
