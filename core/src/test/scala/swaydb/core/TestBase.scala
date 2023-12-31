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

package swaydb.core

import org.scalatest.concurrent.Eventually
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import org.scalatest.{BeforeAndAfterAll, BeforeAndAfterEach}
import swaydb.IOValues._
import swaydb.config.accelerate.{Accelerator, LevelZeroMeter}
import swaydb.config.compaction.{CompactionConfig, LevelMeter, LevelThrottle, LevelZeroThrottle}
import swaydb.config.storage.{Level0Storage, LevelStorage}
import swaydb.config.{Atomic, MMAP, OptimiseWrites, RecoveryMode}
import swaydb.core.CommonAssertions._
import swaydb.core.TestCaseSweeper._
import swaydb.core.TestData._
import swaydb.core.compaction._
import swaydb.core.compaction.throttle.ThrottleCompactorCreator
import swaydb.core.file.DBFile
import swaydb.core.file.reader.FileReader
import swaydb.core.level.zero.LevelZero.LevelZeroLog
import swaydb.core.level.zero.{LevelZero, LevelZeroLogCache}
import swaydb.core.level.{Level, LevelRef, NextLevel}
import swaydb.core.log.{Log, LogEntry}
import swaydb.core.segment.block.binarysearch.BinarySearchIndexBlockConfig
import swaydb.core.segment.block.bloomfilter.BloomFilterBlockConfig
import swaydb.core.segment.block.hashindex.HashIndexBlockConfig
import swaydb.core.segment.block.segment.SegmentBlockConfig
import swaydb.core.segment.block.sortedindex.SortedIndexBlockConfig
import swaydb.core.segment.block.values.ValuesBlockConfig
import swaydb.core.segment.data.merge.stats.MergeStats
import swaydb.core.segment.data.{Memory, Time}
import swaydb.core.segment.io.{SegmentCompactionIO, SegmentReadIO}
import swaydb.core.segment.{PathsDistributor, PersistentSegment, Segment}
import swaydb.effect.{Dir, Effect}
import swaydb.slice.Slice
import swaydb.slice.order.{KeyOrder, TimeOrder}
import swaydb.testkit.RunThis.FutureImplicits
import swaydb.testkit.TestKit._
import swaydb.utils.StorageUnits._
import swaydb.utils.{IDGenerator, OperatingSystem}
import swaydb.{DefActor, Glass, IO}

import java.nio.file._
import java.util.concurrent.atomic.AtomicInteger
import scala.concurrent.ExecutionContext
import scala.concurrent.duration._
import scala.util.Random

trait TestBase extends AnyWordSpec with Matchers with BeforeAndAfterAll with BeforeAndAfterEach with Eventually {

  implicit val idGenerator = IDGenerator()

  private val currentLevelId = new AtomicInteger(100000000)

  private def nextLevelId: Int =
    currentLevelId.decrementAndGet()

  private val projectTargetFolder = getClass.getClassLoader.getResource("").getPath

  val projectDirectory =
    if (OperatingSystem.isWindows)
      Paths.get(projectTargetFolder.drop(1)).getParent.getParent
    else
      Paths.get(projectTargetFolder).getParent.getParent

  val testFileDirectory = projectDirectory.resolve("TEST_FILES")

  val testMemoryFileDirectory = projectDirectory.resolve("TEST_MEMORY_FILES")

  val testClassDirPath = testFileDirectory.resolve(this.getClass.getSimpleName)

  def levelFoldersCount = 0

  def mmapSegments: MMAP.Segment = MMAP.On(OperatingSystem.isWindows, TestForceSave.mmap())

  def level0MMAP: MMAP.Log = MMAP.On(OperatingSystem.isWindows, TestForceSave.mmap())

  def appendixStorageMMAP: MMAP.Log = MMAP.On(OperatingSystem.isWindows, TestForceSave.mmap())

  def isWindowsAndMMAPSegments(): Boolean =
    OperatingSystem.isWindows && mmapSegments.mmapReads && mmapSegments.mmapWrites

  def inMemoryStorage = false

  def nextTime(implicit testTimer: TestTimer): Time =
    testTimer.next

  def levelStorage: LevelStorage =
    if (inMemoryStorage)
      LevelStorage.Memory(dir = memoryTestClassDir.resolve(nextLevelId.toString))
    else
      LevelStorage.Persistent(
        dir = testClassDir.resolve(nextLevelId.toString),
        otherDirs =
          (0 until levelFoldersCount) map {
            _ =>
              Dir(testClassDir.resolve(nextLevelId.toString), 1)
          },
        appendixMMAP = appendixStorageMMAP,
        appendixFlushCheckpointSize = 4.mb
      )

