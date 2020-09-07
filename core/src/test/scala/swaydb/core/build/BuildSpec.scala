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
 * If you modify this Program, or any covered work, by linking or combining
 * it with other code, such other code is not for that reason alone subject
 * to any of the requirements of the GNU Affero GPL version 3.
 */

package swaydb.core.build

import java.nio.file.FileAlreadyExistsException

import swaydb.Error.IO
import swaydb.IOValues._
import swaydb.core.TestData._
import swaydb.core.io.file.Effect
import swaydb.core.util.Extension
import swaydb.core.{TestBase, TestCaseSweeper}
import swaydb.data.DataType
import swaydb.data.slice.Slice
import swaydb.data.util.ByteSizeOf

import scala.util.Random

class BuildSpec extends TestBase {

  "write" should {
    "create a build.info file" in {
      TestCaseSweeper {
        implicit sweeper =>
          DataType.all foreach {
            dataType =>
              val version = Build.Version(major = randomIntMax(), minor = randomIntMax(), revision = randomIntMax())
              val buildInfo = Build.Info(version = version, dataType = dataType)

              val folder = randomDir
              Build.write(folder, buildInfo).value shouldBe folder.resolve(Build.fileName)

              val readBuildInfo = Build.read(folder).value
              readBuildInfo shouldBe buildInfo
          }
      }
    }

    "fail if build.info already exists" in {
      TestCaseSweeper {
        implicit sweeper =>
          val folder = createRandomDir
          val file = Effect.createFile(folder.resolve(Build.fileName))
          val fileContent = Effect.readAllBytes(file)

          Build.write(folder, DataType.Map).left.value shouldBe a[FileAlreadyExistsException]

          //file content is unaffected
          Effect.readAllBytes(file) shouldBe fileContent
      }
    }
  }

  "read" should {
    "return fresh" when {
      "the folder does not exist" in {
        TestCaseSweeper {
          implicit sweeper =>

            val folder = randomDir
            Effect.exists(folder) shouldBe false
            Build.read(folder).value shouldBe Build.Fresh
        }
      }

      "the folder exists but is empty" in {
        TestCaseSweeper {
          implicit sweeper =>

            val folder = createRandomDir
            Effect.exists(folder) shouldBe true
            Build.read(folder).value shouldBe Build.Fresh
        }
      }
    }

    "return NoBuildInfo" when {
      "non-empty folder exists without build.info file" in {
        TestCaseSweeper {
          implicit sweeper =>
            Extension.all foreach {
              extension =>
                val folder = createRandomDir
                val file = Effect.createFile(folder.resolve(s"somefile.$extension"))

                Effect.exists(folder) shouldBe true
                Effect.exists(file) shouldBe true

                Build.read(folder).value shouldBe Build.NoBuildInfo

                Build.read(file).value shouldBe Build.NoBuildInfo
            }
        }
      }
    }

    //full read is already tested in writes test-case

    "fail" when {
      "invalid crc" in {
        TestCaseSweeper {
          implicit sweeper =>
            DataType.all foreach {
              dataType =>
                val version = Build.Version(major = randomIntMax(), minor = randomIntMax(), revision = randomIntMax())
                val buildInfo = Build.Info(version = version, dataType = dataType)

                val folder = randomDir
                val file = Build.write(folder, buildInfo).value

                //drop crc
                Effect.overwrite(file, Effect.readAllBytes(file).dropHead())

                Build.read(folder).left.value.getMessage should startWith(s"assertion failed: Invalid CRC.")
            }
        }
      }

      "invalid formatId" in {
        TestCaseSweeper {
          implicit sweeper =>
            DataType.all foreach {
              dataType =>
                val version = Build.Version(major = randomIntMax(), minor = randomIntMax(), revision = randomIntMax())
                val buildInfo = Build.Info(version = version, dataType = dataType)

                val folder = randomDir
                val file = Build.write(folder, buildInfo).value

                val existsBytes = Effect.readAllBytes(file)

                val bytesWithInvalidFormatId = Slice.create[Byte](existsBytes.size)
                bytesWithInvalidFormatId addAll existsBytes.take(ByteSizeOf.long) //keep CRC
                bytesWithInvalidFormatId add (Build.formatId + 1).toByte //change formatId
                bytesWithInvalidFormatId addAll existsBytes.drop(ByteSizeOf.long + 1) //keep the rest
                bytesWithInvalidFormatId.isFull shouldBe true

                //overwrite
                Effect.overwrite(file, bytesWithInvalidFormatId)

                //Invalid formatId will return invalid CRC.
                //            Build.read(folder).left.value.getMessage shouldBe s"assertion failed: $file has invalid formatId. ${Build.formatId + 1} != ${Build.formatId}"
                Build.read(folder).left.value.getMessage should startWith(s"assertion failed: Invalid CRC.")
            }
        }
      }
    }
  }

  "validateOrCreate" should {
    "fail on an existing non-empty directories" when {
      "DisallowOlderVersions" in {
        TestCaseSweeper {
          implicit sweeper =>
            DataType.all foreach {
              dataType =>
                Extension.all foreach {
                  extension =>

                    val folder = createRandomDir
                    val file = folder.resolve(s"somefile.$extension")

                    Effect.createFile(file)
                    Effect.exists(folder) shouldBe true
                    Effect.exists(file) shouldBe true

                    implicit val validator = BuildValidator.DisallowOlderVersions(dataType)
                    Build.validateOrCreate(folder).left.value.getMessage should startWith("Missing build.info file. This directory might be an incompatible older version of SwayDB. Current version:")
                }
            }
        }
      }
    }

    "fail on an existing directory with different dataType" when {
      "DisallowOlderVersions" in {
        TestCaseSweeper {
          implicit sweeper =>
            DataType.all foreach {
              invalidDataType =>

                val dataType = Random.shuffle(DataType.all.toList).find(_ != invalidDataType).get

                implicit val validator = BuildValidator.DisallowOlderVersions(dataType)
                val folder = createRandomDir
                Build.validateOrCreate(folder)

                Effect.exists(folder) shouldBe true
                Effect.exists(folder.resolve(Build.fileName)) shouldBe true

                val error = Build.validateOrCreate(folder)(IO.ExceptionHandler, BuildValidator.DisallowOlderVersions(invalidDataType))
                error.left.value.exception.getMessage shouldBe s"Invalid type ${invalidDataType.name}. This directory is of type ${dataType.name}."
            }
        }
      }
    }

    "pass on empty directory" when {
      "DisallowOlderVersions" in {
        TestCaseSweeper {
          implicit sweeper =>
            DataType.all foreach {
              dataType =>
                val folder = createRandomDir
                Effect.exists(folder) shouldBe true

                implicit val validator = BuildValidator.DisallowOlderVersions(dataType)
                Build.validateOrCreate(folder).value

                Build.read(folder).value shouldBe a[Build.Info]
            }
        }
      }
    }

    "pass on non existing directory" when {
      "DisallowOlderVersions" in {
        TestCaseSweeper {
          implicit sweeper =>
            DataType.all foreach {
              dataType =>
                val folder = randomDir
                Effect.exists(folder) shouldBe false

                implicit val validator = BuildValidator.DisallowOlderVersions(dataType)
                Build.validateOrCreate(folder).value

                Build.read(folder).value shouldBe a[Build.Info]
            }
        }
      }
    }
  }
}
