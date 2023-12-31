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

package swaydb.core.segment.ref.search

import swaydb.core.segment.data.{Persistent, PersistentOption}
import swaydb.slice.Slice
import swaydb.utils.{SomeOrNone, TupleOrNone}

import java.nio.file.Path

private[ref] sealed trait SegmentReadStateOption extends SomeOrNone[SegmentReadStateOption, SegmentReadState] {
  override def noneS: SegmentReadStateOption = SegmentReadState.Null
}

private[ref] object SegmentReadState {

  final case object Null extends SegmentReadStateOption {
    override def isNoneS: Boolean = true
    override def getS: SegmentReadState = throw new Exception("SegmentState is of type Null")
  }

  def updateOnSuccessSequentialRead(path: Path,
                                    forKey: Slice[Byte],
                                    segmentState: SegmentReadStateOption,
                                    threadReadState: ThreadReadState,
                                    found: Persistent): Unit =
    if (segmentState.isNoneS)
      createOnSuccessSequentialRead(
        path = path,
        forKey = forKey,
        readState = threadReadState,
        found = found
      )
    else
      mutateOnSuccessSequentialRead(
        path = path,
        forKey = forKey,
        readState = threadReadState,
        segmentState = segmentState.getS,
        found = found
      )

  /**
   * Sets read state after successful sequential read.
   */

  def createOnSuccessSequentialRead(path: Path,
                                    forKey: Slice[Byte],
                                    readState: ThreadReadState,
                                    found: Persistent): Unit = {
    found.cutKeys()

    val segmentState =
      new SegmentReadState(
        keyValue = (forKey.cut(), found),
        lower = TupleOrNone.None,
        isSequential = true
      )

    readState.setSegmentState(path, segmentState)
  }

  def mutateOnSuccessSequentialRead(path: Path,
                                    forKey: Slice[Byte],
                                    readState: ThreadReadState,
                                    segmentState: SegmentReadState,
                                    found: Persistent): Unit = {
    found.cutKeys()
    val state = segmentState.getS
    //mutate segmentState for next sequential read
    state.keyValue = (forKey.cut(), found)
    state.isSequential = true
  }

  def updateAfterRandomRead(path: Path,
                            forKey: Slice[Byte],
                            start: PersistentOption,
                            segmentStateOptional: SegmentReadStateOption,
                            threadReadState: ThreadReadState,
                            foundOption: PersistentOption): Unit =
    if (segmentStateOptional.isSomeS)
      SegmentReadState.mutateAfterRandomRead(
        path = path,
        forKey = forKey,
        threadState = threadReadState,
        segmentState = segmentStateOptional.getS,
        foundOption = foundOption
      )
    else
      SegmentReadState.createAfterRandomRead(
        path = path,
        forKey = forKey,
        start = start,
        threadState = threadReadState,
        foundOption = foundOption
      )

  /**
   * Sets read state after a random read WITHOUT an existing [[SegmentReadState]] exists.
   */
  def createAfterRandomRead(path: Path,
                            forKey: Slice[Byte],
                            start: PersistentOption,
                            threadState: ThreadReadState,
                            foundOption: PersistentOption): Unit =

    if (foundOption.isSomeS) {
      val foundKeyValue = foundOption.getS

      foundKeyValue.cutKeys()

      val segmentState =
        new SegmentReadState(
          keyValue = (forKey.cut(), foundKeyValue),
          lower = TupleOrNone.None,
          isSequential = start.isSomeS && foundKeyValue.indexOffset == start.getS.nextIndexOffset
        )

      threadState.setSegmentState(path, segmentState)
    }

  /**
   * Sets read state after a random read WITH an existing [[SegmentReadState]] exists.
   */
  def mutateAfterRandomRead(path: Path,
                            forKey: Slice[Byte],
                            threadState: ThreadReadState,
                            segmentState: SegmentReadState, //should not be null.
                            foundOption: PersistentOption): Unit =
    if (foundOption.isSomeS) {
      val foundKeyValue = foundOption.getS
      foundKeyValue.cutKeys()
      segmentState.isSequential = foundKeyValue.indexOffset == segmentState.keyValue._2.nextIndexOffset
      segmentState.keyValue = (forKey.cut(), foundKeyValue)
    } else {
      segmentState.isSequential = false
    }
}

/**
 * Stores read state of each accessed Segment.
 * This cache is currently managed in [[swaydb.core.Core.readStates]].
 *
 * Both Get and Higher functions mutate [[keyValue]]. But lower
 * can only mutate [[lower]] as it depends on get to fetch
 * the end key-value for faster lower search and should not mutate
 * get's set [[keyValue]].
 */
private[ref] class SegmentReadState(var keyValue: (Slice[Byte], Persistent),
                                    var lower: TupleOrNone[Slice[Byte], Persistent],
                                    var isSequential: Boolean) extends SegmentReadStateOption {
  override def isNoneS: Boolean = false
  override def getS: SegmentReadState = this
}
