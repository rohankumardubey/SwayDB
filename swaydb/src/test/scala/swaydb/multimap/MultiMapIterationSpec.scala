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
 */

package swaydb.multimap

import org.scalatest.OptionValues._
import swaydb.api.TestBaseEmbedded
import swaydb.data.util.StorageUnits._
import swaydb.serializers.Default._
import swaydb.{Bag, MultiMap}

class MultiMapIterationSpec0 extends MultiMapIterationSpec {
  val keyValueCount: Int = 1000

  override def newDB(): MultiMap[Int, String, Nothing, Bag.Less] =
    swaydb.persistent.MultiMap[Int, String, Nothing, Bag.Less](dir = randomDir)
}

class MultiMapIterationSpec1 extends MultiMapIterationSpec {
  val keyValueCount: Int = 1000

  override def newDB(): MultiMap[Int, String, Nothing, Bag.Less] =
    swaydb.persistent.MultiMap[Int, String, Nothing, Bag.Less](dir = randomDir, mapSize = 1.byte)
}

class MultiMapIterationSpec2 extends MultiMapIterationSpec {
  val keyValueCount: Int = 1000

  override def newDB(): MultiMap[Int, String, Nothing, Bag.Less] =
    swaydb.memory.MultiMap[Int, String, Nothing, Bag.Less]()
}

class MultiMapIterationSpec3 extends MultiMapIterationSpec {
  val keyValueCount: Int = 1000

  override def newDB(): MultiMap[Int, String, Nothing, Bag.Less] =
    swaydb.memory.MultiMap[Int, String, Nothing, Bag.Less](mapSize = 1.byte)
}

sealed trait MultiMapIterationSpec extends TestBaseEmbedded {

  val keyValueCount: Int

  def newDB(): MultiMap[Int, String, Nothing, Bag.Less]

  implicit val bag = Bag.less

  "Iteration" should {
    "exclude & include subMap by default" in {
      val db = newDB()

      val firstMap = db.children.init(1)
      val secondMap = firstMap.children.init(2)
      val subMap1 = secondMap.children.init(3)
      val subMap2 = secondMap.children.init(4)

      firstMap.stream.materialize.toList shouldBe empty
      firstMap.children.keys.materialize.toList should contain only 2

      secondMap.stream.materialize.toList shouldBe empty
      secondMap.children.keys.materialize.toList should contain only(3, 4)

      subMap1.stream.materialize.toList shouldBe empty
      subMap2.stream.materialize.toList shouldBe empty

      db.delete()
    }
  }

