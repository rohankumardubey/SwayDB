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

package swaydb.core.segment.assigner

import swaydb.Aggregator
import swaydb.core.data.{KeyValue, Memory, MemoryOption, Value}
import swaydb.core.segment.{Segment, SegmentOption}
import swaydb.core.util.DropIterator
import swaydb.core.util.skiplist.SkipList
import swaydb.data.MaxKey
import swaydb.data.order.KeyOrder
import swaydb.data.slice.{Slice, SliceOption}

import scala.annotation.tailrec
import scala.collection.mutable
import scala.collection.mutable.ListBuffer

private[core] object SegmentAssigner {

  def assignMinMaxOnlyUnsafe(inputSegments: Iterable[Segment],
                             targetSegments: Iterable[Segment])(implicit keyOrder: KeyOrder[Slice[Byte]]): Iterable[Segment] =
    SegmentAssigner.assignUnsafe(2 * inputSegments.size, Segment.tempMinMaxKeyValues(inputSegments), targetSegments).keys

  def assignMinMaxOnlyUnsafe(input: SkipList[SliceOption[Byte], MemoryOption, Slice[Byte], Memory],
                             targetSegments: Iterable[Segment])(implicit keyOrder: KeyOrder[Slice[Byte]]): Iterable[Segment] =
    SegmentAssigner.assignUnsafe(2, Segment.tempMinMaxKeyValues(input), targetSegments).keys

  def assignMinMaxOnlyUnsafe(input: Slice[Memory],
                             targetSegments: Iterable[Segment])(implicit keyOrder: KeyOrder[Slice[Byte]]): Iterable[Segment] =
    SegmentAssigner.assignUnsafe(2, Segment.tempMinMaxKeyValues(input), targetSegments).keys

  def assignUnsafe(keyValues: Slice[KeyValue],
                   segments: Iterable[Segment])(implicit keyOrder: KeyOrder[Slice[Byte]]): mutable.Map[Segment, Slice[KeyValue]] =
    assignUnsafe(
      keyValuesCount = keyValues.size,
      keyValues = keyValues,
      segments = segments
    )

  def assignUnsafe(keyValuesCount: Int,
                   keyValues: Iterable[KeyValue],
                   segments: Iterable[Segment])(implicit keyOrder: KeyOrder[Slice[Byte]]): mutable.Map[Segment, Slice[KeyValue]] = {

    //TODO - remove Map. Used temporarily so the code compiles.
    //    val buffer =
    //      assignUnsafe(
    //        keyValuesCount = keyValuesCount,
    //        keyValues = keyValues,
    //        segments = segments,
    //        noGaps = true
    //      )(keyOrder, Aggregator.Creator.nothing()).asInstanceOf[ListBuffer[Assignment.Assigned]]
    //
    //    val map = mutable.Map.empty[Segment, Slice[KeyValue]]
    //
    //    buffer foreach {
    //      assigned =>
    //        map.put(assigned.segment, assigned.assignments)
    //    }
    //
    //    map
    ???
  }

  def assignUnsafeWithGaps[GAP](keyValuesCount: Int,
                                keyValues: Iterable[KeyValue],
                                segments: Iterable[Segment])(implicit keyOrder: KeyOrder[Slice[Byte]],
                                                             gapCreator: Aggregator.CreatorSizeable[KeyValue, GAP]): ListBuffer[Assignment[GAP]] =
  //    assignUnsafe(
  //      keyValuesCount = keyValuesCount,
  //      keyValues = keyValues,
  //      segments = segments,
  //      noGaps = false
  //    )
    ???

  /**
   * @param assignablesCount keyValuesCount is needed here because keyValues could be a [[ConcurrentSkipList]]
   *                         where calculating size is not constant time.
   */
  private def assignUnsafe[GAP](assignablesCount: Int,
                                assignables: Iterable[Assignable],
                                segments: Iterable[Segment],
                                noGaps: Boolean)(implicit keyOrder: KeyOrder[Slice[Byte]],
                                                 gapCreator: Aggregator.CreatorSizeable[Assignable, GAP]): ListBuffer[Assignment[GAP]] = {
    if (noGaps && Segment.hasOnlyOneSegment(segments)) { //.size iterates the entire Iterable which is not needed.
      val assignments = ListBuffer[Assignment[GAP]]()
      assignments += Assignment.AssignedImmutable(segments.head, assignables)
      assignments
    } else {
      import keyOrder._

      val assignments = ListBuffer.empty[Assignment.Mutable[GAP]]

      val segmentsIterator = segments.iterator

      def getNextSegmentMayBe() = if (segmentsIterator.hasNext) segmentsIterator.next() else Segment.Null

      def assignToSegment(assignable: Assignable,
                          assignTo: Segment): Unit =
        assignments.lastOption match {
          case Some(Assignment.AssignedBuffer(bufferSegment, keyValues)) if bufferSegment.segmentId == assignTo.segmentId =>
            keyValues += assignable

          case _ =>
            assignments += Assignment.AssignedBuffer(assignTo, ListBuffer(assignable))
        }

      def assignToGap(keyValue: Assignable,
                      remainingKeyValues: Int): Unit =
        assignments.lastOption match {
          case Some(Assignment.Gap(aggregator)) =>
            aggregator add keyValue

          case _ =>
            //ignore assigned a create a new gap
            val gapAggregator = gapCreator.createNewSizeHint(remainingKeyValues + 1)
            gapAggregator add keyValue
            assignments += Assignment.Gap(gapAggregator)
        }

      @tailrec
      def assign(remaining: DropIterator[Memory.Range, Assignable],
                 thisSegmentMayBe: SegmentOption,
                 nextSegmentMayBe: SegmentOption): Unit = {
        val assignable = remaining.headOrNull

        if (assignable != null)
          thisSegmentMayBe match {
            case Segment.Null =>
              if (noGaps)
                throw new Exception("Cannot assign key-value to Null Segment.")
              else
                remaining.iterator foreach {
                  keyValue =>
                    assignToGap(keyValue, remaining.size)
                }

            case thisSegment: Segment =>
              val keyCompare = keyOrder.compare(assignable.key, thisSegment.minKey)

              //0 = Unknown. 1 = true, -1 = false
              var _belongsTo = 0

              def getKeyBelongsToNoSpread(): Boolean = {
                if (_belongsTo == 0)
                  if (Segment.belongsToNoSpread(assignable, thisSegment))
                    _belongsTo = 1
                  else
                    _belongsTo = -1

                _belongsTo == 1
              }

              def spreadToNextSegment(assignable: Segment, segment: Segment) =
                assignable.maxKey match {
                  case MaxKey.Fixed(maxKey) =>
                    maxKey >= segment.minKey

                  case MaxKey.Range(_, maxKey) =>
                    maxKey > segment.minKey
                }

              //check if this key-value if it is the new smallest key or if this key belong to this Segment or if there is no next Segment
              if (keyCompare <= 0 || getKeyBelongsToNoSpread() || nextSegmentMayBe.isNoneS)
                assignable match {
                  case assignable: Segment =>
                    nextSegmentMayBe match {
                      case nextSegment: Segment if spreadToNextSegment(assignable, nextSegment) => //check if Segment spreads onto next Segment
                        val keyValueCount = assignable.getKeyValueCount()
                        val keyValues = assignable.iterator()
                        val segmentIterator = DropIterator[Memory.Range, Assignable](keyValueCount, keyValues)

                        val newRemaining = segmentIterator append remaining.dropHead()

                        assign(newRemaining, thisSegmentMayBe, nextSegmentMayBe)

                      case _ =>
                        if (noGaps || keyCompare == 0 || getKeyBelongsToNoSpread()) //if this Segment should be added to thisSegment
                          assignToSegment(assignable = assignable, assignTo = thisSegment)
                        else //gap Segment
                          assignToGap(assignable, remaining.size)

                        assign(remaining.dropHead(), thisSegmentMayBe, nextSegmentMayBe)
                    }

                  case keyValue: KeyValue.Fixed =>
                    if (noGaps || keyCompare == 0 || getKeyBelongsToNoSpread()) //if this key-value should be added to thisSegment
                      assignToSegment(assignable = keyValue, assignTo = thisSegment)
                    else //gap key
                      assignToGap(keyValue, remaining.size)

                    assign(remaining.dropHead(), thisSegmentMayBe, nextSegmentMayBe)

                  case keyValue: KeyValue.Range =>
                    nextSegmentMayBe match {
                      //check if this range key-value spreads onto the next segment
                      case nextSegment: Segment if keyValue.toKey > nextSegment.minKey =>
                        val (fromValue, rangeValue) = keyValue.fetchFromAndRangeValueUnsafe
                        val thisSegmentsRange = Memory.Range(fromKey = keyValue.fromKey, toKey = nextSegment.minKey, fromValue = fromValue, rangeValue = rangeValue)
                        val nextSegmentsRange = Memory.Range(fromKey = nextSegment.minKey, toKey = keyValue.toKey, fromValue = Value.FromValue.Null, rangeValue = rangeValue)

                        if (noGaps || keyCompare == 0 || getKeyBelongsToNoSpread()) //should add to this segment
                          assignToSegment(assignable = thisSegmentsRange, assignTo = thisSegment)
                        else //should add as a gap
                          assignToGap(thisSegmentsRange, remaining.size)

                        assign(remaining.dropPrepend(nextSegmentsRange), nextSegment, getNextSegmentMayBe())

                      case _ =>
                        //belongs to current segment
                        if (noGaps || keyCompare == 0 || getKeyBelongsToNoSpread())
                          assignToSegment(assignable = keyValue, assignTo = thisSegment)
                        else
                          assignToGap(keyValue, remaining.size)

                        assign(remaining.dropHead(), thisSegmentMayBe, nextSegmentMayBe)
                    }
                }
              else
                nextSegmentMayBe match {
                  case Segment.Null =>
                    if (noGaps)
                      throw new Exception("Cannot assign key-value to Null next Segment.")
                    else
                      remaining.iterator foreach {
                        keyValue =>
                          assignToGap(keyValue, remaining.size)
                      }

                  case nextSegment: Segment =>
                    if (assignable.key < nextSegment.minKey) // is this a gap key between thisSegment and the nextSegment
                      assignable match {
                        case assignable: Segment =>
                          if (spreadToNextSegment(assignable, nextSegment)) {
                            //if this Segment spreads onto next Segment read all key-values and assign.
                            val keyValueCount = assignable.getKeyValueCount()
                            val keyValues = assignable.iterator()
                            val segmentIterator = DropIterator[Memory.Range, Assignable](keyValueCount, keyValues)

                            val newRemaining = segmentIterator append remaining.dropHead()

                            assign(newRemaining, thisSegmentMayBe, nextSegmentMayBe)

                          } else {
                            //does not spread onto next Segment.
                            if (noGaps || keyCompare == 0 || getKeyBelongsToNoSpread()) //if this Segment should be added to thisSegment
                              assignToSegment(assignable = assignable, assignTo = thisSegment)
                            else //gap segment
                              assignToGap(assignable, remaining.size)

                            assign(remaining.dropHead(), thisSegmentMayBe, nextSegmentMayBe)
                          }

                        case _: KeyValue.Fixed =>
                          if (noGaps) {
                            //check if a key-value is already assigned to thisSegment. Else if thisSegment is empty jump to next
                            //there is no point adding a single key-value to a Segment.
                            assignments.lastOption match {
                              case Some(Assignment.AssignedBuffer(segment, keyValues)) if segment.segmentId == thisSegment.segmentId =>
                                keyValues += assignable
                                assign(remaining.dropHead(), thisSegmentMayBe, nextSegmentMayBe)

                              case _ =>
                                assign(remaining, nextSegment, getNextSegmentMayBe())
                            }
                          } else {
                            //Is a gap key
                            assignToGap(assignable, remaining.size)
                            assign(remaining.dropHead(), nextSegment, getNextSegmentMayBe())
                          }

                        case keyValue: KeyValue.Range =>
                          if (keyValue.toKey > nextSegment.minKey) {
                            //if it's a gap Range key-value and it's flows onto the next Segment.
                            if (noGaps) {
                              //no gaps allowed, jump to next segment and avoid splitting the range.
                              assign(remaining, nextSegment, getNextSegmentMayBe())
                            } else {
                              //perform a split
                              val (fromValue, rangeValue) = keyValue.fetchFromAndRangeValueUnsafe
                              val thisSegmentsRange = Memory.Range(fromKey = keyValue.fromKey, toKey = nextSegment.minKey, fromValue = fromValue, rangeValue = rangeValue)
                              val nextSegmentsRange = Memory.Range(fromKey = nextSegment.minKey, toKey = keyValue.toKey, fromValue = Value.FromValue.Null, rangeValue = rangeValue)
                              assignToGap(thisSegmentsRange, remaining.size)

                              assign(remaining.dropPrepend(nextSegmentsRange), nextSegment, getNextSegmentMayBe())
                            }
                          } else {
                            //ignore if a key-value is not already assigned to thisSegment. No point adding a single key-value to a Segment.
                            //same code as above, need to push it to a common function.
                            if (noGaps) {
                              assignments.lastOption match {
                                case Some(Assignment.AssignedBuffer(segment, keyValues)) if segment.segmentId == thisSegment.segmentId =>
                                  keyValues += keyValue
                                  assign(remaining.dropHead(), thisSegmentMayBe, nextSegmentMayBe)

                                case _ =>
                                  assign(remaining, nextSegment, getNextSegmentMayBe())
                              }
                            } else {
                              assignToGap(keyValue, remaining.size)
                              assign(remaining.dropHead(), nextSegment, getNextSegmentMayBe())
                            }
                          }
                      }
                    else //jump to next Segment.
                      assign(remaining, nextSegment, getNextSegmentMayBe())
                }
          }
      }

      if (segmentsIterator.hasNext)
        assign(DropIterator(assignablesCount, assignables.iterator), segmentsIterator.next(), getNextSegmentMayBe())

      assignments.asInstanceOf[ListBuffer[Assignment[GAP]]]
    }
  }
}
