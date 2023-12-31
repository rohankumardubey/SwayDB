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

package swaydb.core.segment.io

import swaydb.core.segment.block.binarysearch.BinarySearchIndexBlockConfig
import swaydb.core.segment.block.bloomfilter.BloomFilterBlockConfig
import swaydb.core.segment.block.hashindex.HashIndexBlockConfig
import swaydb.core.segment.block.segment.SegmentBlockConfig
import swaydb.core.segment.block.sortedindex.SortedIndexBlockConfig
import swaydb.core.segment.block.values.ValuesBlockConfig
import swaydb.effect.{IOAction, IOStrategy}

private[core] object SegmentReadIO {

  def defaultSynchronisedStoredIfCompressed =
    new SegmentReadIO(
      fileOpenIO = IOStrategy.SynchronisedIO(cacheOnAccess = true),
      segmentBlockIO = IOStrategy.defaultSynchronised,
      hashIndexBlockIO = IOStrategy.defaultSynchronised,
      bloomFilterBlockIO = IOStrategy.defaultSynchronised,
      binarySearchIndexBlockIO = IOStrategy.defaultSynchronised,
      sortedIndexBlockIO = IOStrategy.defaultSynchronised,
      valuesBlockIO = IOStrategy.defaultSynchronised,
      segmentFooterBlockIO = IOStrategy.defaultSynchronised
    )

  def apply(bloomFilterConfig: BloomFilterBlockConfig,
            hashIndexConfig: HashIndexBlockConfig,
            binarySearchIndexConfig: BinarySearchIndexBlockConfig,
            sortedIndexConfig: SortedIndexBlockConfig,
            valuesConfig: ValuesBlockConfig,
            segmentConfig: SegmentBlockConfig): SegmentReadIO =
    new SegmentReadIO(
      fileOpenIO = IOStrategy.SynchronisedIO(cacheOnAccess = true),
      segmentBlockIO = segmentConfig.blockIOStrategy,
      hashIndexBlockIO = hashIndexConfig.ioStrategy,
      bloomFilterBlockIO = bloomFilterConfig.ioStrategy,
      binarySearchIndexBlockIO = binarySearchIndexConfig.ioStrategy,
      sortedIndexBlockIO = sortedIndexConfig.ioStrategy,
      valuesBlockIO = valuesConfig.ioStrategy,
      segmentFooterBlockIO = segmentConfig.blockIOStrategy
    )
}

private[core] case class SegmentReadIO(fileOpenIO: IOStrategy.ThreadSafe,
                                       segmentBlockIO: IOAction => IOStrategy,
                                       hashIndexBlockIO: IOAction => IOStrategy,
                                       bloomFilterBlockIO: IOAction => IOStrategy,
                                       binarySearchIndexBlockIO: IOAction => IOStrategy,
                                       sortedIndexBlockIO: IOAction => IOStrategy,
                                       valuesBlockIO: IOAction => IOStrategy,
                                       segmentFooterBlockIO: IOAction => IOStrategy)
