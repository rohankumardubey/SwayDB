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

package swaydb

import scala.collection.compat.IterableOnce
import scala.collection.mutable

/**
 * Scala collections are blocking and requires an IO Map from SwayDB to build a Map.
 */
private[swaydb] object ScalaMap {

  def apply[K, V](db: SetMapT[K, V, Bag.Less]): mutable.Map[K, V] =
    new ScalaMapBase[K, V](db) {

      override def addOne(kv: (K, V)): this.type = {
        db.put(kv._1, kv._2)
        this
      }

      override def subtractOne(key: K): this.type = {
        db.remove(key)
        this
      }

      override def subtractAll(xs: IterableOnce[K]): this.type = {
        db.remove(xs.iterator)
        this
      }

      override def addAll(xs: IterableOnce[(K, V)]): this.type = {
        db.put(xs.iterator)
        this
      }
    }
}
