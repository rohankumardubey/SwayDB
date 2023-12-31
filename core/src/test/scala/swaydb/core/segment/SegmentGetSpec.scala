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

package swaydb.core.segment

import org.scalatest.OptionValues._
import org.scalatest.PrivateMethodTester
import org.scalatest.concurrent.ScalaFutures
import swaydb.config.MMAP
import swaydb.core.CommonAssertions._
import swaydb.core.TestCaseSweeper._
import swaydb.core.TestData._
import swaydb.core.segment.data._
import swaydb.core.segment.ref.search.ThreadReadState
import swaydb.core.{TestBase, TestCaseSweeper, TestForceSave, TestSweeper}
import swaydb.serializers.Default._
import swaydb.serializers._
import swaydb.slice.Slice
import swaydb.slice.order.KeyOrder
import swaydb.testkit.RunThis._
import swaydb.utils.OperatingSystem

import scala.util.Random

class SegmentGetSpec0 extends SegmentGetSpec {
  val keyValuesCount = 1000
}

class SegmentGetSpec1 extends SegmentGetSpec {
  val keyValuesCount = 1000
  override def levelFoldersCount = 10
  override def mmapSegments = MMAP.On(OperatingSystem.isWindows, forceSave = TestForceSave.mmap())
  override def level0MMAP = MMAP.On(OperatingSystem.isWindows, forceSave = TestForceSave.mmap())
  override def appendixStorageMMAP = MMAP.On(OperatingSystem.isWindows, forceSave = TestForceSave.mmap())
}

class SegmentGetSpec2 extends SegmentGetSpec {
  val keyValuesCount = 1000
  override def levelFoldersCount = 10
  override def mmapSegments = MMAP.Off(forceSave = TestForceSave.channel())
  override def level0MMAP = MMAP.Off(forceSave = TestForceSave.channel())
  override def appendixStorageMMAP = MMAP.Off(forceSave = TestForceSave.channel())
}

class SegmentGetSpec3 extends SegmentGetSpec {
  val keyValuesCount = 1000
  override def inMemoryStorage = true
}

sealed trait SegmentGetSpec extends TestBase with ScalaFutures with PrivateMethodTester {

  implicit val keyOrder = KeyOrder.default

  def keyValuesCount: Int