  def level0Storage: Level0Storage =
    if (inMemoryStorage)
      Level0Storage.Memory
    else
      Level0Storage.Persistent(mmap = level0MMAP, randomIntDirectory, RecoveryMode.ReportFailure)

  def persistent = levelStorage.persistent

  def memory = levelStorage.memory

  def randomDir(implicit sweeper: TestCaseSweeper) = testClassDir.resolve(s"${randomCharacters()}").sweep()

  def createRandomDir(implicit sweeper: TestCaseSweeper) = Effect.createDirectory(randomDir).sweep()

  def randomFilePath(implicit sweeper: TestCaseSweeper) =
    testClassDir.resolve(s"${randomCharacters()}.test").sweep()

  def nextSegmentId = idGenerator.nextSegment

  def nextId = idGenerator.next

  def deleteFiles = true

  def randomIntDirectory: Path =
    testClassDir.resolve(nextLevelId.toString)

  def createRandomIntDirectory(implicit sweeper: TestCaseSweeper): Path =
    if (persistent)
      Effect.createDirectoriesIfAbsent(randomIntDirectory).sweep()
    else
      randomIntDirectory

  def createNextLevelPath: Path =
    Effect.createDirectoriesIfAbsent(nextLevelPath)

  def createPathDistributor(implicit sweeper: TestCaseSweeper) =
    PathsDistributor(Seq(Dir(createNextLevelPath.sweep(), 1)), () => Seq.empty)

  def nextLevelPath: Path =
    testClassDir.resolve(nextLevelId.toString)

  def testSegmentFile: Path =
    if (memory)
      randomIntDirectory.resolve(nextSegmentId)
    else
      Effect.createDirectoriesIfAbsent(randomIntDirectory).resolve(nextSegmentId)

  def testLogFile: Path =
    if (memory)
      randomIntDirectory.resolve(nextId.toString + ".map")
    else
      Effect.createDirectoriesIfAbsent(randomIntDirectory).resolve(nextId.toString + ".map")

  def farOut = new Exception("Far out!")

  def testClassDir: Path =
    if (inMemoryStorage)
      testClassDirPath
    else
      Effect.createDirectoriesIfAbsent(testClassDirPath)

  def memoryTestClassDir =
    testFileDirectory.resolve(this.getClass.getSimpleName + "_MEMORY_DIR")

  override protected def beforeAll(): Unit =
    if (deleteFiles)
      Effect.walkDelete(testClassDirPath)

  override protected def afterEach(): Unit =
    Effect.deleteIfExists(testClassDirPath)

  object TestLog {
    def apply(keyValues: Slice[Memory],
              fileSize: Int = 4.mb,
              path: Path = testLogFile,
              flushOnOverflow: Boolean = false,
              mmap: MMAP.Log = MMAP.On(OperatingSystem.isWindows, TestForceSave.mmap()))(implicit keyOrder: KeyOrder[Slice[Byte]] = KeyOrder.default,
                                                                                         timeOrder: TimeOrder[Slice[Byte]] = TimeOrder.long,
                                                                                         sweeper: TestCaseSweeper): LevelZeroLog = {
      import swaydb.core.log.serialiser.LevelZeroLogEntryWriter._
      import sweeper._

      implicit val optimiseWrites = OptimiseWrites.random
      implicit val atomic = Atomic.random

      val testLog =
        if (levelStorage.memory)
          Log.memory[Slice[Byte], Memory, LevelZeroLogCache](
            fileSize = fileSize,
            flushOnOverflow = flushOnOverflow
          )
        else
          Log.persistent[Slice[Byte], Memory, LevelZeroLogCache](
            folder = path,
            mmap = mmap,
            flushOnOverflow = flushOnOverflow,
            fileSize = fileSize
          ).runRandomIO.right.value

      keyValues foreach {
        keyValue =>
          testLog.writeSync(LogEntry.Put(keyValue.key, keyValue))
      }

      testLog.sweep()
    }
  }

