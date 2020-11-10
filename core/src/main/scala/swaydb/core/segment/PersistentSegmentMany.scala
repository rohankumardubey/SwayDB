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
 * If you modify this Program or any covered work, only by linking or combining
 * it with separate works, the licensors of this Program grant you additional
 * permission to convey the resulting work.
 */

package swaydb.core.segment

import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap

import com.typesafe.scalalogging.LazyLogging
import swaydb.Error.Segment.ExceptionHandler
import swaydb.IO
import swaydb.core.actor.ByteBufferSweeper.ByteBufferSweeperActor
import swaydb.core.actor.{FileSweeper, MemorySweeper}
import swaydb.core.data._
import swaydb.core.function.FunctionStore
import swaydb.core.io.file.{BlockCache, DBFile, Effect, ForceSaveApplier}
import swaydb.core.level.PathsDistributor
import swaydb.core.segment.assigner.Assignable
import swaydb.core.segment.format.a.block.binarysearch.BinarySearchIndexBlock
import swaydb.core.segment.format.a.block.bloomfilter.BloomFilterBlock
import swaydb.core.segment.format.a.block.hashindex.HashIndexBlock
import swaydb.core.segment.format.a.block.reader.BlockRefReader
import swaydb.core.segment.format.a.block.segment.SegmentBlock
import swaydb.core.segment.format.a.block.segment.data.{TransientSegment, TransientSegmentSerialiser}
import swaydb.core.segment.format.a.block.sortedindex.SortedIndexBlock
import swaydb.core.segment.format.a.block.values.ValuesBlock
import swaydb.core.util._
import swaydb.core.util.skiplist.SkipListTreeMap
import swaydb.data.MaxKey
import swaydb.data.cache.{Cache, CacheNoIO}
import swaydb.data.compaction.ParallelMerge.SegmentParallelism
import swaydb.data.config.Dir
import swaydb.data.order.{KeyOrder, TimeOrder}
import swaydb.data.slice.{Slice, SliceOption}

import scala.annotation.tailrec
import scala.collection.compat._
import scala.concurrent.ExecutionContext
import scala.concurrent.duration.{Deadline, FiniteDuration}
import scala.jdk.CollectionConverters._