  "Segment.get" should {

    "fixed key-value" in {
      runThis(100.times, log = true) {
        TestCaseSweeper {
          implicit sweeper =>
            assertSegment(
              keyValues = Slice(randomFixedKeyValue(1)),

              assert =
                (keyValues, segment) =>
                  Random.shuffle(
                    Seq(
                      () => segment.get(0, ThreadReadState.random).toOptional shouldBe empty,
                      () => segment.get(2, ThreadReadState.random).toOptional shouldBe empty,
                      () => segment.get(keyValues.head.key, ThreadReadState.random).getUnsafe shouldBe keyValues.head
                    )
                  ).foreach(_ ())
            )

            assertSegment(
              keyValues = Slice(randomFixedKeyValue(1), randomFixedKeyValue(2)),

              assert =
                (keyValues, segment) =>
                  Random.shuffle(
                    Seq(
                      () => segment.get(0, ThreadReadState.random).toOptional shouldBe empty,
                      () => segment.get(3, ThreadReadState.random).toOptional shouldBe empty,
                      () => segment.get(keyValues.head.key, ThreadReadState.random).getUnsafe shouldBe keyValues.head
                    )
                  ).foreach(_ ())
            )
        }
      }
    }

    "range-value" in {
      runThis(100.times, log = true) {
        TestCaseSweeper {
          implicit sweeper =>
            assertSegment(
              keyValues = Slice(randomRangeKeyValue(1, 10)),

              assert =
                (keyValues, segment) =>
                  Random.shuffle(
                    Seq(
                      () => segment.get(0, ThreadReadState.random).toOptional shouldBe empty,
                      () => segment.get(10, ThreadReadState.random).toOptional shouldBe empty,
                      () => segment.get(11, ThreadReadState.random).toOptional shouldBe empty,
                      () =>
                        (1 to 9) foreach {
                          i =>
                            segment.get(i, ThreadReadState.random).getUnsafe shouldBe keyValues.head
                        }
                    )
                  ).foreach(_ ())
            )

            assertSegment(
              keyValues =
                Slice(randomRangeKeyValue(1, 10), randomRangeKeyValue(10, 20)),

              assert =
                (keyValues, segment) =>
                  Random.shuffle(
                    Seq(
                      () => segment.get(0, ThreadReadState.random).toOptional shouldBe empty,
                      () => segment.get(20, ThreadReadState.random).toOptional shouldBe empty,
                      () => segment.get(21, ThreadReadState.random).toOptional shouldBe empty,
                      () =>
                        (1 to 9) foreach {
                          i =>
                            segment.get(i, ThreadReadState.random).getUnsafe shouldBe keyValues.head
                        },
                      () => {
                        val readState = ThreadReadState.random
                        (10 to 19) foreach {
                          i =>
                            segment.get(i, readState).getUnsafe shouldBe keyValues.last
                        }
                      }
                    )
                  ).foreach(_ ())
            )
        }
      }
    }

    "value random key-values" in {
      TestCaseSweeper {
        implicit sweeper =>
          val keyValues = randomizedKeyValues(keyValuesCount)
          val segment = TestSegment(keyValues)
          assertGet(keyValues, segment)
      }
    }

    "add cutd key-values to Segment's caches" in {
      TestCaseSweeper {
        implicit sweeper =>
          TestSweeper.createMemorySweeperMax().value.sweep()

          assertSegment(
            keyValues = randomizedKeyValues(keyValuesCount),

            testAgainAfterAssert = false,

            assert =
              (keyValues, segment) =>
                (0 until keyValues.size) foreach {
                  index =>
                    val keyValue = keyValues(index)
                    if (persistent) segment.getFromCache(keyValue.key).toOptional shouldBe empty
                    segment.get(keyValue.key, ThreadReadState.random).getUnsafe shouldBe keyValue

                    val gotFromCache = eventually(segment.getFromCache(keyValue.key).getUnsafe)
                    //underlying array sizes should not be slices but copies of arrays.
                    gotFromCache.key.underlyingArraySize shouldBe keyValue.key.toArray.length

                    gotFromCache match {
                      case range: KeyValue.Range =>
                        //if it's a range, toKey should also be cutd.
                        range.toKey.underlyingArraySize shouldBe keyValues.find(_.key == range.fromKey).value.key.toArray.length
                      case _ =>
                        gotFromCache.getOrFetchValue.map(_.underlyingArraySize) shouldBe keyValue.getOrFetchValue.map(_.toArray.length)
                    }
                }
          )
      }
    }

    "add read key values to cache" in {
      TestCaseSweeper {
        implicit sweeper =>

          runThis(20.times, log = true) {
            assertSegment(
              keyValues =
                randomizedKeyValues(keyValuesCount),

              testAgainAfterAssert =
                false,

              assert =
                (keyValues, segment) => {
                  val readState = ThreadReadState.random

                  keyValues foreach {
                    keyValue =>
                      if (persistent) segment isInKeyValueCache keyValue.key shouldBe false
                      segment.get(keyValue.key, readState).getUnsafe shouldBe keyValue
                      segment isInKeyValueCache keyValue.key shouldBe true
                  }
                }
            )
          }
      }
    }

    //    "read value from a closed ValueReader" in {
    //      runThis(100.times) {
    //        assertSegment(
    //          keyValues = Slice(randomFixedKeyValue(1), randomFixedKeyValue(2)),
    //          assert =
    //            (keyValues, segment) =>
    //              keyValues foreach {
    //                keyValue =>
    //                  val readKeyValue = segment.get(keyValue.key).runIO
    //                  segment.close.runIO
    //                  readKeyValue.getOrFetchValue shouldBe keyValue.getOrFetchValue
    //              }
    //        )
    //      }
    //    }
    //
    //    "lazily load values" in {
    //      runThis(100.times) {
    //        assertSegment(
    //          keyValues = randomizedKeyValues(keyValuesCount),
    //          testAgainAfterAssert = false,
    //          assert =
    //            (keyValues, segment) =>
    //              keyValues foreach {
    //                keyValue =>
    //                  val readKeyValue = segment.get(keyValue.key).runIO
    //
    //                  readKeyValue match {
    //                    case persistent: Persistent.Remove =>
    //                      //remove has no value so isValueDefined will always return true
    //                      persistent.isValueDefined shouldBe true
    //
    //                    case persistent: Persistent =>
    //                      persistent.isValueDefined shouldBe false
    //
    //                    case _: Memory =>
    //                    //memory key-values always have values defined
    //                  }
    //                  //read the value
    //                  readKeyValue match {
    //                    case range: KeyValue.Range =>
    //                      range.fetchFromAndRangeValue.runIO
    //                    case _ =>
    //                      readKeyValue.getOrFetchValue shouldBe keyValue.getOrFetchValue
    //                  }
    //
    //                  //value is now set
    //                  readKeyValue match {
    //                    case persistent: Persistent =>
    //                      persistent.isValueDefined shouldBe true
    //
    //                    case _: Memory =>
    //                    //memory key-values always have values defined
    //                  }
    //              }
    //        )
    //      }
    //    }
  }
}