  object TestSegment {
    def apply(keyValues: Slice[Memory] = randomizedKeyValues()(TestTimer.Incremental()),
              createdInLevel: Int = 1,
              path: Path = testSegmentFile,
              valuesConfig: ValuesBlockConfig = ValuesBlockConfig.random,
              sortedIndexConfig: SortedIndexBlockConfig = SortedIndexBlockConfig.random,
              binarySearchIndexConfig: BinarySearchIndexBlockConfig = BinarySearchIndexBlockConfig.random,
              hashIndexConfig: HashIndexBlockConfig = HashIndexBlockConfig.random,
              bloomFilterConfig: BloomFilterBlockConfig = BloomFilterBlockConfig.random,
              segmentConfig: SegmentBlockConfig = SegmentBlockConfig.random.copy(mmap = mmapSegments))(implicit keyOrder: KeyOrder[Slice[Byte]] = KeyOrder.default,
                                                                                                       timeOrder: TimeOrder[Slice[Byte]] = TimeOrder.long,
                                                                                                       sweeper: TestCaseSweeper,
                                                                                                       ec: ExecutionContext = TestExecutionContext.executionContext): Segment = {

      val segmentNumber = Effect.numberFileId(path)._1 - 1

      implicit val idGenerator: IDGenerator = IDGenerator(segmentNumber)

      implicit val pathsDistributor = PathsDistributor(Seq(Dir(path.getParent, 1)), () => Seq.empty)

      val segments =
        many(
          createdInLevel = createdInLevel,
          keyValues = keyValues,
          valuesConfig = valuesConfig,
          sortedIndexConfig = sortedIndexConfig,
          binarySearchIndexConfig = binarySearchIndexConfig,
          hashIndexConfig = hashIndexConfig,
          bloomFilterConfig = bloomFilterConfig,
          segmentConfig =
            if (persistent)
              segmentConfig.copy(minSize = Int.MaxValue, maxCount = eitherOne(randomIntMax(keyValues.size), Int.MaxValue))
            else
              segmentConfig.copy(minSize = Int.MaxValue, maxCount = Int.MaxValue)
        )

      segments should have size 1

      segments.head
    }

    def one(keyValues: Slice[Memory] = randomizedKeyValues()(TestTimer.Incremental()),
            createdInLevel: Int = 1,
            path: Path = testSegmentFile,
            valuesConfig: ValuesBlockConfig = ValuesBlockConfig.random,
            sortedIndexConfig: SortedIndexBlockConfig = SortedIndexBlockConfig.random,
            binarySearchIndexConfig: BinarySearchIndexBlockConfig = BinarySearchIndexBlockConfig.random,
            hashIndexConfig: HashIndexBlockConfig = HashIndexBlockConfig.random,
            bloomFilterConfig: BloomFilterBlockConfig = BloomFilterBlockConfig.random,
            segmentConfig: SegmentBlockConfig = SegmentBlockConfig.random.copy(mmap = mmapSegments))(implicit keyOrder: KeyOrder[Slice[Byte]] = KeyOrder.default,
                                                                                                     timeOrder: TimeOrder[Slice[Byte]] = TimeOrder.long,
                                                                                                     sweeper: TestCaseSweeper,
                                                                                                     ec: ExecutionContext = TestExecutionContext.executionContext): Segment = {

      val segmentNumber = Effect.numberFileId(path)._1 - 1

      implicit val idGenerator: IDGenerator = IDGenerator(segmentNumber)

      implicit val pathsDistributor: PathsDistributor = PathsDistributor(Seq(Dir(path.getParent, 1)), () => Seq.empty)

      val segments =
        many(
          createdInLevel = createdInLevel,
          keyValues = keyValues,
          valuesConfig = valuesConfig,
          sortedIndexConfig = sortedIndexConfig,
          binarySearchIndexConfig = binarySearchIndexConfig,
          hashIndexConfig = hashIndexConfig,
          bloomFilterConfig = bloomFilterConfig,
          segmentConfig = segmentConfig.copy(minSize = Int.MaxValue, maxCount = Int.MaxValue)
        )

      segments should have size 1

      segments.head
    }

