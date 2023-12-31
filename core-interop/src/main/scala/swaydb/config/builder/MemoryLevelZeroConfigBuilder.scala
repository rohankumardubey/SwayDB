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

package swaydb.config.builder

import swaydb.config.accelerate.{Accelerator, LevelZeroMeter}
import swaydb.config.compaction.LevelZeroThrottle
import swaydb.config.{Atomic, ConfigWizard, OptimiseWrites}
import swaydb.utils.Java.JavaFunction

/**
 * Java Builder class for [[swaydb.config.MemoryLevelZeroConfig]]
 */
class MemoryLevelZeroConfigBuilder {
  private var logSize: Int = _
  private var appliedFunctionsLogSize: Int = _
  private var clearAppliedFunctionsOnBoot: Boolean = _
  private var acceleration: LevelZeroMeter => Accelerator = _
  private var optimiseWrites: OptimiseWrites = _
  private var atomic: Atomic = _
}

object MemoryLevelZeroConfigBuilder {

  class Step0(builder: MemoryLevelZeroConfigBuilder) {
    def logSize(logSize: Int) = {
      builder.logSize = logSize
      new Step1(builder)
    }
  }

  class Step1(builder: MemoryLevelZeroConfigBuilder) {
    def appliedFunctionsLogSize(logSize: Int) = {
      builder.appliedFunctionsLogSize = logSize
      new Step2(builder)
    }
  }

  class Step2(builder: MemoryLevelZeroConfigBuilder) {
    def clearAppliedFunctionsOnBoot(clear: Boolean) = {
      builder.clearAppliedFunctionsOnBoot = clear
      new Step4(builder)
    }
  }

  class Step4(builder: MemoryLevelZeroConfigBuilder) {
    def acceleration(acceleration: JavaFunction[LevelZeroMeter, Accelerator]) = {
      builder.acceleration = acceleration.apply
      new Step5(builder)
    }
  }

  class Step5(builder: MemoryLevelZeroConfigBuilder) {
    def acceleration(optimiseWrites: OptimiseWrites) = {
      builder.optimiseWrites = optimiseWrites
      new Step6(builder)
    }
  }

  class Step6(builder: MemoryLevelZeroConfigBuilder) {
    def atomic(atomic: Atomic) = {
      builder.atomic = atomic
      new Step7(builder)
    }
  }

  class Step7(builder: MemoryLevelZeroConfigBuilder) {
    def throttle(throttle: JavaFunction[LevelZeroMeter, LevelZeroThrottle]) =
      ConfigWizard.withMemoryLevel0(
        logSize = builder.logSize,
        appliedFunctionsLogSize = builder.appliedFunctionsLogSize,
        clearAppliedFunctionsOnBoot = builder.clearAppliedFunctionsOnBoot,
        optimiseWrites = builder.optimiseWrites,
        atomic = builder.atomic,
        acceleration = builder.acceleration,
        throttle = throttle.apply
      )
  }

  def builder() = new Step0(new MemoryLevelZeroConfigBuilder())
}
