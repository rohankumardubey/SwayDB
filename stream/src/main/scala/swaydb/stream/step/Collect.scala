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

package swaydb.stream.step

import swaydb.Bag
import swaydb.stream.StreamFree

private[swaydb] class Collect[A, B](previousStream: StreamFree[A],
                                    pf: PartialFunction[A, B]) extends StreamFree[B] {

  var previousA: A = _

  def stepForward[BAG[_]](startFromOrNull: A)(implicit bag: Bag[BAG]): BAG[B] =
    if (startFromOrNull == null) {
      bag.success(null.asInstanceOf[B])
    } else {
      var nextMatch: B = null.asInstanceOf[B]

      //collectFirst is a stackSafe way reading the stream until a condition is met.
      //use collectFirst to stream until the first match.

      val collected =
        Step
          .collectFirst(startFromOrNull, previousStream) {
            nextAOrNull =>
              this.previousA = nextAOrNull

              if (this.previousA != null && pf.isDefinedAt(this.previousA))
                nextMatch = pf.apply(this.previousA)

              nextMatch != null
          }

      bag.transform(collected) {
        _ =>
          //return the matched result. This code could be improved if bag.collectFirst also took a pf instead of a function.
          nextMatch
      }
    }

  override private[swaydb] def headOrNull[BAG[_]](implicit bag: Bag[BAG]): BAG[B] =
    bag.flatMap(previousStream.headOrNull) {
      headOrNull =>
        //check if head satisfies the partial functions.
        this.previousA = headOrNull //also store A in the current Stream so next() invocation starts from this A.
        val previousAOrNull =
          if (previousA == null || !pf.isDefinedAt(previousA))
            null.asInstanceOf[B]
          else
            pf.apply(previousA)
        //check if headOption can be returned.

        if (previousAOrNull != null) //check if headOption satisfies the partial function.
          bag.success(previousAOrNull) //yes it does. Return!
        else if (headOrNull != null) //headOption did not satisfy the partial function but check if headOption was defined and step forward.
          stepForward(headOrNull) //headOption was defined so there might be more in the stream so step forward.
        else //if there was no headOption then stream must be empty.
          bag.success(null.asInstanceOf[B]) //empty stream.
    }

  override private[swaydb] def nextOrNull[BAG[_]](previous: B)(implicit bag: Bag[BAG]) =
    stepForward(previousA) //continue from previously read A.
}
