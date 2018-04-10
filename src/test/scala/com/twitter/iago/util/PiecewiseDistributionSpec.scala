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

import org.junit.runner.RunWith
import org.mockito.Mockito._
import org.scalatest.junit.JUnitRunner
import org.scalatest.mock.MockitoSugar
import org.scalatest.{MustMatchers, WordSpec}

import com.twitter.conversions.time._
import com.twitter.util.Duration

@RunWith(classOf[JUnitRunner])
class PiecewiseDistributionSpec extends WordSpec with MustMatchers with MockitoSugar {

  "PieceWiseDistribution" should {

    "fail on no segments" in {
      try {
        new PiecewiseDistribution(Seq.empty)
        fail
      } catch {
        case PiecewiseDistribution.NoSegmentsError => // nop
      }
    }

    "use specified distributions" in {
      val dist1 = mock[RequestDistribution]
      val dist2 = mock[RequestDistribution]
      val dist3 = mock[RequestDistribution]

      val piecewise: RequestDistribution =
        new PiecewiseDistribution(
          Seq(
            PiecewiseDistribution.Segment(dist1, 2.seconds),
            PiecewiseDistribution.Segment(dist2, 5.seconds),
            PiecewiseDistribution.Segment(dist3)
          )
        )

      when(dist1.timeToNextArrival()).thenReturn(1.second, 1.second)
      piecewise.timeToNextArrival() must equal(1.second)
      piecewise.timeToNextArrival() must equal(1.second)

      when(dist2.timeToNextArrival()).thenReturn(2.seconds, 3.seconds)
      piecewise.timeToNextArrival() must equal(2.seconds)
      piecewise.timeToNextArrival() must equal(3.seconds)

      when(dist3.timeToNextArrival()).thenReturn(3.seconds, 4.seconds, 5.seconds)
      piecewise.timeToNextArrival() must equal(3.seconds)
      piecewise.timeToNextArrival() must equal(4.seconds)
      piecewise.timeToNextArrival() must equal(5.seconds)
    }

    "loop" in {
      val dist1 = mock[RequestDistribution]
      val dist2 = mock[RequestDistribution]

      val piecewise: RequestDistribution =
        new PiecewiseDistribution(
          Seq(
            PiecewiseDistribution.Segment(dist1, 10.seconds),
            PiecewiseDistribution.Segment(dist2, 15.seconds)
          )
        )

      when(dist1.timeToNextArrival()).thenReturn(10.seconds)
      piecewise.timeToNextArrival() must equal(10.second)

      when(dist2.timeToNextArrival()).thenReturn(15.second)
      piecewise.timeToNextArrival() must equal(15.second)

      when(dist1.timeToNextArrival()).thenReturn(5.seconds)
      piecewise.timeToNextArrival() must equal(5.second)

      when(dist1.timeToNextArrival()).thenReturn(5.seconds)
      piecewise.timeToNextArrival() must equal(5.second)

      when(dist2.timeToNextArrival()).thenReturn(15.second)
      piecewise.timeToNextArrival() must equal(15.second)
    }

    "take duration spillover into account" in {
      val dist1 = mock[RequestDistribution]
      val dist2 = mock[RequestDistribution]
      val dist3 = mock[RequestDistribution]

      val piecewise: RequestDistribution =
        new PiecewiseDistribution(
          Seq(
            PiecewiseDistribution.Segment(dist1, 1.seconds),
            PiecewiseDistribution.Segment(dist2, 2.seconds),
            PiecewiseDistribution.Segment(dist3)
          )
        )

      when(dist1.timeToNextArrival()).thenReturn(2.seconds)
      piecewise.timeToNextArrival() must equal(2.second)

      when(dist2.timeToNextArrival()).thenReturn(1.second)
      // Happens only once as dist1 stole its time.
      piecewise.timeToNextArrival() must equal(1.second)

      when(dist3.timeToNextArrival()).thenReturn(9.seconds)
      piecewise.timeToNextArrival() must equal(9.second)
    }
  }
}
