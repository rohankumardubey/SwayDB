/*
 * Copyright (c) 2018 Simer JS Plaha (simer.j@gmail.com - @simerplaha)
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

package swaydb.java.persistent

import swaydb.configs.level.DefaultExecutionContext
import swaydb.data.accelerate.{Accelerator, LevelZeroMeter}
import swaydb.data.compaction.{CompactionConfig, LevelMeter, Throttle}
import swaydb.data.config._
import swaydb.data.util.Java.JavaFunction
import swaydb.data.util.StorageUnits._
import swaydb.data.{Atomic, OptimiseWrites}
import swaydb.java.serializers.{SerializerConverter, Serializer => JavaSerializer}
import swaydb.persistent.DefaultConfigs
import swaydb.serializers.Serializer
import swaydb.{Bag, CommonConfigs, Glass}

import java.nio.file.Path
import java.util.Collections
import scala.compat.java8.FunctionConverters._
import scala.concurrent.duration.FiniteDuration
import scala.jdk.CollectionConverters._

object PersistentQueue {

  final class Config[A](dir: Path,
                        private var mapSize: Int = DefaultConfigs.mapSize,
                        private var mmapMaps: MMAP.Map = DefaultConfigs.mmap(),
                        private var recoveryMode: RecoveryMode = RecoveryMode.ReportFailure,
                        private var mmapAppendix: MMAP.Map = DefaultConfigs.mmap(),
                        private var appendixFlushCheckpointSize: Int = 2.mb,
                        private var otherDirs: java.util.Collection[Dir] = Collections.emptyList(),
                        private var cacheKeyValueIds: Boolean = true,
                        private var compactionConfig: Option[CompactionConfig] = None,
                        private var threadStateCache: ThreadStateCache = ThreadStateCache.Limit(hashMapMaxSize = 100, maxProbe = 10),
                        private var sortedKeyIndex: SortedKeyIndex = DefaultConfigs.sortedKeyIndex(),
                        private var randomSearchIndex: RandomSearchIndex = DefaultConfigs.randomSearchIndex(),
                        private var binarySearchIndex: BinarySearchIndex = DefaultConfigs.binarySearchIndex(),
                        private var mightContainIndex: MightContainIndex = DefaultConfigs.mightContainIndex(),
                        private var optimiseWrites: OptimiseWrites = CommonConfigs.optimiseWrites(),
                        private var atomic: Atomic = CommonConfigs.atomic(),
                        private var valuesConfig: ValuesConfig = DefaultConfigs.valuesConfig(),
                        private var segmentConfig: SegmentConfig = DefaultConfigs.segmentConfig(),
                        private var fileCache: FileCache.On = DefaultConfigs.fileCache(DefaultExecutionContext.sweeperEC),
                        private var memoryCache: MemoryCache = DefaultConfigs.memoryCache(DefaultExecutionContext.sweeperEC),
                        private var levelZeroThrottle: JavaFunction[LevelZeroMeter, FiniteDuration] = (DefaultConfigs.levelZeroThrottle _).asJava,
                        private var levelOneThrottle: JavaFunction[LevelMeter, Throttle] = (DefaultConfigs.levelOneThrottle _).asJava,
                        private var levelTwoThrottle: JavaFunction[LevelMeter, Throttle] = (DefaultConfigs.levelTwoThrottle _).asJava,
                        private var levelThreeThrottle: JavaFunction[LevelMeter, Throttle] = (DefaultConfigs.levelThreeThrottle _).asJava,
                        private var levelFourThrottle: JavaFunction[LevelMeter, Throttle] = (DefaultConfigs.levelFourThrottle _).asJava,
                        private var levelFiveThrottle: JavaFunction[LevelMeter, Throttle] = (DefaultConfigs.levelFiveThrottle _).asJava,
                        private var levelSixThrottle: JavaFunction[LevelMeter, Throttle] = (DefaultConfigs.levelSixThrottle _).asJava,
                        private var acceleration: JavaFunction[LevelZeroMeter, Accelerator] = DefaultConfigs.accelerator.asJava,
                        serializer: Serializer[A]) {

    def setMapSize(mapSize: Int) = {
      this.mapSize = mapSize
      this
    }

    def setCompactionConfig(config: CompactionConfig) = {
      this.compactionConfig = Some(config)
      this
    }

    def setMmapMaps(mmapMaps: MMAP.Map) = {
      this.mmapMaps = mmapMaps
      this
    }

    def setOptimiseWrites(optimiseWrites: OptimiseWrites) = {
      this.optimiseWrites = optimiseWrites
      this
    }

    def setAtomic(atomic: Atomic) = {
      this.atomic = atomic
      this
    }

    def setRecoveryMode(recoveryMode: RecoveryMode) = {
      this.recoveryMode = recoveryMode
      this
    }

    def setMmapAppendix(mmapAppendix: MMAP.Map) = {
      this.mmapAppendix = mmapAppendix
      this
    }

    def setAppendixFlushCheckpointSize(appendixFlushCheckpointSize: Int) = {
      this.appendixFlushCheckpointSize = appendixFlushCheckpointSize
      this
    }

    def setOtherDirs(otherDirs: java.util.Collection[Dir]) = {
      this.otherDirs = otherDirs
      this
    }

    def setCacheKeyValueIds(cacheKeyValueIds: Boolean) = {
      this.cacheKeyValueIds = cacheKeyValueIds
      this
    }

    def setThreadStateCache(threadStateCache: ThreadStateCache) = {
      this.threadStateCache = threadStateCache
      this
    }

    def setSortedKeyIndex(sortedKeyIndex: SortedKeyIndex) = {
      this.sortedKeyIndex = sortedKeyIndex
      this
    }

    def setRandomSearchIndex(randomSearchIndex: RandomSearchIndex) = {
      this.randomSearchIndex = randomSearchIndex
      this
    }

    def setBinarySearchIndex(binarySearchIndex: BinarySearchIndex) = {
      this.binarySearchIndex = binarySearchIndex
      this
    }

    def setMightContainIndex(mightContainIndex: MightContainIndex) = {
      this.mightContainIndex = mightContainIndex
      this
    }

    def setValuesConfig(valuesConfig: ValuesConfig) = {
      this.valuesConfig = valuesConfig
      this
    }

    def setSegmentConfig(segmentConfig: SegmentConfig) = {
      this.segmentConfig = segmentConfig
      this
    }

    def setFileCache(fileCache: FileCache.On) = {
      this.fileCache = fileCache
      this
    }

    def setMemoryCache(memoryCache: MemoryCache) = {
      this.memoryCache = memoryCache
      this
    }

    def setLevelZeroThrottle(levelZeroThrottle: JavaFunction[LevelZeroMeter, FiniteDuration]) = {
      this.levelZeroThrottle = levelZeroThrottle
      this
    }

    def setLevelOneThrottle(levelOneThrottle: JavaFunction[LevelMeter, Throttle]) = {
      this.levelOneThrottle = levelOneThrottle
      this
    }

    def setLevelTwoThrottle(levelTwoThrottle: JavaFunction[LevelMeter, Throttle]) = {
      this.levelTwoThrottle = levelTwoThrottle
      this
    }

    def setLevelThreeThrottle(levelThreeThrottle: JavaFunction[LevelMeter, Throttle]) = {
      this.levelThreeThrottle = levelThreeThrottle
      this
    }

    def setLevelFourThrottle(levelFourThrottle: JavaFunction[LevelMeter, Throttle]) = {
      this.levelFourThrottle = levelFourThrottle
      this
    }

    def setLevelFiveThrottle(levelFiveThrottle: JavaFunction[LevelMeter, Throttle]) = {
      this.levelFiveThrottle = levelFiveThrottle
      this
    }

    def setLevelSixThrottle(levelSixThrottle: JavaFunction[LevelMeter, Throttle]) = {
      this.levelSixThrottle = levelSixThrottle
      this
    }

    def setAcceleration(acceleration: JavaFunction[LevelZeroMeter, Accelerator]) = {
      this.acceleration = acceleration
      this
    }

    def get(): swaydb.java.Queue[A] = {
      val scalaQueue =
        swaydb.persistent.Queue[A, Glass](
          dir = dir,
          mapSize = mapSize,
          mmapMaps = mmapMaps,
          recoveryMode = recoveryMode,
          mmapAppendix = mmapAppendix,
          appendixFlushCheckpointSize = appendixFlushCheckpointSize,
          otherDirs = otherDirs.asScala.toSeq,
          cacheKeyValueIds = cacheKeyValueIds,
          compactionConfig = compactionConfig getOrElse CommonConfigs.compactionConfig(),
          acceleration = acceleration.asScala,
          threadStateCache = threadStateCache,
          optimiseWrites = optimiseWrites,
          atomic = atomic,
          sortedKeyIndex = sortedKeyIndex,
          randomSearchIndex = randomSearchIndex,
          binarySearchIndex = binarySearchIndex,
          mightContainIndex = mightContainIndex,
          valuesConfig = valuesConfig,
          segmentConfig = segmentConfig,
          fileCache = fileCache,
          memoryCache = memoryCache,
          levelZeroThrottle = levelZeroThrottle.asScala,
          levelOneThrottle = levelOneThrottle.asScala,
          levelTwoThrottle = levelTwoThrottle.asScala,
          levelThreeThrottle = levelThreeThrottle.asScala,
          levelFourThrottle = levelFourThrottle.asScala,
          levelFiveThrottle = levelFiveThrottle.asScala,
          levelSixThrottle = levelSixThrottle.asScala
        )(serializer = serializer, bag = Bag.glass)

      swaydb.java.Queue[A](scalaQueue)
    }
  }

  def config[A](dir: Path,
                serializer: JavaSerializer[A]): Config[A] =
    new Config(
      dir = dir,
      serializer = SerializerConverter.toScala(serializer)
    )
}
