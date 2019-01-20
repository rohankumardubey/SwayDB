/*
 * Copyright (C) 2018 Simer Plaha (@simerplaha)
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
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with SwayDB. If not, see <https://www.gnu.org/licenses/>.
 */

package swaydb.core.finders

import scala.annotation.tailrec
import scala.util.{Failure, Success, Try}
import swaydb.core.data.{KeyValue, Value}
import swaydb.core.data.KeyValue.ReadOnly
import swaydb.core.function.FunctionStore
import swaydb.core.merge.{FunctionMerger, PendingApplyMerger, RemoveMerger, UpdateMerger}
import swaydb.core.util.TryUtil
import swaydb.data.order.{KeyOrder, TimeOrder}
import swaydb.data.slice.Slice

private[core] object Get {

  def apply(key: Slice[Byte],
            getFromCurrentLevel: Slice[Byte] => Try[Option[KeyValue.ReadOnly.SegmentResponse]],
            getFromNextLevel: Slice[Byte] => Try[Option[KeyValue.ReadOnly.Put]])(implicit keyOrder: KeyOrder[Slice[Byte]],
                                                                                 timeOrder: TimeOrder[Slice[Byte]],
                                                                                 functionStore: FunctionStore): Try[Option[KeyValue.ReadOnly.Put]] = {

    import keyOrder._

    @tailrec
    def returnSegmentResponse(current: KeyValue.ReadOnly.SegmentResponse): Try[Option[ReadOnly.Put]] =
      current match {
        case current: KeyValue.ReadOnly.Remove =>
          if (current.hasTimeLeft())
            getFromNextLevel(key) map {
              nextOption =>
                nextOption flatMap {
                  next =>
                    if (next.hasTimeLeft())
                      RemoveMerger(current, next) match {
                        case put: ReadOnly.Put if put.hasTimeLeft() =>
                          Some(put)

                        case _: ReadOnly.Fixed =>
                          None
                      }
                    else
                      None
                }
            }
          else
            TryUtil.successNone

        case current: KeyValue.ReadOnly.Put =>
          if (current.hasTimeLeft())
            Success(Some(current))
          else
            TryUtil.successNone

        case current: KeyValue.ReadOnly.Update =>
          if (current.hasTimeLeft())
            getFromNextLevel(key) map {
              nextOption =>
                nextOption flatMap {
                  next =>
                    if (next.hasTimeLeft())
                      UpdateMerger(current, next) match {
                        case put: ReadOnly.Put if put.hasTimeLeft() =>
                          Some(put)

                        case _: ReadOnly.Fixed =>
                          None
                      }
                    else
                      None
                }
            }
          else
            TryUtil.successNone

        case current: KeyValue.ReadOnly.Range =>
          (if (current.key equiv key) current.fetchFromOrElseRangeValue else current.fetchRangeValue) match {
            case Success(currentValue) =>
              if (Value.hasTimeLeft(currentValue))
                returnSegmentResponse(currentValue.toMemory(key))
              else
                TryUtil.successNone

            case Failure(exception) =>
              Failure(exception)
          }

        case current: KeyValue.ReadOnly.Function =>
          getFromNextLevel(key) flatMap {
            nextOption =>
              nextOption map {
                next =>
                  if (next.hasTimeLeft())
                    FunctionMerger(current, next) match {
                      case Success(put: ReadOnly.Put) if put.hasTimeLeft() =>
                        Success(Some(put))

                      case Success(_: ReadOnly.Fixed) =>
                        TryUtil.successNone

                      case Failure(exception) =>
                        Failure(exception)
                    }
                  else
                    TryUtil.successNone
              } getOrElse {
                TryUtil.successNone
              }
          }

        case current: KeyValue.ReadOnly.PendingApply =>
          getFromNextLevel(key) flatMap {
            nextOption =>
              nextOption map {
                next =>
                  if (next.hasTimeLeft())
                    PendingApplyMerger(current, next) match {
                      case Success(put: ReadOnly.Put) if put.hasTimeLeft() =>
                        Success(Some(put))

                      case Success(_: ReadOnly.Fixed) =>
                        TryUtil.successNone

                      case Failure(exception) =>
                        Failure(exception)
                    }
                  else
                    TryUtil.successNone
              } getOrElse {
                TryUtil.successNone
              }
          }
      }

    getFromCurrentLevel(key) flatMap {
      case Some(current) =>
        returnSegmentResponse(current)

      case None =>
        getFromNextLevel(key)
    }
  }
}
