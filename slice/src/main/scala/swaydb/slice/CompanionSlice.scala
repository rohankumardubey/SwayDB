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

package swaydb.slice

import swaydb.slice.utils.ScalaByteOps
import swaydb.utils.{Aggregator, ByteSizeOf}

import java.io.ByteArrayInputStream
import java.nio.ByteBuffer
import java.nio.charset.{Charset, StandardCharsets}
import scala.collection.{Iterable, Iterator, Seq}
import scala.concurrent.{ExecutionContext, Future}
import scala.reflect.ClassTag

/**
 * Companion implementation for [[Slice]].
 *
 * This is a trait because the [[Slice]] class itself is getting too
 * long even though inheritance such as like this is discouraged.
 */
trait CompanionSlice extends SliceBuildFrom {

  val emptyBytes: Slice[Byte] =
    of[Byte](0)

  @inline final def empty[T: ClassTag]: Slice[T] =
    of[T](0)

  final def range(from: Int, to: Int): Slice[Int] = {
    val slice = of[Int](to - from + 1)
    (from to to) foreach slice.add
    slice
  }

  final def range(from: Char, to: Char): Slice[Char] = {
    val slice = of[Char](26)
    (from to to) foreach slice.add
    slice.close()
  }

  final def range(from: Byte, to: Byte): Slice[Byte] = {
    val slice = of[Byte](to - from + 1)

    (from to to) foreach {
      i =>
        slice add i.toByte
    }

    slice.close()
  }

  def fill[T: ClassTag](length: Int)(elem: => T): Slice[T] =
    new SliceMut[T](
      array = Array.fill(length)(elem),
      fromOffset = 0,
      toOffset = if (length == 0) -1 else length - 1,
      _written = length
    )

  def ofBytes(length: Int): SliceMut[Byte] =
    of[Byte](length)

  @inline final def of[T: ClassTag](length: Int, isFull: Boolean = false): SliceMut[T] =
    new SliceMut(
      array = new Array[T](length),
      fromOffset = 0,
      toOffset = if (length == 0) -1 else length - 1,
      _written = if (isFull) length else 0
    )

  def of(array: Array[Byte]): Slice[Byte] =
    apply(array)

  def apply[T: ClassTag](data: Array[T]): Slice[T] =
    if (data.length == 0)
      of[T](0)
    else
      new SliceMut[T](
        array = data,
        fromOffset = 0,
        toOffset = data.length - 1,
        _written = data.length
      )

  def from[T: ClassTag](iterator: Iterator[T], size: Int): Slice[T] = {
    val slice = of[T](size)
    iterator foreach slice.add
    slice
  }

  def from[T: ClassTag](iterable: Iterable[T], size: Int): Slice[T] = {
    val slice = of[T](size)
    iterable foreach slice.add
    slice
  }

  def from[T: ClassTag](iterable: Iterable[Slice[T]]): Slice[T] = {
    val slice = of[T](iterable.foldLeft(0)(_ + _.size))
    iterable foreach slice.addAll
    slice
  }

  def of(byteBuffer: ByteBuffer): Slice[Byte] =
    new SliceMut[Byte](
      array = byteBuffer.array(),
      fromOffset = byteBuffer.arrayOffset(),
      toOffset = byteBuffer.position() - 1,
      _written = byteBuffer.position()
    )

  def of(byteBuffer: ByteBuffer, from: Int, to: Int): Slice[Byte] =
    new SliceMut[Byte](
      array = byteBuffer.array(),
      fromOffset = from,
      toOffset = to,
      _written = to - from + 1
    )

  @inline final def apply[T: ClassTag](data: T*): Slice[T] =
    Slice(data.toArray)

  @inline final def writeInt(integer: Int): Slice[Byte] =
    of[Byte](ByteSizeOf.int).addInt(integer)

  @inline final def writeUnsignedInt(integer: Int): Slice[Byte] =
    of[Byte](ByteSizeOf.varInt).addUnsignedInt(integer).close()

  @inline final def writeSignedInt(integer: Int): Slice[Byte] =
    of[Byte](ByteSizeOf.varInt).addSignedInt(integer).close()

  @inline final def writeLong(num: Long): Slice[Byte] =
    of[Byte](ByteSizeOf.long).addLong(num)

  @inline final def writeUnsignedLong(num: Long): Slice[Byte] =
    of[Byte](ByteSizeOf.varLong).addUnsignedLong(num).close()

