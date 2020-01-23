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
 */

package swaydb.data.stream.step

import swaydb.{Bag, Stream}

private[swaydb] class FlatMap[A, B](previousStream: Stream[A],
                                    f: A => Stream[B]) extends Stream[B] {

  //cache stream and emits it's items.
  //next Stream is read only if the current cached stream is emitted.
  var innerStream: Stream[B] = _
  var previousA: A = _

  def streamNext[T[_]](nextA: A)(implicit bag: Bag[T]): T[B] = {
    innerStream = f(nextA)
    previousA = nextA
    innerStream.headOrNull
  }


  override private[swaydb] def headOrNull[BAG[_]](implicit bag: Bag[BAG]): BAG[B] =
    bag.flatMap(previousStream.headOrNull) {
      case null =>
        bag.success(null.asInstanceOf[B])

      case nextA =>
        streamNext(nextA)
    }

  override private[swaydb] def nextOrNull[BAG[_]](previous: B)(implicit bag: Bag[BAG]) =
    bag.flatMap(innerStream.nextOrNull(previous)) {
      case null =>
        bag.flatMap(previousStream.nextOrNull(previousA)) {
          case null =>
            bag.success(null.asInstanceOf[B])

          case nextA =>
            streamNext(nextA)
        }

      case some =>
        bag.success(some)
    }
}
