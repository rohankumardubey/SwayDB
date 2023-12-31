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

package swaydb.core.series.map

import swaydb.core.series._

import java.util.concurrent.atomic.AtomicInteger
import scala.annotation.tailrec
import scala.util.Random

/**
 * A fixed size HashMap that inserts newer key-values to empty spaces or
 * overwrites older key-values if the space is occupied by an older
 * key-value.
 */
private[swaydb] sealed trait LimitHashMap[K, V >: Null] extends Iterable[(K, V)] {
  def limit: Int
  def put(key: K, value: V): Unit
  def getOrNull(key: K): V
  def getOption(key: K): Option[V] =
    Option(getOrNull(key))
}

private[swaydb] object LimitHashMap {

  /**
   * A Limit HashMap that tries to insert newer key-values to empty slots
   * or else overwrites older key-values if no slots are free.
   *
   * @param limit    Max number of key-values
   * @param maxProbe Number of re-tries on hash collision.
   */
  def apply[K, V >: Null](limit: Int,
                          maxProbe: Int): LimitHashMap[K, V] =
    if (limit <= 0)
      new Empty[K, V]
    else if (maxProbe <= 0)
      new NoProbe[K, V](
        series = SeriesBasic[(K, V)](limit)
      )
    else
      new Probed[K, V](
        series = SeriesBasic(limit),
        maxProbe = maxProbe,
        overwriteOldest = true,
        overwriteRandom = false
      )

  def volatile[K, V >: Null](limit: Int,
                             maxProbe: Int): LimitHashMap[K, V] =
    if (limit <= 0)
      new Empty[K, V]
    else if (maxProbe <= 0)
      new NoProbe[K, V](
        series = SeriesVolatile[(K, V)](limit)
      )
    else
      new Probed[K, V](
        series = SeriesVolatile[(K, V, Int)](limit),
        maxProbe = maxProbe,
        overwriteOldest = true,
        overwriteRandom = false
      )

  def volatileBucket[K, V >: Null](limit: Int,
                                   maxProbe: Int): LimitHashMap[K, V] =
    if (limit <= 0)
      new Empty[K, V]
    else if (maxProbe <= 0)
      new NoProbe[K, V](
        series = SeriesVolatile[(K, V)](limit)
      )
    else
      new Probed[K, V](
        series = SeriesVolatile[(K, V, Int)](limit),
        maxProbe = maxProbe,
        overwriteOldest = true,
        overwriteRandom = false
      )

  /**
   * @param limit Max number of key-values
   */
  def apply[K, V >: Null](limit: Int): LimitHashMap[K, V] =
    if (limit <= 0)
      new Empty[K, V]
    else
      new NoProbe[K, V](
        series = SeriesBasic[(K, V)](limit)
      )

  def volatile[K, V >: Null](limit: Int): LimitHashMap[K, V] =
    if (limit <= 0)
      new Empty[K, V]
    else
      new NoProbe[K, V](
        series = SeriesVolatile[(K, V)](limit)
      )

  private class Probed[K, V >: Null](series: Series[(K, V, Int)],
                                     maxProbe: Int,
                                     overwriteOldest: Boolean,
                                     overwriteRandom: Boolean) extends LimitHashMap[K, V] {

    val limit = series.length
    val limitMinusOne = limit - 1
    val time: AtomicInteger =
      if (overwriteOldest)
        new AtomicInteger(0)
      else
        null

    def put(key: K, value: V): Unit = {
      val index = Math.abs(key.##) % limit
      if (overwriteOldest)
        putOverwriteOldest(
          key = key,
          value = value,
          hashIndex = index,
          nextHashIndex = index,
          oldestIndex = index,
          oldestIndexTime = Int.MaxValue,
          probe = 0
        )
      else if (overwriteRandom)
        putOverwriteRandom(
          key = key,
          value = value,
          hashIndex = index,
          targetIndex = index,
          probe = 0
        )
      else
        putOverwriteHead(
          key = key,
          value = value,
          hashIndex = index,
          targetIndex = index,
          probe = 0
        )
    }

    /**
     * Overwrites the oldest on conflict.
     */
    @tailrec
    private def putOverwriteOldest(key: K, value: V, hashIndex: Int, nextHashIndex: Int, oldestIndex: Int, oldestIndexTime: Int, probe: Int): Unit =
      if (probe == maxProbe) {
        //overwrite the oldest
        series.set(oldestIndex, (key, value, time.incrementAndGet()))
      } else {
        val existing = series.getOrNull(nextHashIndex)
        if (existing == null || existing._1 == key) {
          series.set(nextHashIndex, (key, value, time.incrementAndGet()))
        } else {
          val (nextOldestIndex, nextOldestTime) =
            if (oldestIndexTime > existing._3)
              (nextHashIndex, existing._3)
            else
              (oldestIndex, oldestIndexTime)

          val nextTargetIndex = if (nextHashIndex + 1 >= limit) 0 else nextHashIndex + 1

          putOverwriteOldest(
            key = key,
            value = value,
            hashIndex = hashIndex,
            nextHashIndex = nextTargetIndex,
            oldestIndex = nextOldestIndex,
            oldestIndexTime = nextOldestTime,
            probe = probe + 1
          )
        }
      }

    @tailrec
    private def putOverwriteRandom(key: K, value: V, hashIndex: Int, targetIndex: Int, probe: Int): Unit =
      if (probe == maxProbe) {
        //random select and index to insert input.
        val index = (hashIndex + Random.nextInt(probe)) min limitMinusOne
        series.set(index, (key, value, 0))
      } else {
        val existing = series.getOrNull(targetIndex)
        if (existing == null || existing._1 == key)
          series.set(targetIndex, (key, value, 0))
        else
          putOverwriteRandom(key, value, hashIndex, if (targetIndex + 1 >= limit) 0 else targetIndex + 1, probe + 1)
      }

    @tailrec
    private def putOverwriteHead(key: K, value: V, hashIndex: Int, targetIndex: Int, probe: Int): Unit =
      if (probe == maxProbe) {
        series.set(hashIndex, (key, value, 0))
      } else {
        val existing = series.getOrNull(targetIndex)
        if (existing == null || existing._1 == key)
          series.set(targetIndex, (key, value, 0))
        else
          putOverwriteHead(key, value, hashIndex, if (targetIndex + 1 >= limit) 0 else targetIndex + 1, probe + 1)
      }

    def getOrNull(key: K): V = {
      val index = Math.abs(key.##) % limit
      get(key, index, 0)
    }

    @tailrec
    private def get(key: K, index: Int, probe: Int): V =
      if (probe == maxProbe) {
        null
      } else {
        val keyValue = series.getOrNull(index)
        if (keyValue != null && keyValue._1 == key)
          keyValue._2
        else
          get(key, if (index + 1 >= limit) 0 else index + 1, probe + 1)
      }

    override def iterator: Iterator[(K, V)] =
      new Iterator[(K, V)] {
        val innerIterator = series.iterator

        override def hasNext: Boolean =
          innerIterator.hasNext

        override def next(): (K, V) = {
          val nextItem = innerIterator.next()
          if (nextItem == null)
            null
          else
            (nextItem._1, nextItem._2)
        }
      }
  }

  private class NoProbe[K, V >: Null](series: Series[(K, V)]) extends LimitHashMap[K, V] {

    val limit = series.length

    def put(key: K, value: V) =
      series.set(Math.abs(key.##) % limit, (key, value))

    def getOrNull(key: K): V = {
      val value = series.getOrNull(Math.abs(key.##) % limit)
      if (value != null && value._1 == key)
        value._2
      else
        null
    }

    override def iterator: Iterator[(K, V)] =
      series.iterator
  }

  private class Empty[K, V >: Null] extends LimitHashMap[K, V] {
    override def limit: Int = 0
    override def put(key: K, value: V): Unit = ()
    override def getOrNull(key: K): V = null
    override def iterator: Iterator[(K, V)] = Iterator.empty
  }
}

