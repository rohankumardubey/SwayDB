/*
 * Copyright (c) 2019 Simer Plaha (@simerplaha)
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
 */

package swaydb.data.order

import swaydb.data.slice.Slice

object KeyOrder {

  /**
    * Default ordering.
    *
    * Custom key-ordering can be implemented.
    * Documentation: http://www.swaydb.io/custom-key-ordering
    *
    */
  val default, lexicographic: KeyOrder[Slice[Byte]] = new KeyOrder[Slice[Byte]] {
    def compare(a: Slice[Byte], b: Slice[Byte]): Int = {
      val minimum = java.lang.Math.min(a.size, b.size)
      var i = 0
      while (i < minimum) {
        val aB = a(i) & 0xFF
        val bB = b(i) & 0xFF
        if (aB != bB) return aB - bB
        i += 1
      }
      a.size - b.size
    }
  }

  /**
    * Provides the default reverse ordering.
    */
  val reverse: KeyOrder[Slice[Byte]] =
    new KeyOrder[Slice[Byte]] {
      def compare(a: Slice[Byte], b: Slice[Byte]): Int =
        default.compare(a, b) * -1
    }

  def apply[K](ordering: Ordering[K]): KeyOrder[K] =
    new KeyOrder[K]() {
      override def compare(x: K, y: K): Int =
        ordering.compare(x, y)
    }
}

trait KeyOrder[K] extends Ordering[K]
