/*
 * Copyright (c) 2020 Simer JS Plaha (simer.j@gmail.com - @simerplaha)
 *
 * This file is a part of SwayDB.
 *
 * SwayDB is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * SwayDB is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with SwayDB. If not, see <https://www.gnu.org/licenses/>.
 *
 * Additional permission under the GNU Affero GPL version 3 section 7:
 * If you modify this Program or any covered work, only by linking or combining
 * it with separate works, the licensors of this Program grant you additional
 * permission to convey the resulting work.
 */

package swaydb.data

sealed trait OptimiseWrites {
  def atomic: Boolean
}

case object OptimiseWrites {

  def randomOrder(atomic: Boolean): OptimiseWrites.RandomOrder =
    RandomOrder(atomic = atomic)

  def sequentialOrder(atomic: Boolean,
                      initialSkipListLength: Int): OptimiseWrites.SequentialOrder =
    SequentialOrder(
      atomic = atomic,
      initialSkipListLength = initialSkipListLength
    )

  /**
   * Always use this setting if writes are in random order or when unsure.
   *
   * @param atomic If true ensures that all range operations and [[swaydb.Prepare]] transactions
   *               are available for reads atomically. For eg: if you submit a [[swaydb.Prepare]]
   *               that updates 10 key-values, those 10 key-values will be visible to reads only after
   *               each update is applied.
   */
  case class RandomOrder(atomic: Boolean) extends OptimiseWrites

  /**
   * Optimises writes for sequential order. Eg: if you inserts are simple
   * sequential put eg - 1, 2, 3 ... N with [[swaydb.data.order.KeyOrder.integer]].
   * Then this setting would increase write throughput.
   *
   * @param initialSkipListLength set the initial length of SkipList's Array.
   *                              The Array is extended if the size is too small.
   * @param atomic                see [[RandomOrder.atomic]]
   */
  case class SequentialOrder(atomic: Boolean,
                             initialSkipListLength: Int) extends OptimiseWrites

}