protected case object PersistentSegmentMany {

  val formatId: Byte = 127
  val formatIdSlice: Slice[Byte] = Slice(formatId)

  def apply(file: DBFile,
            createdInLevel: Int,
            segment: TransientSegment.Many)(implicit keyOrder: KeyOrder[Slice[Byte]],
                                            timeOrder: TimeOrder[Slice[Byte]],
                                            functionStore: FunctionStore,
                                            keyValueMemorySweeper: Option[MemorySweeper.KeyValue],
                                            blockCache: Option[BlockCache.State],
                                            fileSweeper: FileSweeper,
                                            bufferCleaner: ByteBufferSweeperActor,
                                            segmentIO: SegmentIO,
                                            forceSaveApplier: ForceSaveApplier): PersistentSegmentMany = {

    implicit val blockMemorySweeper: Option[MemorySweeper.Block] = blockCache.map(_.sweeper)

    val segments = new ConcurrentHashMap[Int, SegmentRef]()

    val listSegmentSize = segment.listSegment.segmentSize

    val firstSegmentOffset = segment.fileHeader.size + listSegmentSize

    val cacheBlocksOnCreate = segment.listSegment.sortedIndexUnblockedReader.isDefined

    //enable cache only if cacheBlocksOnCreate is true.
    if (cacheBlocksOnCreate)
      segment
        .segments
        .foldLeft(firstSegmentOffset) {
          case (actualOffset, singleton) =>
            val thisSegmentSize = singleton.segmentSize

            val blockRef =
              BlockRefReader(
                file = file,
                start = actualOffset,
                fileSize = thisSegmentSize
              )

            val pathNameOffset = actualOffset - firstSegmentOffset

            val ref =
              SegmentRef(
                path = file.path.resolve(s"ref.$pathNameOffset"),
                minKey = singleton.minKey,
                maxKey = singleton.maxKey,
                nearestPutDeadline = singleton.nearestPutDeadline,
                minMaxFunctionId = singleton.minMaxFunctionId,
                blockRef = blockRef,
                segmentIO = segmentIO,
                valuesReaderCacheable = singleton.valuesUnblockedReader,
                sortedIndexReaderCacheable = singleton.sortedIndexUnblockedReader,
                hashIndexReaderCacheable = singleton.hashIndexUnblockedReader,
                binarySearchIndexReaderCacheable = singleton.binarySearchUnblockedReader,
                bloomFilterReaderCacheable = singleton.bloomFilterUnblockedReader,
                footerCacheable = singleton.footerUnblocked
              )

            segments.put(pathNameOffset, ref)

            actualOffset + thisSegmentSize
        }

    val listSegment =
      if (cacheBlocksOnCreate)
        Some(
          SegmentRef(
            path = file.path,
            minKey = segment.listSegment.minKey,
            maxKey = segment.listSegment.maxKey,
            nearestPutDeadline = segment.listSegment.nearestPutDeadline,
            minMaxFunctionId = segment.listSegment.minMaxFunctionId,
            blockRef =
              BlockRefReader(
                file = file,
                start = segment.fileHeader.size,
                fileSize = listSegmentSize
              ),
            segmentIO = segmentIO,
            valuesReaderCacheable = segment.listSegment.valuesUnblockedReader,
            sortedIndexReaderCacheable = segment.listSegment.sortedIndexUnblockedReader,
            hashIndexReaderCacheable = segment.listSegment.hashIndexUnblockedReader,
            binarySearchIndexReaderCacheable = segment.listSegment.binarySearchUnblockedReader,
            bloomFilterReaderCacheable = segment.listSegment.bloomFilterUnblockedReader,
            footerCacheable = segment.listSegment.footerUnblocked
          )
        )
      else
        None

    val listSegmentCache =
      Cache.noIO[Unit, SegmentRef](synchronised = true, stored = true, initial = listSegment) {
        (_, _) =>
          initListSegment(
            file = file,
            minKey = segment.listSegment.minKey,
            maxKey = segment.listSegment.maxKey,
            fileBlockRef =
              BlockRefReader(
                file = file,
                start = 1,
                fileSize = segment.segmentSize - 1
              )
          )
      }

    PersistentSegmentMany(
      file = file,
      createdInLevel = createdInLevel,
      minKey = segment.minKey,
      maxKey = segment.maxKey,
      minMaxFunctionId = segment.minMaxFunctionId,
      segmentSize = segment.segmentSize,
      nearestPutDeadline = segment.nearestPutDeadline,
      listSegmentCache = listSegmentCache,
      segmentsCache = segments
    )
  }

  def apply(file: DBFile,
            segmentSize: Int,
            createdInLevel: Int,
            minKey: Slice[Byte],
            maxKey: MaxKey[Slice[Byte]],
            minMaxFunctionId: Option[MinMax[Slice[Byte]]],
            nearestExpiryDeadline: Option[Deadline])(implicit keyOrder: KeyOrder[Slice[Byte]],
                                                     timeOrder: TimeOrder[Slice[Byte]],
                                                     functionStore: FunctionStore,
                                                     keyValueMemorySweeper: Option[MemorySweeper.KeyValue],
                                                     blockCache: Option[BlockCache.State],
                                                     fileSweeper: FileSweeper,
                                                     bufferCleaner: ByteBufferSweeperActor,
                                                     segmentIO: SegmentIO,
                                                     forceSaveApplier: ForceSaveApplier): PersistentSegmentMany = {

    implicit val blockCacheMemorySweeper: Option[MemorySweeper.Block] = blockCache.map(_.sweeper)

    val fileBlockRef: BlockRefReader[SegmentBlock.Offset] =
      BlockRefReader(
        file = file,
        start = 1,
        fileSize = segmentSize - 1
      )

    val listSegmentCache =
      Cache.noIO[Unit, SegmentRef](synchronised = true, stored = true, initial = None) {
        (_, _) =>
          initListSegment(
            file = file,
            minKey = minKey,
            maxKey = maxKey,
            fileBlockRef = fileBlockRef
          )
      }

    new PersistentSegmentMany(
      file = file,
      createdInLevel = createdInLevel,
      minKey = minKey,
      maxKey = maxKey,
      minMaxFunctionId = minMaxFunctionId,
      segmentSize = segmentSize,
      nearestPutDeadline = nearestExpiryDeadline,
      listSegmentCache = listSegmentCache,
      segmentsCache = new ConcurrentHashMap()
    )
  }

  /**
   * Used for recovery only - [[swaydb.core.level.tool.AppendixRepairer]] - Not performance optimised.
   *
   * Used when Segment's information is unknown.
   */
  def apply(file: DBFile)(implicit keyOrder: KeyOrder[Slice[Byte]],
                          timeOrder: TimeOrder[Slice[Byte]],
                          functionStore: FunctionStore,
                          keyValueMemorySweeper: Option[MemorySweeper.KeyValue],
                          blockCacheMemorySweeper: Option[MemorySweeper.Block],
                          blockCache: Option[BlockCache.State],
                          fileSweeper: FileSweeper,
                          bufferCleaner: ByteBufferSweeperActor,
                          segmentIO: SegmentIO,
                          forceSaveApplier: ForceSaveApplier): PersistentSegmentMany = {

    val fileExtension = Effect.fileExtension(file.path)

    if (fileExtension != Extension.Seg)
      throw new Exception(s"Invalid Segment file extension: $fileExtension")

    val fileBlockRef: BlockRefReader[SegmentBlock.Offset] =
      BlockRefReader(
        file = file,
        start = 1,
        fileSize = file.fileSize.toInt - 1
      )

    val listSegment =
      initListSegment(
        file = file,
        minKey = null,
        maxKey = null,
        fileBlockRef = fileBlockRef
      )

    val footer = listSegment.getFooter()

    val segmentRefKeyValues =
      listSegment
        .iterator()
        .toList

    val segmentRefs =
      parseSkipList(
        file = file,
        minKey = null,
        maxKey = null,
        fileBlockRef = fileBlockRef
      )

    val lastSegment =
      segmentRefs.last() match {
        case SegmentRef.Null =>
          throw new Exception("Empty List Segment read. List Segment are non-empty lists.")

        case ref: SegmentRef =>
          ref
      }

    val lastKeyValue =
      lastSegment
        .iterator()
        .foldLeft(Persistent.Null: PersistentOption) {
          case (_, next) =>
            next
        }

    val maxKey =
      lastKeyValue match {
        case fixed: Persistent.Fixed =>
          MaxKey.Fixed(fixed.key.unslice())

        case range: Persistent.Range =>
          MaxKey.Range(range.fromKey.unslice(), range.toKey.unslice())

        case Persistent.Null =>
          throw new Exception("Empty Segment read. Persisted Segments cannot be empty.")
      }

    val allKeyValues = segmentRefs.values().flatMap(_.iterator())

    val deadlineFunctionId = DeadlineAndFunctionId(allKeyValues)

    val minKey = segmentRefKeyValues.head.key.unslice()

    val listSegmentCache =
      Cache.noIO[Unit, SegmentRef](synchronised = true, stored = true, initial = None) {
        case (_, _) =>
          initListSegment(
            file = file,
            minKey = minKey,
            maxKey = maxKey,
            fileBlockRef = fileBlockRef
          )
      }

    PersistentSegmentMany(
      file = file,
      segmentSize = file.fileSize.toInt,
      createdInLevel = footer.createdInLevel,
      minKey = minKey,
      maxKey = maxKey,
      minMaxFunctionId = deadlineFunctionId.minMaxFunctionId.map(_.unslice()),
      nearestPutDeadline = deadlineFunctionId.nearestDeadline,
      listSegmentCache = listSegmentCache,
      //above parsed segmentRefs cannot be used here because
      //it's MaxKey.Range's minKey is set to the Segment's minKey
      //instead of the Segment's last range key-values minKey.
      segmentsCache = new ConcurrentHashMap(),
    )
  }

  private def parseSkipList(file: DBFile,
                            minKey: Slice[Byte],
                            maxKey: MaxKey[Slice[Byte]],
                            fileBlockRef: BlockRefReader[SegmentBlock.Offset])(implicit keyOrder: KeyOrder[Slice[Byte]],
                                                                               keyValueMemorySweeper: Option[MemorySweeper.KeyValue],
                                                                               blockCacheMemorySweeper: Option[MemorySweeper.Block],
                                                                               segmentIO: SegmentIO): SkipListTreeMap[SliceOption[Byte], SegmentRefOption, Slice[Byte], SegmentRef] = {
    val blockedReader: BlockRefReader[SegmentBlock.Offset] = fileBlockRef.copy()
    val listSegmentSize = blockedReader.readUnsignedInt()
    val listSegment = blockedReader.read(listSegmentSize)
    val listSegmentRef = BlockRefReader[SegmentBlock.Offset](listSegment)

    val segmentRef =
      SegmentRef(
        path = file.path,
        minKey = minKey,
        maxKey = maxKey,
        nearestPutDeadline = None,
        minMaxFunctionId = None,
        blockRef = listSegmentRef,
        segmentIO = segmentIO,
        valuesReaderCacheable = None,
        sortedIndexReaderCacheable = None,
        hashIndexReaderCacheable = None,
        binarySearchIndexReaderCacheable = None,
        bloomFilterReaderCacheable = None,
        footerCacheable = None
      )

    val skipList = SkipListTreeMap[SliceOption[Byte], SegmentRefOption, Slice[Byte], SegmentRef](Slice.Null, SegmentRef.Null)


    //this will also clear all the SegmentRef's
    //            blockCacheMemorySweeper foreach {
    //              cacheMemorySweeper =>
    //                cacheMemorySweeper.add(listSegmentSize, self)
    //            }

    val tailSegmentBytesFromOffset = blockedReader.getPosition
    val tailManySegmentsSize = fileBlockRef.size.toInt - tailSegmentBytesFromOffset

    var previousPath: Path = null
    var previousSegmentRef: SegmentRef = null

    segmentRef.iterator() foreach {
      keyValue =>
        val thisSegmentBlockRef =
          BlockRefReader[SegmentBlock.Offset](
            ref = fileBlockRef.copy(),
            start = tailSegmentBytesFromOffset,
            size = tailManySegmentsSize
          )

        val nextSegmentRef =
          keyValue match {
            case range: Persistent.Range =>
              TransientSegmentSerialiser.toSegmentRef(
                path = file.path,
                reader = thisSegmentBlockRef,
                range = range,
                valuesReaderCacheable = None,
                sortedIndexReaderCacheable = None,
                hashIndexReaderCacheable = None,
                binarySearchIndexReaderCacheable = None,
                bloomFilterReaderCacheable = None,
                footerCacheable = None
              )

            case put: Persistent.Put =>
              TransientSegmentSerialiser.toSegmentRef(
                path = file.path,
                reader = thisSegmentBlockRef,
                put = put,
                valuesReaderCacheable = None,
                sortedIndexReaderCacheable = None,
                hashIndexReaderCacheable = None,
                binarySearchIndexReaderCacheable = None,
                bloomFilterReaderCacheable = None,
                footerCacheable = None
              )

            case _: Persistent.Fixed =>
              throw new Exception("Non put key-value written to List segment")
          }

        val segmentRef =
          if (previousPath == nextSegmentRef.path)
            previousSegmentRef
          else
            nextSegmentRef

        previousPath = segmentRef.path
        previousSegmentRef = segmentRef

        skipList.put(segmentRef.minKey, segmentRef)
    }

    skipList
  }

  private def initListSegment(file: DBFile,
                              minKey: Slice[Byte],
                              maxKey: MaxKey[Slice[Byte]],
                              fileBlockRef: BlockRefReader[SegmentBlock.Offset])(implicit keyOrder: KeyOrder[Slice[Byte]],
                                                                                 timeOrder: TimeOrder[Slice[Byte]],
                                                                                 functionStore: FunctionStore,
                                                                                 blockCache: Option[BlockCache.State],
                                                                                 keyValueMemorySweeper: Option[MemorySweeper.KeyValue],
                                                                                 blockCacheMemorySweeper: Option[MemorySweeper.Block],
                                                                                 segmentIO: SegmentIO): SegmentRef = {
    val blockedReader: BlockRefReader[SegmentBlock.Offset] = fileBlockRef.copy()
    val listSegmentSize = blockedReader.readUnsignedInt()
    val listSegment = blockedReader.read(listSegmentSize)
    val listSegmentRef = BlockRefReader[SegmentBlock.Offset](listSegment)

    SegmentRef(
      path = file.path,
      minKey = minKey,
      maxKey = maxKey,
      //ListSegment does not store deadline. This is stored at the higher Level.
      minMaxFunctionId = None,
      nearestPutDeadline = None,
      blockRef = listSegmentRef,
      segmentIO = segmentIO,
      valuesReaderCacheable = None,
      sortedIndexReaderCacheable = None,
      hashIndexReaderCacheable = None,
      binarySearchIndexReaderCacheable = None,
      bloomFilterReaderCacheable = None,
      footerCacheable = None
    )
  }

}

