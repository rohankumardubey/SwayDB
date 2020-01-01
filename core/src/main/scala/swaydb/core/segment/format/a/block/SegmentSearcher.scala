/*
 * Copyright (c) 2020 Simer Plaha (@simerplaha)
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
package swaydb.core.segment.format.a.block

import com.typesafe.scalalogging.LazyLogging
import swaydb.core.data.{Persistent, PersistentOptional}
import swaydb.core.segment.format.a.block.binarysearch.BinarySearchIndexBlock
import swaydb.core.segment.format.a.block.hashindex.HashIndexBlock
import swaydb.core.segment.format.a.block.reader.UnblockedReader
import swaydb.data.order.KeyOrder
import swaydb.data.slice.Slice

private[core] trait SegmentSearcher {

  def searchSequential(key: Slice[Byte],
                       start: PersistentOptional,
                       sortedIndexReader: UnblockedReader[SortedIndexBlock.Offset, SortedIndexBlock],
                       valuesReaderOrNull: UnblockedReader[ValuesBlock.Offset, ValuesBlock])(implicit keyOrder: KeyOrder[Slice[Byte]],
                                                                                               partialKeyOrder: KeyOrder[Persistent.Partial]): PersistentOptional

  def searchRandom(key: Slice[Byte],
                   start: PersistentOptional,
                   end: => PersistentOptional,
                   hashIndexReaderOrNull: UnblockedReader[HashIndexBlock.Offset, HashIndexBlock],
                   binarySearchIndexReaderOrNull: => UnblockedReader[BinarySearchIndexBlock.Offset, BinarySearchIndexBlock],
                   sortedIndexReader: UnblockedReader[SortedIndexBlock.Offset, SortedIndexBlock],
                   valuesReaderOrNull: UnblockedReader[ValuesBlock.Offset, ValuesBlock],
                   hasRange: Boolean,
                   keyValueCount: => Int)(implicit keyOrder: KeyOrder[Slice[Byte]],
                                          partialKeyOrder: KeyOrder[Persistent.Partial]): PersistentOptional

  def searchHigherRandomly(key: Slice[Byte],
                           start: PersistentOptional,
                           end: PersistentOptional,
                           keyValueCount: => Int,
                           binarySearchIndexReaderOrNull: => UnblockedReader[BinarySearchIndexBlock.Offset, BinarySearchIndexBlock],
                           sortedIndexReader: UnblockedReader[SortedIndexBlock.Offset, SortedIndexBlock],
                           valuesReaderOrNull: UnblockedReader[ValuesBlock.Offset, ValuesBlock])(implicit keyOrder: KeyOrder[Slice[Byte]],
                                                                                                   partialKeyOrder: KeyOrder[Persistent.Partial]): PersistentOptional

  def searchHigherSequentially(key: Slice[Byte],
                               start: PersistentOptional,
                               sortedIndexReader: UnblockedReader[SortedIndexBlock.Offset, SortedIndexBlock],
                               valuesReaderOrNull: UnblockedReader[ValuesBlock.Offset, ValuesBlock])(implicit keyOrder: KeyOrder[Slice[Byte]],
                                                                                                       partialKeyOrder: KeyOrder[Persistent.Partial]): PersistentOptional

  def searchLower(key: Slice[Byte],
                  start: PersistentOptional,
                  end: PersistentOptional,
                  keyValueCount: Int,
                  binarySearchIndexReaderOrNull: UnblockedReader[BinarySearchIndexBlock.Offset, BinarySearchIndexBlock],
                  sortedIndexReader: UnblockedReader[SortedIndexBlock.Offset, SortedIndexBlock],
                  valuesReaderOrNull: UnblockedReader[ValuesBlock.Offset, ValuesBlock])(implicit keyOrder: KeyOrder[Slice[Byte]],
                                                                                          partialOrdering: KeyOrder[Persistent.Partial]): PersistentOptional
}

private[core] object SegmentSearcher extends SegmentSearcher with LazyLogging {

  var seqSeeks = 0
  var successfulSeqSeeks = 0
  var failedSeqSeeks = 0

  var hashIndexSeeks = 0
  var successfulHashIndexSeeks = 0
  var failedHashIndexSeeks = 0

  def searchSequential(key: Slice[Byte],
                       start: PersistentOptional,
                       sortedIndexReader: UnblockedReader[SortedIndexBlock.Offset, SortedIndexBlock],
                       valuesReaderOrNull: UnblockedReader[ValuesBlock.Offset, ValuesBlock])(implicit keyOrder: KeyOrder[Slice[Byte]],
                                                                                               partialKeyOrder: KeyOrder[Persistent.Partial]): PersistentOptional =
    if (start.isSomeS)
      SortedIndexBlock.searchSeekOne(
        key = key,
        start = start.getS,
        indexReader = sortedIndexReader,
        valuesReaderOrNull = valuesReaderOrNull
      )
    else
      SortedIndexBlock.searchSeekOne(
        key = key,
        fromPosition = 0,
        keySizeOrNull = null,
        indexReader = sortedIndexReader,
        valuesReaderOrNull = valuesReaderOrNull
      )

  def searchRandom(key: Slice[Byte],
                   start: PersistentOptional,
                   end: => PersistentOptional,
                   hashIndexReaderOrNull: UnblockedReader[HashIndexBlock.Offset, HashIndexBlock],
                   binarySearchIndexReaderOrNull: => UnblockedReader[BinarySearchIndexBlock.Offset, BinarySearchIndexBlock],
                   sortedIndexReader: UnblockedReader[SortedIndexBlock.Offset, SortedIndexBlock],
                   valuesReaderOrNull: UnblockedReader[ValuesBlock.Offset, ValuesBlock],
                   hasRange: Boolean,
                   keyValueCount: => Int)(implicit keyOrder: KeyOrder[Slice[Byte]],
                                          partialKeyOrder: KeyOrder[Persistent.Partial]): PersistentOptional =
    if (hashIndexReaderOrNull == null)
      BinarySearchIndexBlock.search(
        key = key,
        lowest = start,
        highest = end,
        keyValuesCount = keyValueCount,
        binarySearchIndexReaderOrNull = binarySearchIndexReaderOrNull,
        sortedIndexReader = sortedIndexReader,
        valuesReaderOrNull = valuesReaderOrNull
      ).toPersistentOptional
    else
    //      hashIndexSeeks += 1
    //println
    //println(s"Search key: ${key.readInt()}")
      HashIndexBlock.search(
        key = key,
        hashIndexReader = hashIndexReaderOrNull,
        sortedIndexReader = sortedIndexReader,
        valuesReaderOrNull = valuesReaderOrNull
      ) match {
        case Persistent.Partial.Null =>
          if (hashIndexReaderOrNull.block.isPerfect && !sortedIndexReader.block.hasPrefixCompression && !hasRange) {
            Persistent.Null
          } else {
            failedHashIndexSeeks += 1
            BinarySearchIndexBlock.search(
              key = key,
              lowest = start,
              highest = end,
              keyValuesCount = keyValueCount,
              binarySearchIndexReaderOrNull = binarySearchIndexReaderOrNull,
              sortedIndexReader = sortedIndexReader,
              valuesReaderOrNull = valuesReaderOrNull
            ).toPersistentOptional
          }

        case keyValue: Persistent.Partial =>
          successfulHashIndexSeeks += 1
          keyValue.toPersistent
      }

  def searchHigherSequentially(key: Slice[Byte],
                               start: PersistentOptional,
                               sortedIndexReader: UnblockedReader[SortedIndexBlock.Offset, SortedIndexBlock],
                               valuesReaderOrNull: UnblockedReader[ValuesBlock.Offset, ValuesBlock])(implicit keyOrder: KeyOrder[Slice[Byte]],
                                                                                                       partialKeyOrder: KeyOrder[Persistent.Partial]): PersistentOptional =
    if (start.isSomeS)
      if (keyOrder.equiv(key, start.getS.key))
        SortedIndexBlock.readNextKeyValue(
          previous = start.getS,
          sortedIndexReader = sortedIndexReader,
          valuesReaderOrNull = valuesReaderOrNull
        )
      else
        SortedIndexBlock.searchHigherSeekOne(
          key = key,
          startFrom = start.getS,
          sortedIndexReader = sortedIndexReader,
          valuesReaderOrNull = valuesReaderOrNull
        )
    else
      SortedIndexBlock.searchHigherSeekOne(
        key = key,
        fromPosition = 0,
        keySizeOrNull = null,
        sortedIndexReader = sortedIndexReader,
        valuesReaderOrNull = valuesReaderOrNull
      )

  def searchHigherRandomly(key: Slice[Byte],
                           start: PersistentOptional,
                           end: PersistentOptional,
                           keyValueCount: => Int,
                           binarySearchIndexReaderOrNull: => UnblockedReader[BinarySearchIndexBlock.Offset, BinarySearchIndexBlock],
                           sortedIndexReader: UnblockedReader[SortedIndexBlock.Offset, SortedIndexBlock],
                           valuesReaderOrNull: UnblockedReader[ValuesBlock.Offset, ValuesBlock])(implicit keyOrder: KeyOrder[Slice[Byte]],
                                                                                                   partialKeyOrder: KeyOrder[Persistent.Partial]): PersistentOptional =
    BinarySearchIndexBlock.searchHigher(
      key = key,
      start = start,
      end = end,
      keyValuesCount = keyValueCount,
      binarySearchIndexReaderOrNull = binarySearchIndexReaderOrNull,
      sortedIndexReader = sortedIndexReader,
      valuesReaderOrNull = valuesReaderOrNull
    )

  def searchLower(key: Slice[Byte],
                  start: PersistentOptional,
                  end: PersistentOptional,
                  keyValueCount: Int,
                  binarySearchIndexReaderOrNull: UnblockedReader[BinarySearchIndexBlock.Offset, BinarySearchIndexBlock],
                  sortedIndexReader: UnblockedReader[SortedIndexBlock.Offset, SortedIndexBlock],
                  valuesReaderOrNull: UnblockedReader[ValuesBlock.Offset, ValuesBlock])(implicit keyOrder: KeyOrder[Slice[Byte]],
                                                                                          partialOrdering: KeyOrder[Persistent.Partial]): PersistentOptional =
    BinarySearchIndexBlock.searchLower(
      key = key,
      start = start,
      end = end,
      keyValuesCount = keyValueCount,
      binarySearchIndexReaderOrNull = binarySearchIndexReaderOrNull,
      sortedIndexReader = sortedIndexReader,
      valuesReaderOrNull = valuesReaderOrNull
    )
}