    def many(createdInLevel: Int = 1,
             keyValues: Slice[Memory] = randomizedKeyValues()(TestTimer.Incremental()),
             valuesConfig: ValuesBlockConfig = ValuesBlockConfig.random,
             sortedIndexConfig: SortedIndexBlockConfig = SortedIndexBlockConfig.random,
             binarySearchIndexConfig: BinarySearchIndexBlockConfig = BinarySearchIndexBlockConfig.random,
             hashIndexConfig: HashIndexBlockConfig = HashIndexBlockConfig.random,
             bloomFilterConfig: BloomFilterBlockConfig = BloomFilterBlockConfig.random,
             segmentConfig: SegmentBlockConfig = SegmentBlockConfig.random.copy(mmap = mmapSegments))(implicit keyOrder: KeyOrder[Slice[Byte]] = KeyOrder.default,
                                                                                                      timeOrder: TimeOrder[Slice[Byte]] = TimeOrder.long,
                                                                                                      pathsDistributor: PathsDistributor,
                                                                                                      idGenerator: IDGenerator,
                                                                                                      sweeper: TestCaseSweeper,
                                                                                                      ec: ExecutionContext = TestExecutionContext.executionContext): Slice[Segment] = {
      import sweeper._

      implicit val segmentIO: SegmentReadIO =
        SegmentReadIO(
          bloomFilterConfig = bloomFilterConfig,
          hashIndexConfig = hashIndexConfig,
          binarySearchIndexConfig = binarySearchIndexConfig,
          sortedIndexConfig = sortedIndexConfig,
          valuesConfig = valuesConfig,
          segmentConfig = segmentConfig
        )

      val segment =
        if (levelStorage.memory)
          Segment.memory(
            minSegmentSize = segmentConfig.minSize,
            maxKeyValueCountPerSegment = segmentConfig.maxCount,
            pathsDistributor = pathsDistributor,
            createdInLevel = createdInLevel,
            stats = MergeStats.memoryBuilder(keyValues).close
          )
        else
          Segment.persistent(
            pathsDistributor = pathsDistributor,
            createdInLevel = createdInLevel,
            bloomFilterConfig = bloomFilterConfig,
            hashIndexConfig = hashIndexConfig,
            binarySearchIndexConfig = binarySearchIndexConfig,
            sortedIndexConfig = sortedIndexConfig,
            valuesConfig = valuesConfig,
            segmentConfig = segmentConfig,
            mergeStats =
              MergeStats
                .persistentBuilder(keyValues)
                .close(
                  hasAccessPositionIndex = sortedIndexConfig.enableAccessPositionIndex,
                  optimiseForReverseIteration = sortedIndexConfig.optimiseForReverseIteration
                )
          ).awaitInf

      segment.foreach(_.sweep())

      Slice.from(segment, segment.size)
    }
  }

  object TestLevel {

    def testDefaultThrottle(meter: LevelMeter): LevelThrottle =
      if (meter.segmentsCount > 15)
        LevelThrottle(Duration.Zero, 20)
      else if (meter.segmentsCount > 10)
        LevelThrottle(1.second, 20)
      else if (meter.segmentsCount > 5)
        LevelThrottle(2.seconds, 10)
      else
        LevelThrottle(3.seconds, 10)

    implicit def toSome[T](input: T): Option[T] =
      Some(input)

    def apply(levelStorage: LevelStorage = levelStorage,
              nextLevel: Option[NextLevel] = None,
              throttle: LevelMeter => LevelThrottle = testDefaultThrottle,
              valuesConfig: ValuesBlockConfig = ValuesBlockConfig.random,
              sortedIndexConfig: SortedIndexBlockConfig = SortedIndexBlockConfig.random,
              binarySearchIndexConfig: BinarySearchIndexBlockConfig = BinarySearchIndexBlockConfig.random,
              hashIndexConfig: HashIndexBlockConfig = HashIndexBlockConfig.random,
              bloomFilterConfig: BloomFilterBlockConfig = BloomFilterBlockConfig.random,
              segmentConfig: SegmentBlockConfig = SegmentBlockConfig.random2(deleteDelay = Duration.Zero, mmap = mmapSegments),
              keyValues: Slice[Memory] = Slice.empty)(implicit keyOrder: KeyOrder[Slice[Byte]] = KeyOrder.default,
                                                      timeOrder: TimeOrder[Slice[Byte]] = TimeOrder.long,
                                                      sweeper: TestCaseSweeper): Level = {
      import sweeper._

      val level =
        Level(
          levelStorage = levelStorage,
          nextLevel = nextLevel,
          throttle = throttle,
          valuesConfig = valuesConfig,
          sortedIndexConfig = sortedIndexConfig,
          binarySearchIndexConfig = binarySearchIndexConfig,
          hashIndexConfig = hashIndexConfig,
          bloomFilterConfig = bloomFilterConfig,
          segmentConfig = segmentConfig
        )

      level.flatMap {
        level =>
          if (keyValues.nonEmpty)
            level.put(keyValues) map {
              _ =>
                level
            }
          else
            IO[swaydb.Error.Level, Level](level)
      }.right.value.sweep()
    }
  }

  object TestLevelZero {

    def apply(nextLevel: Option[Level],
              logSize: Int = randomIntMax(10.mb),
              appliedFunctionsLogSize: Int = randomIntMax(1.mb),
              clearAppliedFunctionsOnBoot: Boolean = false,
              enableTimer: Boolean = true,
              brake: LevelZeroMeter => Accelerator = Accelerator.brake(),
              throttle: LevelZeroMeter => LevelZeroThrottle = _ => LevelZeroThrottle(Duration.Zero, 1))(implicit keyOrder: KeyOrder[Slice[Byte]] = KeyOrder.default,
                                                                                                        timeOrder: TimeOrder[Slice[Byte]] = TimeOrder.long,
                                                                                                        sweeper: TestCaseSweeper,
                                                                                                        optimiseWrites: OptimiseWrites = OptimiseWrites.random,
                                                                                                        atomimc: Atomic = Atomic.random): LevelZero = {
      import sweeper._

      LevelZero(
        logSize = logSize,
        appliedFunctionsLogSize = appliedFunctionsLogSize,
        clearAppliedFunctionsOnBoot = clearAppliedFunctionsOnBoot,
        storage = level0Storage,
        nextLevel = nextLevel,
        enableTimer = enableTimer,
        cacheKeyValueIds = randomBoolean(),
        throttle = throttle,
        acceleration = brake
      ).value.sweep()
    }
  }

