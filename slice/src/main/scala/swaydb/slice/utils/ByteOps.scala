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

package swaydb.slice.utils

import swaydb.slice._
import swaydb.utils.Maybe.Maybe

import java.nio.charset.Charset
import scala.reflect.ClassTag

abstract class ByteOps[@specialized(Byte) B](implicit val classTag: ClassTag[B]) {

  def writeInt(int: Int, slice: SliceMut[B]): Unit

  def readInt(reader: ReaderBase[B]): Int

  def readInt(bytes: SliceRO[B]): Int

  def writeLong(long: Long, slice: SliceMut[B]): Unit

  def readLong(bytes: SliceRO[B]): Long

  def readLong(reader: ReaderBase[B]): Long

  def readBoolean(reader: ReaderBase[B]): Boolean

  def readBoolean(slice: SliceRO[B]): Boolean

  def readString(reader: ReaderBase[B], charset: Charset): String

  def readString(size: Int,
                 reader: ReaderBase[B],
                 charset: Charset): String

  def readString(slice: SliceRO[B], charset: Charset): String

  def readStringWithSize(slice: SliceRO[B], charset: Charset): String

  def readStringWithSizeUTF8(slice: SliceRO[B]): String

  def readStringWithSizeUTF8(reader: ReaderBase[B]): String

  def writeString(string: String,
                  bytes: SliceMut[B],
                  charsets: Charset): SliceMut[B]

  def writeString(string: String,
                  charsets: Charset): Slice[B]

  def writeStringWithSize(string: String,
                          charsets: Charset): Slice[B]

  def writeStringWithSize(string: String,
                          bytes: SliceMut[B],
                          charsets: Charset): SliceMut[B]

  def writeStringWithSizeUTF8(string: String): Slice[B]

  def writeSignedInt(x: Int, slice: SliceMut[B]): Unit

  def readSignedInt(reader: ReaderBase[B]): Int

  def readSignedInt(slice: SliceRO[B]): Int

  def writeUnsignedInt(int: Int, slice: SliceMut[B]): Unit

  def writeBoolean(bool: Boolean, slice: SliceMut[B]): SliceMut[B]

  private[swaydb] def writeUnsignedIntNonZero(int: Int, slice: SliceMut[B]): Unit

  private[swaydb] def readUnsignedIntNonZero(slice: SliceRO[B]): Int

  private[swaydb] def readUnsignedIntNonZero(reader: ReaderBase[B]): Int

  private[swaydb] def readUnsignedIntNonZeroStrict(reader: ReaderBase[B]): Maybe[Int]

  private[swaydb] def readUnsignedIntNonZeroWithByteSize(slice: SliceRO[B]): (Int, Int)

  private[swaydb] def readUnsignedIntNonZeroWithByteSize(reader: ReaderBase[B]): (Int, Int)

  def writeUnsignedIntReversed(int: Int): Slice[B]

  def readUnsignedInt(reader: ReaderBase[B]): Int

  def readUnsignedInt(sliceReader: SliceReader[B]): Int

  def readUnsignedInt(slice: SliceRO[B]): Int

  def readUnsignedIntWithByteSize(slice: SliceRO[B]): (Int, Int)

  def readUnsignedIntWithByteSize(reader: ReaderBase[B]): (Int, Int)

  def readUnsignedIntWithByteSize(reader: SliceReader[B]): (Int, Int)

  def readLastUnsignedInt(slice: SliceRO[B]): (Int, Int)

  def writeSignedLong(long: Long, slice: SliceMut[B]): Unit

  def readSignedLong(reader: ReaderBase[B]): Long

  def readSignedLong(slice: SliceRO[B]): Long

  def writeUnsignedLong(long: Long, slice: SliceMut[B]): Unit

  def readUnsignedLong(reader: ReaderBase[B]): Long

  def readUnsignedLong(slice: SliceRO[B]): Long

  def readUnsignedLongWithByteSize(slice: SliceRO[B]): (Long, Int)

  def readUnsignedLongByteSize(slice: SliceRO[B]): Int
}

object ByteOps {
  final implicit val Scala: ByteOps[Byte] = ScalaByteOps
  final implicit val Java: ByteOps[java.lang.Byte] = ScalaByteOps.asInstanceOf[ByteOps[java.lang.Byte]]
}