  @inline final def writeSignedLong(num: Long): Slice[Byte] =
    of[Byte](ByteSizeOf.varLong).addSignedLong(num).close()

  @inline final def writeBoolean(bool: Boolean): Slice[Byte] =
    of[Byte](1).addBoolean(bool)

  @inline final def writeString(string: String, charsets: Charset = StandardCharsets.UTF_8): Slice[Byte] =
    ScalaByteOps.writeString(string, charsets)

  @inline final def writeStringUTF8(string: String): Slice[Byte] =
    ScalaByteOps.writeString(string, StandardCharsets.UTF_8)

  @inline final def intersects[T](range1: (Slice[T], Slice[T]),
                                  range2: (Slice[T], Slice[T]))(implicit ordering: Ordering[Slice[T]]): Boolean =
    intersects(
      range1 = (range1._1, range1._2, true),
      range2 = (range2._1, range2._2, true)
    )

  def within[T](key: Slice[T],
                minKey: Slice[T],
                maxKey: Slice[T],
                maxKeyInclusive: Boolean)(implicit keyOrder: Ordering[Slice[T]]): Boolean = {
    import keyOrder._

    key >= minKey && {
      if (maxKeyInclusive)
        key <= maxKey
      else
        key < maxKey
    }
  }

  def within[T](key: T,
                minKey: T,
                maxKey: T,
                maxKeyInclusive: Boolean)(implicit keyOrder: Ordering[T]): Boolean = {
    import keyOrder._

    key >= minKey && {
      if (maxKeyInclusive)
        key <= maxKey
      else
        key < maxKey
    }
  }


  def minMax[T](left: Option[(Slice[T], Slice[T], Boolean)],
                right: Option[(Slice[T], Slice[T], Boolean)])(implicit keyOrder: Ordering[Slice[T]]): Option[(Slice[T], Slice[T], Boolean)] = {
    for {
      lft <- left
      rht <- right
    } yield minMax(lft, rht)
  } orElse left.orElse(right)

  def minMax[T](left: (Slice[T], Slice[T], Boolean),
                right: (Slice[T], Slice[T], Boolean))(implicit keyOrder: Ordering[Slice[T]]): (Slice[T], Slice[T], Boolean) = {
    val min = keyOrder.min(left._1, right._1)
    val maxCompare = keyOrder.compare(left._2, right._2)
    if (maxCompare == 0)
      (min, left._2, left._3 || right._3)
    else if (maxCompare < 0)
      (min, right._2, right._3)
    else
      (min, left._2, left._3)
  }

  /**
   * Boolean indicates if the toKey is inclusive.
   */
  def intersects[T](range1: (T, T, Boolean),
                    range2: (T, T, Boolean))(implicit ordering: Ordering[T]): Boolean = {
    import ordering._

    def check(range1: (T, T, Boolean),
              range2: (T, T, Boolean)): Boolean =
      if (range1._3 && range2._3)
        range1._1 >= range2._1 && range1._1 <= range2._2 ||
          range1._2 >= range2._1 && range1._2 <= range2._2
      else if (!range1._3 && range2._3)
        range1._1 >= range2._1 && range1._1 <= range2._2 ||
          range1._2 > range2._1 && range1._2 < range2._2
      else if (range1._3 && !range2._3)
        range1._1 >= range2._1 && range1._1 < range2._2 ||
          range1._2 >= range2._1 && range1._2 < range2._2
      else //both are false
        range1._1 >= range2._1 && range1._1 < range2._2 ||
          range1._2 > range2._1 && range1._2 < range2._2

    check(range1, range2) || check(range2, range1)
  }

  def groupedBySize[T: ClassTag](minGroupSize: Int,
                                 itemSize: T => Int,
                                 items: Slice[T]): Slice[Slice[T]] =
    if (minGroupSize <= 0) {
      Slice(items)
    } else {
      val allGroups =
        Slice
          .of[SliceMut[T]](items.size)
          .add(Slice.of[T](items.size))

      var currentGroupSize: Int = 0

      var i = 0
      while (i < items.size) {
        if (currentGroupSize < minGroupSize) {
          val currentItem = items(i)
          allGroups.last add currentItem
          currentGroupSize += itemSize(currentItem)
          i += 1
        } else {
          val tailItemsSize = items.drop(i).foldLeft(0)(_ + itemSize(_))
          if (tailItemsSize >= minGroupSize) {
            val newGroup = Slice.of[T](items.size - i + 1)
            allGroups add newGroup
            currentGroupSize = 0
          } else {
            currentGroupSize = minGroupSize - 1
          }
        }
      }

      allGroups
    }