  def createFile(bytes: Slice[Byte])(implicit sweeper: TestCaseSweeper): Path =
    Effect.write(testClassDir.resolve(nextSegmentId).sweep(), bytes.toByteBufferWrap)

  def createRandomFileReader(path: Path)(implicit sweeper: TestCaseSweeper): FileReader =
    if (Random.nextBoolean())
      createMMAPFileReader(path)
    else
      createStandardFileFileReader(path)

  def createAllFilesReaders(path: Path)(implicit sweeper: TestCaseSweeper): List[FileReader] =
    List(
      createMMAPFileReader(path),
      createStandardFileFileReader(path)
    )

  def createMMAPFileReader(bytes: Slice[Byte])(implicit sweeper: TestCaseSweeper): FileReader =
    createMMAPFileReader(createFile(bytes))

  /**
   * Creates all file types currently supported which are MMAP and StandardFile.
   */
  def createDBFiles(mmapPath: Path, mmapBytes: Slice[Byte], channelPath: Path, channelBytes: Slice[Byte])(implicit sweeper: TestCaseSweeper): List[DBFile] =
    List(
      createMMAPWriteAndRead(mmapPath, mmapBytes),
      createStandardWriteAndRead(channelPath, channelBytes)
    )

  def createDBFiles(mmapBytes: Slice[Byte], channelBytes: Slice[Byte])(implicit sweeper: TestCaseSweeper): List[DBFile] =
    List(
      createMMAPWriteAndRead(randomFilePath, mmapBytes),
      createStandardWriteAndRead(randomFilePath, channelBytes)
    )

  def createMMAPWriteAndRead(path: Path, bytes: Slice[Byte])(implicit sweeper: TestCaseSweeper): DBFile = {
    import sweeper._

    DBFile.mmapWriteAndRead(
      path = path,
      fileOpenIOStrategy = randomThreadSafeIOStrategy(),
      autoClose = true,
      deleteAfterClean = OperatingSystem.isWindows,
      forceSave = TestForceSave.mmap(),
      bytes = bytes
    ).sweep()
  }

  def createWriteableMMAPFile(path: Path, bufferSize: Int)(implicit sweeper: TestCaseSweeper): DBFile = {
    import sweeper._

    DBFile.mmapInit(
      path = path,
      fileOpenIOStrategy = randomThreadSafeIOStrategy(),
      autoClose = true,
      deleteAfterClean = OperatingSystem.isWindows,
      forceSave = TestForceSave.mmap(),
      bufferSize = bufferSize
    ).sweep()
  }

  def createWriteableStandardFile(path: Path)(implicit sweeper: TestCaseSweeper): DBFile = {
    import sweeper._

    DBFile.standardWrite(
      path = path,
      fileOpenIOStrategy = randomThreadSafeIOStrategy(),
      autoClose = true,
      forceSave = TestForceSave.channel()
    )
  }

  def createStandardWriteAndRead(path: Path, bytes: Slice[Byte])(implicit sweeper: TestCaseSweeper): DBFile = {
    import sweeper._

    val file =
      DBFile.standardWrite(
        path = path,
        fileOpenIOStrategy = randomThreadSafeIOStrategy(),
        autoClose = true,
        forceSave = TestForceSave.channel()
      ).sweep()

    file.append(bytes)
    file.close()

    DBFile.standardRead(
      path = path,
      fileOpenIOStrategy = randomThreadSafeIOStrategy(),
      autoClose = true
    ).sweep()
  }

  def createMMAPFileReader(path: Path)(implicit sweeper: TestCaseSweeper): FileReader = {
    import sweeper._

    val file =
      DBFile.mmapRead(
        path = path,
        fileOpenIOStrategy = randomThreadSafeIOStrategy(),
        autoClose = true,
        deleteAfterClean = OperatingSystem.isWindows
      ).sweep()

    new FileReader(file = file)
  }

  def createStandardFileFileReader(bytes: Slice[Byte])(implicit sweeper: TestCaseSweeper): FileReader =
    createStandardFileFileReader(createFile(bytes))

