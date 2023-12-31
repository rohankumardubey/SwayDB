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

import swaydb.OK
import swaydb.core.segment.data.{SwayFunction, Value}
import swaydb.slice.Slice
import swaydb.slice.order.KeyOrder

import java.util.concurrent.ConcurrentHashMap
import scala.annotation.tailrec
import scala.collection.mutable
import scala.jdk.CollectionConverters._

/**
 * Stores all functions currently registered. This should always contain
 * all functions currently applied or being applied during compaction.
 *
 * Missing functions will be reported with their functionId.
 */
private[swaydb] abstract class FunctionStore {
  def get(functionId: Slice[Byte]): Option[SwayFunction]
  def get(functionId: String): Option[SwayFunction]

  def put(functionId: Slice[Byte], function: SwayFunction): OK
  def put(functionId: String, function: SwayFunction): OK

  def remove(functionId: Slice[Byte]): SwayFunction

  def contains(functionId: Slice[Byte]): Boolean
  def notContains(functionId: Slice[Byte]): Boolean =
    !contains(functionId)

  def asScala: mutable.Map[Slice[Byte], SwayFunction]

  def size: Int
}

private[swaydb] object FunctionStore {

  def memory(): FunctionStore.Memory =
    new Memory()

  val order: FunctionIdOrder =
    new FunctionIdOrder {
      override def compare(x: Slice[Byte], y: Slice[Byte]): Int =
        KeyOrder.lexicographic.compare(x, y)
    }

  def containsFunction(functionId: Slice[Byte], values: Slice[Value]) = {

    @tailrec
    def checkContains(values: Slice[Value]): Boolean =
      values.headOption match {
        case Some(value) =>
          value match {
            case _: Value.Remove | _: Value.Update | _: Value.Put =>
              false

            case Value.Function(function, _) =>
              order.equiv(function, functionId) || checkContains(values.dropHead())

            case Value.PendingApply(applies) =>
              checkContains(applies)
          }

        case None =>
          false
      }

    checkContains(values)
  }

  trait FunctionIdOrder extends Ordering[Slice[Byte]]

  final class Memory extends FunctionStore {

    val hashMap = new ConcurrentHashMap[Slice[Byte], SwayFunction]()

    override def get(functionId: String): Option[SwayFunction] =
      get(Slice.writeString[Byte](functionId))

    override def get(functionId: Slice[Byte]): Option[SwayFunction] =
      Option(hashMap.get(functionId))

    override def put(functionId: String, function: SwayFunction): OK =
      put(Slice.writeString[Byte](functionId), function)

    override def put(functionId: Slice[Byte], function: SwayFunction): OK =
      if (hashMap.putIfAbsent(functionId, function) == null)
        OK.instance
      else
        throw new IllegalArgumentException(s"Duplicate functionId '${functionId.readString()}'. functionId should be unique.")

    override def contains(functionId: Slice[Byte]): Boolean =
      get(functionId).isDefined

    override def remove(functionId: Slice[Byte]): SwayFunction =
      hashMap.remove(functionId)

    def asScala: mutable.Map[Slice[Byte], SwayFunction] =
      hashMap.asScala

    def size =
      hashMap.size()
  }
}
