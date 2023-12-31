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

package swaydb.api.setmap

import org.scalatest.OptionValues._
import swaydb.Glass
import swaydb.core.TestCaseSweeper._
import swaydb.core.{TestBase, TestCaseSweeper}
import swaydb.serializers.Default._

class SetMapSpec0 extends SetMapSpec {
  override def newDB()(implicit sweeper: TestCaseSweeper): swaydb.SetMap[Int, String, Glass] =
    swaydb.persistent.SetMap[Int, String, Glass](randomDir).sweep(_.delete())
}

class SetMapSpec3 extends SetMapSpec {
  override def newDB()(implicit sweeper: TestCaseSweeper): swaydb.SetMap[Int, String, Glass] =
    swaydb.memory.SetMap[Int, String, Glass]().sweep(_.delete())
}

sealed trait SetMapSpec extends TestBase {

  def newDB()(implicit sweeper: TestCaseSweeper): swaydb.SetMap[Int, String, Glass]

  "put" in {
    TestCaseSweeper {
      implicit sweeper =>

        val map = newDB()

        (1 to 1000000) foreach {
          i =>
            map.put(i, i.toString)
        }

        (1 to 1000000) foreach {
          i =>
            map.get(i).value shouldBe i.toString
        }
    }
  }
}