  def createStandardFileFileReader(path: Path)(implicit sweeper: TestCaseSweeper): FileReader = {
    import sweeper._

    val file =
      DBFile.standardRead(
        path = path,
        fileOpenIOStrategy = randomThreadSafeIOStrategy(),
        autoClose = true
      ).sweep()

    new FileReader(file = file)
  }

  def createRandomFileReader(bytes: Slice[Byte])(implicit sweeper: TestCaseSweeper): FileReader =
    createRandomFileReader(createFile(bytes))

  /**
   * Runs multiple asserts on individual levels and also one by one merges key-values from upper levels
   * to lower levels and asserts the results are still the same.
   *
   * The tests written only need to define a 3 level test case and this function will create a 4 level database
   * and run multiple passes for the test merging key-values from levels into lower levels asserting the results
   * are the same after merge.
   *
   * Note: Tests for decremental time is not required because in reality upper Level cannot have lower time key-values
   * that are not merged into lower Level already. So there will never be a situation where upper Level's keys are
   * ignored completely due to it having a lower or equal time to lower Level. If it has a lower or same time thi®s means
   * that it has already been merged into lower Levels already making the upper Level's read always valid.
   */
  def assertLevel(level0KeyValues: (Slice[Memory], Slice[Memory], TestTimer) => Slice[Memory] = (_, _, _) => Slice.empty,
                  assertLevel0: (Slice[Memory], Slice[Memory], Slice[Memory], LevelRef) => Unit = (_, _, _, _) => (),
                  level1KeyValues: (Slice[Memory], TestTimer) => Slice[Memory] = (_, _) => Slice.empty,
                  assertLevel1: (Slice[Memory], Slice[Memory], LevelRef) => Unit = (_, _, _) => (),
                  level2KeyValues: TestTimer => Slice[Memory] = _ => Slice.empty,
                  assertLevel2: (Slice[Memory], LevelRef) => Unit = (_, _) => (),
                  assertAllLevels: (Slice[Memory], Slice[Memory], Slice[Memory], LevelRef) => Unit = (_, _, _, _) => (),
                  throttleOn: Boolean = false)(implicit keyOrder: KeyOrder[Slice[Byte]] = KeyOrder.default): Unit = {

    def iterationMessage =
      s"Thread: ${Thread.currentThread().getId} - throttleOn: $throttleOn"

    implicit val compactionStrategy: CompactorCreator =
      ThrottleCompactorCreator

    println(iterationMessage)

    val noAssert =
      (_: LevelRef) => ()

    val testTimer: TestTimer = TestTimer.Incremental()

    /**
     * If [[throttleOn]] is true then enable fast throttling
     * so that this test covers as many scenarios as possible.
     */
    val levelThrottle: LevelMeter => LevelThrottle = if (throttleOn) _ => LevelThrottle(Duration.Zero, randomNextInt(3) max 1) else _ => LevelThrottle(Duration.Zero, 0)
    val levelZeroThrottle: LevelZeroMeter => LevelZeroThrottle = if (throttleOn) _ => LevelZeroThrottle(Duration.Zero, 1) else _ => LevelZeroThrottle(365.days, 0)

    println("Starting levels")

    implicit val levelSweeper: TestCaseSweeper = TestCaseSweeper()
    implicit val fileSweeper = levelSweeper.fileSweeper

    val level4 = TestLevel(throttle = levelThrottle)
    val level3 = TestLevel(nextLevel = Some(level4), throttle = levelThrottle)
    val level2 = TestLevel(nextLevel = Some(level3), throttle = levelThrottle)
    val level1 = TestLevel(nextLevel = Some(level2), throttle = levelThrottle)
    val level0 = TestLevelZero(nextLevel = Some(level1), throttle = levelZeroThrottle)

    val compaction: Option[DefActor[Compactor]] =
      if (throttleOn) {
        val compactor =
          CoreInitializer.initialiseCompaction(
            zero = level0,
            compactionConfig =
              CompactionConfig(
                resetCompactionPriorityAtInterval = randomIntMax(10).max(1),
                actorExecutionContext = TestExecutionContext.executionContext,
                compactionExecutionContext = TestExecutionContext.executionContext,
                pushStrategy = randomPushStrategy()
              ),
          ).value

        Some(compactor)
      } else {
        None
      }

    println("Levels started")

    //start with a default testTimer.
    val level2KV = level2KeyValues(testTimer)
    println("level2KV created.")

    //if upper levels should insert key-values at an older time start the testTimer to use older time
    val level1KV = level1KeyValues(level2KV, testTimer)
    println("level1KV created.")

    //if upper levels should insert key-values at an older time start the testTimer to use older time
    val level0KV = level0KeyValues(level1KV, level2KV, testTimer)
    println("level0KV created.")

    val level0Assert: LevelRef => Unit = assertLevel0(level0KV, level1KV, level2KV, _)
    val level1Assert: LevelRef => Unit = assertLevel1(level1KV, level2KV, _)
    val level2Assert: LevelRef => Unit = assertLevel2(level0KV, _)
    val levelAllAssert: LevelRef => Unit = assertAllLevels(level0KV, level1KV, level2KV, _)

    def runAsserts(asserts: Seq[((Slice[Memory], LevelRef => Unit), (Slice[Memory], LevelRef => Unit), (Slice[Memory], LevelRef => Unit), (Slice[Memory], LevelRef => Unit))]) =
      asserts.foldLeft(1) {
        case (count, ((level0KeyValues, level0Assert), (level1KeyValues, level1Assert), (level2KeyValues, level2Assert), (level3KeyValues, level3Assert))) => {
          println(s"\nRunning assert: $count/${asserts.size} - $iterationMessage")
          doAssertOnLevel(
            level0KeyValues = level0KeyValues,
            assertLevel0 = level0Assert,
            level0 = level0,
            level1KeyValues = level1KeyValues,
            assertLevel1 = level1Assert,
            level1 = level1,
            level2KeyValues = level2KeyValues,
            assertLevel2 = level2Assert,
            level2 = level2,
            level3KeyValues = level3KeyValues,
            assertLevel3 = level3Assert,
            level3 = level3,
            assertAllLevels = levelAllAssert,
            //if level3's key-values are empty - no need to run assert for this pass.
            assertLevel3ForAllLevels = level3KeyValues.nonEmpty
          )
          count + 1
        }
      }

    val asserts: Seq[((Slice[Memory], LevelRef => Unit), (Slice[Memory], LevelRef => Unit), (Slice[Memory], LevelRef => Unit), (Slice[Memory], LevelRef => Unit))] =
      if (throttleOn)
      //if throttle is only the top most Level's (Level0) assert should
      // be executed because throttle behaviour is unknown during runtime
      // and lower Level's key-values would change as compaction continues.
        (1 to 5) map (
          i =>
            if (i == 1)
              (
                (level0KV, level0Assert),
                (level1KV, noAssert),
                (level2KV, noAssert),
                (Slice.empty, noAssert)
              )
            else
              (
                (Slice.empty, level0Assert),
                (Slice.empty, noAssert),
                (Slice.empty, noAssert),
                (Slice.empty, noAssert)
              )
          )
      else
        Seq(
          (
            (level0KV, level0Assert),
            (level1KV, level1Assert),
            (level2KV, level2Assert),
            (Slice.empty, noAssert)
          ),
          (
            (level0KV, level0Assert),
            (level1KV, level1Assert),
            (Slice.empty, level2Assert),
            (level2KV, level2Assert)
          ),
          (
            (level0KV, level0Assert),
            (Slice.empty, level1Assert),
            (level1KV, level1Assert),
            (level2KV, level2Assert)
          ),
          (
            (Slice.empty, level0Assert),
            (level0KV, level0Assert),
            (level1KV, level1Assert),
            (level2KV, level2Assert)
          ),
          (
            (Slice.empty, level0Assert),
            (Slice.empty, level0Assert),
            (level0KV, level0Assert),
            (level1KV, level1Assert)
          ),
          (
            (Slice.empty, level0Assert),
            (Slice.empty, level0Assert),
            (Slice.empty, level0Assert),
            (level0KV, level0Assert)
          )
        )

    runAsserts(asserts)

    compaction.foreach(_.terminateAndClear[Glass]())

    level0.delete[Glass]()

    if (!throttleOn)
      assertLevel(
        level0KeyValues = level0KeyValues,
        assertLevel0 = assertLevel0,
        level1KeyValues = level1KeyValues,
        assertLevel1 = assertLevel1,
        level2KeyValues = level2KeyValues,
        assertLevel2 = assertLevel2,
        assertAllLevels = assertAllLevels,
        throttleOn = true
      )
  }

