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

import com.twitter.util.Duration

object PiecewiseDistribution {
  case class Segment(dist: RequestDistribution, duration: Duration = Duration.Top)

  case object NoSegmentsError extends RuntimeException("No segments supplied")
}

/**
 * PiecewiseDistribution implements sequential execution of given request distributions.
 * @param segments a sequence consisting of the duration of that a given distribution
 *                 should be used and the given distribution. Can not be empty.
 *                 The sequence will be repeated on exhaustion.
 * @see RequestDistribution
 * @see PiecewiseDistribution.Segment
 */
class PiecewiseDistribution(segments: Seq[PiecewiseDistribution.Segment])
    extends RequestDistribution {

  if (segments.size == 0) throw PiecewiseDistribution.NoSegmentsError

  // Accumulated duration spent in the current used distribution.
  private[this] var accumulatedDuration: Duration = Duration.Zero

  // Current distribution index used in dists.
  private[this] var currentSegmentIndex: Int = 0

  // Get the next distribution to use.
  private[this] def nextDist(): RequestDistribution = {
    val segment = segments(currentSegmentIndex)

    if (accumulatedDuration < segment.duration) {
      // Still enough time for current distribution in use.
      segment.dist
    } else {
      // Time for current distribution exhausted. Move on to the next distribution.
      accumulatedDuration = accumulatedDuration - segment.duration
      currentSegmentIndex = (currentSegmentIndex + 1) % segments.size
      nextDist()
    }
  }

  override def timeToNextArrival(): Duration = {
    val dist: RequestDistribution = nextDist()
    val delta: Duration = dist.timeToNextArrival()
    accumulatedDuration += delta
    return delta
  }
}