  implicit class SlicesImplicits[T: ClassTag](slices: Slice[Slice[T]]) {
    /**
     * Closes this Slice and children Slices which disables
     * more data to be written to any of the Slices.
     */
    @inline final def closeAll(): Slice[Slice[T]] = {
      val newSlices = of[Slice[T]](slices.close().size)

      slices foreach {
        slice =>
          newSlices.add(slice.close())
      }

      newSlices
    }
  }

  implicit class OptionByteSliceImplicits(slice: Option[Slice[Byte]]) {

    @inline final def cut(): Option[Slice[Byte]] =
      slice flatMap {
        slice =>
          if (slice.isEmpty)
            None
          else
            Some(slice.cut())
      }
  }

  implicit class SeqByteSliceImplicits(slice: Seq[Slice[Byte]]) {

    @inline final def cut(): Seq[Slice[Byte]] =
      if (slice.isEmpty)
        slice
      else
        slice.map(_.cut())
  }

  implicit class JavaByteSliced(sliced: Slice[java.lang.Byte]) {
    @inline def cast: Slice[Byte] =
      sliced.asInstanceOf[Slice[Byte]]
  }

  implicit class ScalaByteSliced(self: Slice[Byte]) {
    @inline def cast: Slice[java.lang.Byte] =
      self.asInstanceOf[Slice[java.lang.Byte]]

    @inline def readBoolean(): Boolean =
      ScalaByteOps.readBoolean(self)

    @inline def readInt(): Int =
      ScalaByteOps.readInt(self)

    @inline def dropUnsignedInt(): Slice[Byte] = {
      val (_, byteSize) = readUnsignedIntWithByteSize()
      self drop byteSize
    }

    @inline def readSignedInt(): Int =
      ScalaByteOps.readSignedInt(self)

    @inline def readUnsignedInt(): Int =
      ScalaByteOps.readUnsignedInt(self)

    @inline def readUnsignedIntWithByteSize(): (Int, Int) =
      ScalaByteOps.readUnsignedIntWithByteSize(self)

    @inline def readNonZeroUnsignedIntWithByteSize(): (Int, Int) =
      ScalaByteOps.readUnsignedIntNonZeroWithByteSize(self)

    @inline def readLong(): Long =
      ScalaByteOps.readLong(self)

    @inline def readUnsignedLong(): Long =
      ScalaByteOps.readUnsignedLong(self)

    @inline def readUnsignedLongWithByteSize(): (Long, Int) =
      ScalaByteOps.readUnsignedLongWithByteSize(self)

    @inline def readUnsignedLongByteSize(): Int =
      ScalaByteOps.readUnsignedLongByteSize(self)

    @inline def readSignedLong(): Long =
      ScalaByteOps.readSignedLong(self)

    @inline def readString(charset: Charset = StandardCharsets.UTF_8): String =
      ScalaByteOps.readString(self, charset)

    @inline def readStringUTF8(): String =
      ScalaByteOps.readString(self, StandardCharsets.UTF_8)

    @inline def createReader(): SliceReader =
      SliceReader(self)

    @inline def toByteBufferWrap: ByteBuffer =
      ByteBuffer.wrap(self.unsafeInnerByteArray, self.fromOffset, self.size)

    @inline def toByteBufferDirect: ByteBuffer =
      ByteBuffer
        .allocateDirect(self.size)
        .put(self.unsafeInnerByteArray, 0, self.size)

    @inline def toByteArrayInputStream: ByteArrayInputStream =
      new ByteArrayInputStream(self.unsafeInnerByteArray, self.fromOffset, self.size)
  }

  final def sequence[A: ClassTag](in: Iterable[Future[A]])(implicit ec: ExecutionContext): Future[Slice[A]] =
    in.iterator.foldLeft(Future.successful(Slice.of[A](in.size))) {
      case (result, next) =>
        result.zipWith(next)(_ add _)
    }

  @inline private[swaydb] final def newAggregator[T: ClassTag](maxSize: Int): Aggregator[T, Slice[T]] =
    new SliceBuilder[T](maxSize)
}