  private def doAssertOnLevel(level0KeyValues: Slice[Memory],
                              assertLevel0: LevelRef => Unit,
                              level0: LevelZero,
                              level1KeyValues: Slice[Memory],
                              assertLevel1: LevelRef => Unit,
                              level1: Level,
                              level2KeyValues: Slice[Memory],
                              assertLevel2: LevelRef => Unit,
                              level2: Level,
                              level3KeyValues: Slice[Memory],
                              assertLevel3: LevelRef => Unit,
                              level3: Level,
                              assertAllLevels: LevelRef => Unit,
                              assertLevel3ForAllLevels: Boolean)(implicit keyOrder: KeyOrder[Slice[Byte]] = KeyOrder.default,
                                                                 levelSweeper: TestCaseSweeper): Unit = {
    implicit val ec: ExecutionContext =
      TestExecutionContext.executionContext

    implicit val compactionIOActor: SegmentCompactionIO.Actor =
      SegmentCompactionIO.create().sweep()

    println("level3.putKeyValues")
    if (level3KeyValues.nonEmpty) level3.put(level3KeyValues).runRandomIO.right.value
    println("level2.putKeyValues")
    if (level2KeyValues.nonEmpty) level2.put(level2KeyValues).runRandomIO.right.value
    println("level1.putKeyValues")
    if (level1KeyValues.nonEmpty) level1.put(level1KeyValues).runRandomIO.right.value
    println("level0.putKeyValues")
    if (level0KeyValues.nonEmpty) level0.putKeyValues(level0KeyValues).runRandomIO.right.value
    import swaydb.testkit.RunThis._

    Seq(
      () => {
        println("asserting Level3")
        assertLevel3(level3)
      },
      () => {
        println("asserting Level2")
        assertLevel2(level2)
      },
      () => {
        println("asserting Level1")
        assertLevel1(level1)
      },

      () => {
        println("asserting Level0")
        assertLevel0(level0)
      },
      () => {
        if (assertLevel3ForAllLevels) {
          println("asserting all on Level3")
          assertAllLevels(level3)
        }
      },
      () => {
        println("asserting all on Level2")
        assertAllLevels(level2)
      },
      () => {
        println("asserting all on Level1")
        assertAllLevels(level1)
      },

      () => {
        println("asserting all on Level0")
        assertAllLevels(level0)
      }
    ).runThisRandomlyInParallel
  }

