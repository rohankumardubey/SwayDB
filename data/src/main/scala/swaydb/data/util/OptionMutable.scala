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

package swaydb.data.util

import scala.annotation.unchecked.uncheckedVariance

private[swaydb] sealed trait OptionMutable[+T] extends SomeOrNoneCovariant[OptionMutable[T], OptionMutable.Some[T]] {
  override def noneC: OptionMutable[Nothing] = OptionMutable.Null
  def setValue(value: T@uncheckedVariance): Unit
  def value: T
}

private[swaydb] object OptionMutable {
  final case object Null extends OptionMutable[Nothing] {
    private def throwException = throw new Exception(s"${this.getClass.getSimpleName} is of type Null.")

    override def isNoneC: Boolean = true
    override def getC: Some[Nothing] = throwException
    override def setValue(value: Nothing): Unit = throwException
    override def value: Nothing = throwException
  }

  final class Some[+T](var value: T@uncheckedVariance) extends OptionMutable[T] {

    override def isNoneC: Boolean = false
    override def getC: Some[T] = this

    def setValue(value: T@uncheckedVariance): Unit =
      this.value = value
  }
}
