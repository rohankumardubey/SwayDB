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
 * If you modify this Program or any covered work, only by linking or
 * combining it with separate works, the licensors of this Program grant
 * you additional permission to convey the resulting work.
 */

package swaydb

/**
 * An Iterator like implementation that can be used to build [[Stream]]s
 * from other streaming libraries.
 *
 * This trait can be used to create async or sync streams.
 *
 * @see [[Stream.streamer]] to create this object from a SwayDB stream
 *      which can then be converted into other stream from other Streaming
 *      libraries.
 */
trait Streamer[A] {
  def nextOrNull[BAG[_]](implicit bag: Bag[BAG]): BAG[A]
  final def nextOption[BAG[_]](implicit bag: Bag[BAG]): BAG[Option[A]] =
    bag.map(nextOrNull)(Option(_))
}