  def assertSegment[T](keyValues: Slice[Memory],
                       assert: (Slice[Memory], Segment) => T,
                       segmentConfig: SegmentBlockConfig = SegmentBlockConfig.random.copy(mmap = mmapSegments),
                       ensureOneSegmentOnly: Boolean = false,
                       testAgainAfterAssert: Boolean = true,
                       closeAfterCreate: Boolean = false,
                       valuesConfig: ValuesBlockConfig = ValuesBlockConfig.random,
                       sortedIndexConfig: SortedIndexBlockConfig = SortedIndexBlockConfig.random,
                       binarySearchIndexConfig: BinarySearchIndexBlockConfig = BinarySearchIndexBlockConfig.random,
                       hashIndexConfig: HashIndexBlockConfig = HashIndexBlockConfig.random,
                       bloomFilterConfig: BloomFilterBlockConfig = BloomFilterBlockConfig.random)(implicit keyOrder: KeyOrder[Slice[Byte]] = KeyOrder.default,
                                                                                                  sweeper: TestCaseSweeper,
                                                                                                  segmentIO: SegmentReadIO = SegmentReadIO.random,
                                                                                                  ec: ExecutionContext = TestExecutionContext.executionContext) = {
    println(s"assertSegment - keyValues: ${keyValues.size}")

    val segment =
      if (ensureOneSegmentOnly)
        TestSegment.one(
          keyValues = keyValues,
          valuesConfig = valuesConfig,
          sortedIndexConfig = sortedIndexConfig,
          binarySearchIndexConfig = binarySearchIndexConfig,
          hashIndexConfig = hashIndexConfig,
          bloomFilterConfig = bloomFilterConfig,
          segmentConfig = segmentConfig
        )
      else
        TestSegment(
          keyValues = keyValues,
          valuesConfig = valuesConfig,
          sortedIndexConfig = sortedIndexConfig,
          binarySearchIndexConfig = binarySearchIndexConfig,
          hashIndexConfig = hashIndexConfig,
          bloomFilterConfig = bloomFilterConfig,
          segmentConfig = segmentConfig
        )

    if (closeAfterCreate) segment.close

    assert(keyValues, segment) //first
    if (testAgainAfterAssert) {
      assert(keyValues, segment) //with cache populated

      //clear cache and assert
      segment.clearCachedKeyValues()
      assert(keyValues, segment) //same Segment but test with cleared cache.

      //clear all caches and assert
      segment.clearAllCaches()
      assert(keyValues, segment) //same Segment but test with cleared cache.
    }

    segment match {
      case segment: PersistentSegment =>
        val segmentReopened = segment.reopen //reopen
        if (closeAfterCreate) segmentReopened.close
        assert(keyValues, segmentReopened)

        if (testAgainAfterAssert) assert(keyValues, segmentReopened)
        segmentReopened.close

      case _: Segment =>
      //memory segment cannot be reopened
    }

    segment.close
  }
}
