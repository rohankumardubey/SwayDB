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
 * If you modify this Program, or any covered work, by linking or combining
 * it with other code, such other code is not for that reason alone subject
 * to any of the requirements of the GNU Affero GPL version 3.
 */

package swaydb.data.config

import swaydb.Compression
import swaydb.data.util.Java.JavaFunction

import scala.jdk.CollectionConverters._

class ValuesConfigBuilder {
  private var compressDuplicateValues: Boolean = _
  private var compressDuplicateRangeValues: Boolean = _
  private var ioStrategy: JavaFunction[IOAction, IOStrategy] = _
  private var compression: JavaFunction[UncompressedBlockInfo, java.lang.Iterable[Compression]] = _
}

object ValuesConfigBuilder {

  class Step0(builder: ValuesConfigBuilder) {
    def withCompressDuplicateValues(compressDuplicateValues: Boolean) = {
      builder.compressDuplicateValues = compressDuplicateValues
      new Step1(builder)
    }
  }

  class Step1(builder: ValuesConfigBuilder) {
    def withCompressDuplicateRangeValues(compressDuplicateRangeValues: Boolean) = {
      builder.compressDuplicateRangeValues = compressDuplicateRangeValues
      new Step2(builder)
    }
  }

  class Step2(builder: ValuesConfigBuilder) {
    def withIoStrategy(ioStrategy: JavaFunction[IOAction, IOStrategy]) = {
      builder.ioStrategy = ioStrategy
      new Step3(builder)
    }
  }

  class Step3(builder: ValuesConfigBuilder) {
    def withCompression(compression: JavaFunction[UncompressedBlockInfo, java.lang.Iterable[Compression]]) = {
      builder.compression = compression
      new Step4(builder)
    }
  }

  class Step4(builder: ValuesConfigBuilder) {
    def build() =
      ValuesConfig(
        compressDuplicateValues = builder.compressDuplicateValues,
        compressDuplicateRangeValues = builder.compressDuplicateRangeValues,
        ioStrategy = builder.ioStrategy.apply,
        compression = builder.compression.apply(_).asScala
      )
  }

  def builder() = new Step0(new ValuesConfigBuilder())
}