  "Iteration" when {
    "the map contains 1 element" in {
      val db = newDB()

      val firstMap = db.children.init(1)
      val secondMap = firstMap.children.init(2)

      firstMap.stream.materialize.toList shouldBe empty
      firstMap.children.keys.materialize.toList should contain only 2

      secondMap.put(1, "one")
      secondMap.stream.size shouldBe 1

      secondMap.headOption.value shouldBe ((1, "one"))
      secondMap.lastOption.value shouldBe ((1, "one"))

      secondMap.stream.map(keyValue => (keyValue._1 + 1, keyValue._2)).materialize.toList should contain only ((2, "one"))
      secondMap.stream.foldLeft(List.empty[(Int, String)]) { case (_, keyValue) => List(keyValue) } shouldBe List((1, "one"))
      secondMap.reverse.stream.foldLeft(List.empty[(Int, String)]) { case (_, keyValue) => List(keyValue) } shouldBe List((1, "one"))
      secondMap.reverse.stream.map(keyValue => (keyValue._1 + 1, keyValue._2)).materialize.toList should contain only ((2, "one"))
      secondMap.reverse.stream.take(100).materialize.toList should contain only ((1, "one"))
      secondMap.reverse.stream.take(1).materialize.toList should contain only ((1, "one"))
      secondMap.stream.take(100).materialize.toList should contain only ((1, "one"))
      secondMap.stream.take(1).materialize.toList should contain only ((1, "one"))
      secondMap.reverse.stream.drop(1).materialize.toList shouldBe empty
      secondMap.stream.drop(1).materialize.toList shouldBe empty
      secondMap.reverse.stream.drop(0).materialize.toList should contain only ((1, "one"))
      secondMap.stream.drop(0).materialize.toList should contain only ((1, "one"))

      db.delete()
    }

    "the map contains 2 elements" in {
      val db = newDB()

      val rootMap = db.children.init(1)
      val firstMap = rootMap.children.init(2)

      firstMap.put(1, "one")
      firstMap.put(2, "two")

      firstMap.stream.size shouldBe 2
      firstMap.headOption.value shouldBe ((1, "one"))
      firstMap.lastOption.value shouldBe ((2, "two"))

      firstMap.stream.map(keyValue => (keyValue._1 + 1, keyValue._2)).materialize.toList shouldBe List((2, "one"), (3, "two"))
      firstMap.stream.foldLeft(List.empty[(Int, String)]) { case (previous, keyValue) => previous :+ keyValue } shouldBe List((1, "one"), (2, "two"))
      firstMap.reverse.stream.foldLeft(List.empty[(Int, String)]) { case (previous, keyValue) => previous :+ keyValue } shouldBe List((2, "two"), (1, "one"))
      firstMap.reverse.stream.map(keyValue => keyValue).materialize.toList shouldBe List((2, "two"), (1, "one"))
      firstMap.reverse.stream.take(100).materialize.toList shouldBe List((2, "two"), (1, "one"))
      firstMap.reverse.stream.take(2).materialize.toList shouldBe List((2, "two"), (1, "one"))
      firstMap.reverse.stream.take(1).materialize.toList should contain only ((2, "two"))
      firstMap.stream.take(100).materialize.toList should contain only((1, "one"), (2, "two"))
      firstMap.stream.take(2).materialize.toList should contain only((1, "one"), (2, "two"))
      firstMap.stream.take(1).materialize.toList should contain only ((1, "one"))
      firstMap.reverse.stream.drop(1).materialize.toList should contain only ((1, "one"))
      firstMap.stream.drop(1).materialize.toList should contain only ((2, "two"))
      firstMap.reverse.stream.drop(0).materialize.toList shouldBe List((2, "two"), (1, "one"))
      firstMap.stream.drop(0).materialize.toList shouldBe List((1, "one"), (2, "two"))

      db.delete()
    }

    "Sibling maps" in {
      val db = newDB()

      val rootMap = db.children.init(1)

      val subMap1 = rootMap.children.init(2)
      subMap1.put(1, "one")
      subMap1.put(2, "two")

      val subMap2 = rootMap.children.init(3)
      subMap2.put(3, "three")
      subMap2.put(4, "four")

      rootMap.stream.materialize.toList shouldBe empty
      rootMap.children.keys.materialize.toList should contain only(2, 3)

      //FIRST MAP ITERATIONS
      subMap1.stream.size shouldBe 2
      subMap1.headOption.value shouldBe ((1, "one"))
      subMap1.lastOption.value shouldBe ((2, "two"))
      subMap1.stream.map(keyValue => (keyValue._1 + 1, keyValue._2)).materialize.toList shouldBe List((2, "one"), (3, "two"))
      subMap1.stream.foldLeft(List.empty[(Int, String)]) { case (previous, keyValue) => previous :+ keyValue } shouldBe List((1, "one"), (2, "two"))
      subMap1.reverse.stream.foldLeft(List.empty[(Int, String)]) { case (keyValue, previous) => keyValue :+ previous } shouldBe List((2, "two"), (1, "one"))
      subMap1.reverse.stream.map(keyValue => keyValue).materialize.toList shouldBe List((2, "two"), (1, "one"))
      subMap1.reverse.stream.take(100).materialize.toList shouldBe List((2, "two"), (1, "one"))
      subMap1.reverse.stream.take(2).materialize.toList shouldBe List((2, "two"), (1, "one"))
      subMap1.reverse.stream.take(1).materialize.toList should contain only ((2, "two"))
      subMap1.stream.take(100).materialize.toList should contain only((1, "one"), (2, "two"))
      subMap1.stream.take(2).materialize.toList should contain only((1, "one"), (2, "two"))
      subMap1.stream.take(1).materialize.toList should contain only ((1, "one"))
      subMap1.reverse.stream.drop(1).materialize.toList should contain only ((1, "one"))
      subMap1.stream.drop(1).materialize.toList should contain only ((2, "two"))
      subMap1.reverse.stream.drop(0).materialize.toList shouldBe List((2, "two"), (1, "one"))
      subMap1.stream.drop(0).materialize.toList shouldBe List((1, "one"), (2, "two"))

      //SECOND MAP ITERATIONS
      subMap2.stream.size shouldBe 2
      subMap2.headOption.value shouldBe ((3, "three"))
      subMap2.lastOption.value shouldBe ((4, "four"))
      subMap2.stream.map(keyValue => (keyValue._1, keyValue._2)).materialize.toList shouldBe List((3, "three"), (4, "four"))
      subMap2.stream.foldLeft(List.empty[(Int, String)]) { case (previous, keyValue) => previous :+ keyValue } shouldBe List((3, "three"), (4, "four"))
      subMap2.reverse.stream.foldLeft(List.empty[(Int, String)]) { case (keyValue, previous) => keyValue :+ previous } shouldBe List((4, "four"), (3, "three"))
      subMap2.reverse.stream.map(keyValue => keyValue).materialize.toList shouldBe List((4, "four"), (3, "three"))
      subMap2.reverse.stream.take(100).materialize.toList shouldBe List((4, "four"), (3, "three"))
      subMap2.reverse.stream.take(2).materialize.toList shouldBe List((4, "four"), (3, "three"))
      subMap2.reverse.stream.take(1).materialize.toList should contain only ((4, "four"))
      subMap2.stream.take(100).materialize.toList should contain only((3, "three"), (4, "four"))
      subMap2.stream.take(2).materialize.toList should contain only((3, "three"), (4, "four"))
      subMap2.stream.take(1).materialize.toList should contain only ((3, "three"))
      subMap2.reverse.stream.drop(1).materialize.toList should contain only ((3, "three"))
      subMap2.stream.drop(1).materialize.toList should contain only ((4, "four"))
      subMap2.reverse.stream.drop(0).materialize.toList shouldBe List((4, "four"), (3, "three"))
      subMap2.stream.drop(0).materialize.toList shouldBe List((3, "three"), (4, "four"))

      db.delete()
    }

    "nested maps" in {
      val db = newDB()

      val rootMap = db.children.init(1)

      val subMap1 = rootMap.children.init(2)
      subMap1.put(1, "one")
      subMap1.put(2, "two")

      val subMap2 = subMap1.children.init(3)
      subMap2.put(3, "three")
      subMap2.put(4, "four")

      rootMap.stream.materialize.toList shouldBe empty
      rootMap.children.keys.materialize.toList should contain only 2

      //FIRST MAP ITERATIONS
      subMap1.stream.size shouldBe 2
      subMap1.headOption.value shouldBe ((1, "one"))
      subMap1.lastOption.value shouldBe ((2, "two"))
      subMap1.children.keys.lastOption.value shouldBe 3
      subMap1.stream.map(keyValue => (keyValue._1, keyValue._2)).materialize.toList shouldBe List((1, "one"), (2, "two"))
      subMap1.children.keys.materialize.toList shouldBe List(3)
      subMap1.stream.foldLeft(List.empty[(Int, String)]) { case (previous, keyValue) => previous :+ keyValue } shouldBe List((1, "one"), (2, "two"))
      subMap1.children.keys.foldLeft(List.empty[Int]) { case (previous, keyValue) => previous :+ keyValue } shouldBe List(3)
      subMap1.reverse.stream.foldLeft(List.empty[(Int, String)]) { case (keyValue, previous) => keyValue :+ previous } shouldBe List((2, "two"), (1, "one"))
      subMap1.reverse.stream.map(keyValue => keyValue).materialize.toList shouldBe List((2, "two"), (1, "one"))
      subMap1.reverse.stream.take(100).materialize.toList shouldBe List((2, "two"), (1, "one"))
      subMap1.reverse.stream.take(3).materialize.toList shouldBe List((2, "two"), (1, "one"))
      subMap1.reverse.stream.take(1).materialize.toList should contain only ((2, "two"))
      subMap1.stream.take(100).materialize.toList should contain only((1, "one"), (2, "two"))
      subMap1.stream.take(2).materialize.toList should contain only((1, "one"), (2, "two"))
      subMap1.stream.take(1).materialize.toList should contain only ((1, "one"))
      subMap1.reverse.stream.drop(1).materialize.toList should contain only ((1, "one"))
      subMap1.children.keys.drop(1).materialize.toList shouldBe empty
      subMap1.stream.drop(1).materialize.toList should contain only ((2, "two"))
      subMap1.children.stream.drop(1).materialize.toList shouldBe empty
      subMap1.reverse.stream.drop(0).materialize.toList shouldBe List((2, "two"), (1, "one"))
      subMap1.children.keys.drop(0).materialize.toList shouldBe List(3)
      subMap1.stream.drop(0).materialize.toList shouldBe List((1, "one"), (2, "two"))

      //KEYS ONLY ITERATIONS - TODO - Key iterations are currently not supported for MultiMap.
      //      subMap1.keys.size shouldBe 2
      //      subMap1.keys.headOption.value shouldBe 1
      //      subMap1.keys.lastOption.value shouldBe 2
      //      //      subMap1.maps.keys.lastOption.runIO shouldBe 3
      //      //      subMap1.maps.keys.toSeq shouldBe List(3)
      //
      //SECOND MAP ITERATIONS
      subMap2.stream.size shouldBe 2
      subMap2.stream.headOption.value shouldBe ((3, "three"))
      subMap2.stream.lastOption.value shouldBe ((4, "four"))
      subMap2.stream.map(keyValue => (keyValue._1, keyValue._2)).materialize.toList shouldBe List((3, "three"), (4, "four"))
      subMap2.stream.foldLeft(List.empty[(Int, String)]) { case (previous, keyValue) => previous :+ keyValue } shouldBe List((3, "three"), (4, "four"))
      subMap2.reverse.stream.foldLeft(List.empty[(Int, String)]) { case (keyValue, previous) => keyValue :+ previous } shouldBe List((4, "four"), (3, "three"))
      subMap2.reverse.stream.map(keyValue => keyValue).materialize.toList shouldBe List((4, "four"), (3, "three"))
      subMap2.reverse.stream.take(100).materialize.toList shouldBe List((4, "four"), (3, "three"))
      subMap2.reverse.stream.take(2).materialize.toList shouldBe List((4, "four"), (3, "three"))
      subMap2.reverse.stream.take(1).materialize.toList should contain only ((4, "four"))
      subMap2.stream.take(100).materialize.toList should contain only((3, "three"), (4, "four"))
      subMap2.stream.take(2).materialize.toList should contain only((3, "three"), (4, "four"))
      subMap2.stream.take(1).materialize.toList should contain only ((3, "three"))
      subMap2.reverse.stream.drop(1).materialize.toList should contain only ((3, "three"))
      subMap2.stream.drop(1).materialize.toList should contain only ((4, "four"))
      subMap2.reverse.stream.drop(0).materialize.toList shouldBe List((4, "four"), (3, "three"))
      subMap2.stream.drop(0).materialize.toList shouldBe List((3, "three"), (4, "four"))

      db.delete()
    }
  }
}