protected case class PersistentSegmentMany(file: DBFile,
                                           createdInLevel: Int,
                                           minKey: Slice[Byte],
                                           maxKey: MaxKey[Slice[Byte]],
                                           minMaxFunctionId: Option[MinMax[Slice[Byte]]],
                                           segmentSize: Int,
                                           nearestPutDeadline: Option[Deadline],
                                           listSegmentCache: CacheNoIO[Unit, SegmentRef],
                                           private[segment] val segmentsCache: ConcurrentHashMap[Int, SegmentRef])(implicit keyOrder: KeyOrder[Slice[Byte]],
                                                                                                                   timeOrder: TimeOrder[Slice[Byte]],
                                                                                                                   functionStore: FunctionStore,
                                                                                                                   blockCache: Option[BlockCache.State],
                                                                                                                   fileSweeper: FileSweeper,
                                                                                                                   bufferCleaner: ByteBufferSweeperActor,
                                                                                                                   keyValueMemorySweeper: Option[MemorySweeper.KeyValue],
                                                                                                                   blockMemorySweeper: Option[MemorySweeper.Block],
                                                                                                                   segmentIO: SegmentIO,
                                                                                                                   forceSaveApplier: ForceSaveApplier) extends PersistentSegment with LazyLogging {

  override def formatId: Byte = PersistentSegmentMany.formatId

  private def fetchSegmentRef(persistent: Persistent): SegmentRef = {
    val offset = TransientSegmentSerialiser.offset(persistent)
    implicit val segment: SegmentRef = segmentsCache.get(offset)

    if (segment == null) {
      val listSegment = listSegmentCache.value(())
      val firstSegmentStartOffset: Int = 1 + Bytes.sizeOfUnsignedInt(listSegment.segmentSize) + listSegment.segmentSize

      //initialise segment
      val thisSegmentBlockRef =
        BlockRefReader(
          file = file,
          start = firstSegmentStartOffset,
          fileSize = this.segmentSize - firstSegmentStartOffset
        )

      val segment =
        TransientSegmentSerialiser.toSegmentRef(
          path = file.path,
          reader = thisSegmentBlockRef,
          persistent = persistent,
          valuesReaderCacheable = None,
          sortedIndexReaderCacheable = None,
          hashIndexReaderCacheable = None,
          binarySearchIndexReaderCacheable = None,
          bloomFilterReaderCacheable = None,
          footerCacheable = None
        )

      val existingSegment = segmentsCache.putIfAbsent(offset, segment)

      if (existingSegment == null) {
        //TODO - cache management
        //keyValueMemorySweeper.foreach(_.add())
        segment
      } else {
        existingSegment
      }
    } else {
      segment
    }
  }

  @inline def getAllSegmentRefs(): Iterator[SegmentRef] =
    new Iterator[SegmentRef] {
      var nextRef: SegmentRef = null
      val iter = listSegmentCache.value(()).iterator()

      @tailrec
      final override def hasNext: Boolean =
        if (iter.hasNext)
          if (nextRef == null) {
            nextRef = fetchSegmentRef(iter.next())
            true
          } else {
            val nextNextRef = fetchSegmentRef(iter.next())
            if (nextRef == nextNextRef)
              hasNext
            else {
              nextRef = nextNextRef
              true
            }
          }
        else
          false

      override def next(): SegmentRef =
        nextRef
    }


  def path = file.path

  override def close: Unit = {
    file.close()
    segmentsCache.forEach((_, ref) => ref.clearAllCaches())
    segmentsCache.clear()
    listSegmentCache.clear()
  }

  def isOpen: Boolean =
    file.isOpen

  def isFileDefined =
    file.isFileDefined

  def delete(delay: FiniteDuration) = {
    val deadline = delay.fromNow
    if (deadline.isOverdue())
      this.delete
    else
      fileSweeper send FileSweeper.Command.Delete(this, deadline)
  }

  def delete: Unit = {
    logger.trace(s"{}: DELETING FILE", path)
    IO(file.delete()) onLeftSideEffect {
      failure =>
        logger.error(s"{}: Failed to delete Segment file.", path, failure.value.exception)
    } map {
      _ =>
        segmentsCache.forEach {
          (_, ref) =>
            ref.clearAllCaches()
        }
    }
  }

  def copyTo(toPath: Path): Path =
    file copyTo toPath

  /**
   * Default targetPath is set to this [[PersistentSegmentOne]]'s parent directory.
   */
  def put(headGap: Iterable[Assignable],
          tailGap: Iterable[Assignable],
          mergeableCount: Int,
          mergeable: Iterator[Assignable],
          removeDeletes: Boolean,
          createdInLevel: Int,
          segmentParallelism: SegmentParallelism,
          valuesConfig: ValuesBlock.Config,
          sortedIndexConfig: SortedIndexBlock.Config,
          binarySearchIndexConfig: BinarySearchIndexBlock.Config,
          hashIndexConfig: HashIndexBlock.Config,
          bloomFilterConfig: BloomFilterBlock.Config,
          segmentConfig: SegmentBlock.Config,
          pathsDistributor: PathsDistributor = PathsDistributor(Seq(Dir(path.getParent, 1)), () => Seq()))(implicit idGenerator: IDGenerator,
                                                                                                           executionContext: ExecutionContext): SegmentPutResult[Slice[PersistentSegment]] =
    if (removeDeletes)
      Segment.mergePut(
        oldKeyValuesCount = getKeyValueCount(),
        oldKeyValues = iterator(),
        headGap = headGap,
        tailGap = tailGap,
        mergeableCount = mergeableCount,
        mergeable = mergeable,
        removeDeletes = removeDeletes,
        createdInLevel = createdInLevel,
        valuesConfig = valuesConfig,
        sortedIndexConfig = sortedIndexConfig,
        binarySearchIndexConfig = binarySearchIndexConfig,
        hashIndexConfig = hashIndexConfig,
        bloomFilterConfig = bloomFilterConfig,
        segmentConfig = segmentConfig,
        pathsDistributor = pathsDistributor
      )
    else
      SegmentRef.fastAssignPut(
        headGap = headGap,
        tailGap = tailGap,
        segmentRefs = getAllSegmentRefs(),
        assignableCount = mergeableCount,
        assignables = mergeable,
        removeDeletes = removeDeletes,
        createdInLevel = createdInLevel,
        segmentParallelism = segmentParallelism,
        valuesConfig = valuesConfig,
        sortedIndexConfig = sortedIndexConfig,
        binarySearchIndexConfig = binarySearchIndexConfig,
        hashIndexConfig = hashIndexConfig,
        bloomFilterConfig = bloomFilterConfig,
        segmentConfig = segmentConfig,
        pathsDistributor = pathsDistributor
      )

  def refresh(removeDeletes: Boolean,
              createdInLevel: Int,
              valuesConfig: ValuesBlock.Config,
              sortedIndexConfig: SortedIndexBlock.Config,
              binarySearchIndexConfig: BinarySearchIndexBlock.Config,
              hashIndexConfig: HashIndexBlock.Config,
              bloomFilterConfig: BloomFilterBlock.Config,
              segmentConfig: SegmentBlock.Config,
              pathsDistributor: PathsDistributor = PathsDistributor(Seq(Dir(path.getParent, 1)), () => Seq()))(implicit idGenerator: IDGenerator): Slice[PersistentSegment] =
    Segment.refreshForNewLevelPut(
      removeDeletes = removeDeletes,
      createdInLevel = createdInLevel,
      keyValues = iterator(),
      valuesConfig = valuesConfig,
      sortedIndexConfig = sortedIndexConfig,
      binarySearchIndexConfig = binarySearchIndexConfig,
      hashIndexConfig = hashIndexConfig,
      bloomFilterConfig = bloomFilterConfig,
      segmentConfig = segmentConfig,
      pathsDistributor = pathsDistributor
    )

  def getFromCache(key: Slice[Byte]): PersistentOption = {
    segmentsCache.forEach {
      (_, ref) =>
        val got = ref.getFromCache(key)
        if (got.isSomeS)
          return got
    }

    Persistent.Null
  }

  def mightContainKey(key: Slice[Byte], threadState: ThreadReadState): Boolean = {
    val listSegment = listSegmentCache.value(())

    listSegment.get(key, threadState) match {
      case _: Persistent =>
        true

      case Persistent.Null =>
        listSegment.lower(key, threadState) match {
          case persistent: Persistent =>
            fetchSegmentRef(persistent).mightContainKey(key, threadState)

          case Persistent.Null =>
            false
        }
    }
  }

  override def mightContainFunction(key: Slice[Byte]): Boolean =
    minMaxFunctionId exists {
      minMaxFunctionId =>
        MinMax.contains(
          key = key,
          minMax = minMaxFunctionId
        )(FunctionStore.order)
    }

  def get(key: Slice[Byte], threadState: ThreadReadState): PersistentOption = {
    val listSegment = listSegmentCache.value(())

    listSegment.get(key, threadState) match {
      case persistent: Persistent =>
        fetchSegmentRef(persistent).get(key, threadState)

      case Persistent.Null =>
        listSegment.lower(key, threadState) match {
          case persistent: Persistent =>
            fetchSegmentRef(persistent).get(key, threadState)

          case Persistent.Null =>
            Persistent.Null
        }
    }
  }

  def lower(key: Slice[Byte], threadState: ThreadReadState): PersistentOption = {
    val listSegment = listSegmentCache.value(())

    listSegment.lower(key, threadState) match {
      case persistent: Persistent =>
        fetchSegmentRef(persistent).lower(key, threadState)

      case Persistent.Null =>
        Persistent.Null
    }
  }

  private def higherFromHigherSegment(key: Slice[Byte],
                                      floorSegment: SegmentRefOption,
                                      threadState: ThreadReadState): PersistentOption = {
    val listSegment = listSegmentCache.value(())

    listSegment.higher(key, threadState) match {
      case segmentKeyValue: Persistent =>
        val higherSegment = fetchSegmentRef(segmentKeyValue)

        if (floorSegment containsC higherSegment)
          Persistent.Null
        else
          higherSegment.higher(key, threadState)

      case Persistent.Null =>
        Persistent.Null
    }
  }

  def higher(key: Slice[Byte], threadState: ThreadReadState): PersistentOption = {
    val listSegment = listSegmentCache.value(())

    val floorSegment: SegmentRefOption =
      listSegment.get(key = key, threadState = threadState) match {
        case persistent: Persistent =>
          fetchSegmentRef(persistent)

        case Persistent.Null =>
          listSegment.lower(key = key, threadState = threadState) match {
            case persistent: Persistent =>
              fetchSegmentRef(persistent)

            case Persistent.Null =>
              SegmentRef.Null
          }
      }

    floorSegment
      .flatMapSomeC(Persistent.Null: PersistentOption)(_.higher(key, threadState))
      .orElseS {
        higherFromHigherSegment(
          key = key,
          floorSegment = floorSegment,
          threadState = threadState
        )
      }
  }

  override def iterator(): Iterator[Persistent] =
    getAllSegmentRefs().flatMap(_.iterator())

  override def hasRange: Boolean =
    getAllSegmentRefs().exists(_.hasRange)

  override def hasPut: Boolean =
    getAllSegmentRefs().exists(_.hasPut)

  def getKeyValueCount(): Int =
    getAllSegmentRefs().foldLeft(0)(_ + _.getKeyValueCount())

  override def isFooterDefined: Boolean =
    segmentsCache.asScala.values.exists(_.isFooterDefined)

  def existsOnDisk: Boolean =
    file.existsOnDisk

  def memory: Boolean =
    false

  def persistent: Boolean =
    true

  def notExistsOnDisk: Boolean =
    !file.existsOnDisk

  def hasBloomFilter: Boolean =
    getAllSegmentRefs().exists(_.hasBloomFilter)

  def clearCachedKeyValues(): Unit =
    segmentsCache
      .asScala
      .values
      .foreach(_.clearCachedKeyValues())

  def clearAllCaches(): Unit = {
    clearCachedKeyValues()
    segmentsCache.asScala.values.foreach(_.clearAllCaches())
    segmentsCache.clear()
    listSegmentCache.clear()
  }

  def isInKeyValueCache(key: Slice[Byte]): Boolean = {
    segmentsCache.forEach {
      (_, ref) =>
        if (ref.isInKeyValueCache(key))
          return true
    }

    false
  }

  def isKeyValueCacheEmpty: Boolean =
    segmentsCache
      .asScala
      .values
      .forall(_.isKeyValueCacheEmpty)

  def areAllCachesEmpty: Boolean =
    segmentsCache.isEmpty && !listSegmentCache.isCached

  def cachedKeyValueSize: Int =
    segmentsCache
      .asScala
      .values
      .foldLeft(0)(_ + _.cachedKeyValueSize)
}
