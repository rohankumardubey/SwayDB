/*
 * Copyright 2018 Simer JS Plaha (simer.j@gmail.com - @simerplaha)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package swaydb.core.segment.data.merge

import org.scalatest.matchers.should.Matchers._
import org.scalatest.wordspec.AnyWordSpec
import swaydb.core.log.timer.TestTimer
import swaydb.core.segment.{CoreFunctionStore, TestCoreFunctionStore}
import swaydb.core.segment.data.KeyValueTestKit._
import swaydb.core.segment.data.Memory
import swaydb.core.segment.data.merge.SegmentMergeTestKit._
import swaydb.serializers._
import swaydb.serializers.Default._
import swaydb.slice.Slice
import swaydb.slice.order.{KeyOrder, TimeOrder}
import swaydb.slice.SliceTestKit._
import swaydb.testkit.RunThis._

class PendingApplyMergerSpec extends AnyWordSpec {

  private implicit val timeOrder: TimeOrder[Slice[Byte]] = TimeOrder.long
  private implicit val keyOrder: KeyOrder[Slice[Byte]] = KeyOrder.default

  private implicit val testFunctionStore: TestCoreFunctionStore = TestCoreFunctionStore()
  private implicit val functionStore: CoreFunctionStore = testFunctionStore.store

  "Merging PendingApply into any other fixed key-value" when {
    "times are in order" in {

      implicit val testTimer: TestTimer.Incremental = TestTimer.Incremental()

      runThis(1000.times) {
        val key = genStringOption()

        val oldKeyValue = randomPendingApplyKeyValue(key = key)

        val newKeyValue = randomFixedKeyValue(key = key)
        val expected = collapseMerge(newKeyValue, oldKeyValue.applies).asInstanceOf[Memory.Fixed]

        //        println(s"oldKeyValue: $oldKeyValue")
        //        println(s"newKeyValue: $newKeyValue")
        //        println

        assertMerge(
          newKeyValue = newKeyValue,
          oldKeyValue = oldKeyValue,
          expected = expected,
          lastLevelExpect = expected.toLastLevelExpected.getOrElse(Memory.Null)
        )
      }
    }
  }

  "Merging PendingApply into any other fixed key-value" when {
    "times are not in order" should {
      "always return old key-value" in {

        implicit val testTimer: TestTimer.Incremental = TestTimer.Incremental()

        runThis(1000.times) {
          val key = genStringOption()

          val newKeyValue = randomFixedKeyValue(key = key)

          val oldKeyValue = randomPendingApplyKeyValue(key = key)

          assertMerge(
            newKeyValue = newKeyValue,
            oldKeyValue = oldKeyValue,
            expected = oldKeyValue,
            lastLevelExpect = oldKeyValue.toLastLevelExpected.getOrElse(Memory.Null)
          )
        }
      }
    }
  }
}
