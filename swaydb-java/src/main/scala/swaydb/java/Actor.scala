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

package swaydb.java

import swaydb.ActorConfig.QueueOrder
import swaydb.Bag
import swaydb.java.data.TriFunctionVoid
import swaydb.utils.Java.JavaFunction

import java.util.concurrent.{CompletionStage, ExecutorService}
import java.util.function.{BiConsumer, Consumer}
import java.util.{Comparator, TimerTask, UUID}
import scala.compat.java8.DurationConverters._
import scala.compat.java8.FutureConverters._
import scala.concurrent.ExecutionContext

object Actor {

  final class TerminatedActor extends Throwable

  sealed trait ActorBase[T, S] {
    implicit val bag = Bag.future(asScala.executionContext)

    def asScala: swaydb.ActorRef[T, S]

    def send(message: T): Unit =
      asScala.send(message)

    def ask[R](message: JavaFunction[Actor.Ref[R, Void], T]): CompletionStage[R] =
      asScala.ask[R, scala.concurrent.Future] {
        actor: swaydb.ActorRef[R, Unit] =>
          message.apply(new Actor.Ref[R, Void](actor.asInstanceOf[swaydb.ActorRef[R, Void]]))
      }.toJava

    /**
     * Sends a message to this actor with delay
     */
    def send(message: T, delay: java.time.Duration): TimerTask =
      asScala.send(message, delay.toScala)

    def ask[R](message: JavaFunction[Actor.Ref[R, Void], T], delay: java.time.Duration): swaydb.Actor.Task[R, CompletionStage] = {
      val javaFuture =
        asScala.ask[R, scala.concurrent.Future](
          message =
            (actor: swaydb.ActorRef[R, Unit]) =>
              message.apply(new Actor.Ref[R, Void](actor.asInstanceOf[swaydb.ActorRef[R, Void]])),
          delay =
            delay.toScala
        )(bag)

      new swaydb.Actor.Task(javaFuture.task.toJava, javaFuture.timer)
    }

    def totalWeight: Long =
      asScala.totalWeight

    def messageCount: Int =
      asScala.messageCount

    def hasMessages: Boolean =
      asScala.hasMessages

    def terminate(): Unit =
      asScala.terminate()

    def isTerminated: Boolean =
      asScala.isTerminated

    def clear(): Unit =
      asScala.clear()

    def terminateAndClear(): Unit =
      asScala.terminateAndClear()
  }

  final class Boot[T, S](val asScala: swaydb.ActorHooks[T, S]) {
    def recover[M <: T](execution: TriFunctionVoid[M, Throwable, Instance[T, S]]): Boot[T, S] = {
      val actorRefWithRecovery =
        asScala.recover[M, Throwable] {
          case (message, io, actor) =>
            val throwable: Throwable =
              io match {
                case swaydb.IO.Right(_) =>
                  new TerminatedActor()

                case swaydb.IO.Left(value) =>
                  value
              }
            execution.apply(message, throwable, new Instance(actor))
        }

      new Boot(actorRefWithRecovery)
    }

    def terminateAndRecover[M <: T](execution: TriFunctionVoid[M, Throwable, Instance[T, S]]): Boot[T, S] = {
      val actorRefWithRecovery =
        asScala.recover[M, Throwable] {
          case (message, io, actor) =>
            val throwable: Throwable =
              io match {
                case swaydb.IO.Right(_) =>
                  new TerminatedActor()

                case swaydb.IO.Left(value) =>
                  value
              }
            execution.apply(message, throwable, new Instance(actor))
        }

      new Boot(actorRefWithRecovery)
    }

    def start(): Actor.Ref[T, S] =
      new Actor.Ref(asScala.start())
  }

  final class Ref[T, S](override val asScala: swaydb.ActorRef[T, S]) extends ActorBase[T, S]

  final class Instance[T, S](val asScala: swaydb.Actor[T, S]) extends ActorBase[T, S] {
    def state(): S = asScala.state
  }

  def fifo[T](consumer: Consumer[T]): Actor.Boot[T, Void] = {
    def scalaExecution(message: T, actor: swaydb.Actor[T, Void]) =
      consumer.accept(message)

    val scalaActorRef =
      swaydb.Actor[T, Void](UUID.randomUUID().toString, null)(execution = scalaExecution)(
        ec = scala.concurrent.ExecutionContext.Implicits.global,
        queueOrder = QueueOrder.FIFO
      )

    new Actor.Boot(scalaActorRef)
  }

  def fifo[T](consumer: BiConsumer[T, Instance[T, Void]]): Actor.Boot[T, Void] = {
    def scalaExecution(message: T, actor: swaydb.Actor[T, Void]) =
      consumer.accept(message, new Instance(actor))

    val scalaActorRef =
      swaydb.Actor[T, Void](UUID.randomUUID().toString, null)(execution = scalaExecution)(
        ec = scala.concurrent.ExecutionContext.Implicits.global,
        queueOrder = QueueOrder.FIFO
      )

    new Actor.Boot(scalaActorRef)
  }

  def fifo[T](consumer: Consumer[T],
              executorService: ExecutorService): Actor.Boot[T, Void] =
    fifo[T, Void](
      initialState = null,
      consumer =
        new BiConsumer[T, Instance[T, Void]] {
          override def accept(t: T, u: Instance[T, Void]): Unit =
            consumer.accept(t)
        },
      executorService = executorService
    )

  def fifo[T](consumer: BiConsumer[T, Instance[T, Void]],
              executorService: ExecutorService): Actor.Boot[T, Void] =
    fifo[T, Void](null, consumer, executorService)

  def ordered[T](consumer: Consumer[T],
                 comparator: Comparator[T]): Actor.Boot[T, Void] = {
    def scalaExecution(message: T, actor: swaydb.Actor[T, Void]) =
      consumer.accept(message)

    val scalaActorRef =
      swaydb.Actor[T, Void](UUID.randomUUID().toString, null)(execution = scalaExecution)(
        ec = scala.concurrent.ExecutionContext.Implicits.global,
        queueOrder = QueueOrder.Ordered(Ordering.comparatorToOrdering(comparator))
      )

    new Actor.Boot(scalaActorRef)
  }

  def ordered[T](consumer: BiConsumer[T, Instance[T, Void]],
                 comparator: Comparator[T]): Actor.Boot[T, Void] = {
    def scalaExecution(message: T, actor: swaydb.Actor[T, Void]) =
      consumer.accept(message, new Instance(actor))

    val scalaActorRef =
      swaydb.Actor[T, Void](UUID.randomUUID().toString, null)(execution = scalaExecution)(
        ec = scala.concurrent.ExecutionContext.Implicits.global,
        queueOrder = QueueOrder.Ordered(Ordering.comparatorToOrdering(comparator))
      )

    new Actor.Boot(scalaActorRef)
  }

  def ordered[T](consumer: Consumer[T],
                 executorService: ExecutorService,
                 comparator: Comparator[T]): Actor.Boot[T, Void] =
    ordered[T, Void](
      initialState = null,
      consumer =
        new BiConsumer[T, Instance[T, Void]] {
          override def accept(t: T, u: Instance[T, Void]): Unit =
            consumer.accept(t)
        },
      executorService = executorService,
      comparator = comparator
    )

  def ordered[T](consumer: BiConsumer[T, Instance[T, Void]],
                 executorService: ExecutorService,
                 comparator: Comparator[T]): Actor.Boot[T, Void] =
    ordered[T, Void](null, consumer, executorService, comparator)

  def fifo[T, S](initialState: S,
                 consumer: Consumer[T]): Actor.Boot[T, S] =
    fifo[T, S](
      initialState = initialState,
      consumer =
        new BiConsumer[T, Instance[T, S]] {
          override def accept(t: T, u: Instance[T, S]): Unit =
            consumer.accept(t)
        }
    )

  def fifo[T, S](initialState: S,
                 consumer: BiConsumer[T, Instance[T, S]]): Actor.Boot[T, S] = {
    def scalaExecution(message: T, actor: swaydb.Actor[T, S]) =
      consumer.accept(message, new Instance(actor))

    val scalaActorRef =
      swaydb.Actor[T, S](UUID.randomUUID().toString, initialState)(execution = scalaExecution)(
        ec = scala.concurrent.ExecutionContext.Implicits.global,
        queueOrder = QueueOrder.FIFO
      )

    new Actor.Boot(scalaActorRef)
  }

  def fifo[T, S](initialState: S,
                 consumer: Consumer[T],
                 executorService: ExecutorService): Actor.Boot[T, S] =
    fifo[T, S](
      initialState = initialState,
      consumer =
        new BiConsumer[T, Instance[T, S]] {
          override def accept(t: T, u: Instance[T, S]): Unit =
            consumer.accept(t)
        },
      executorService = executorService
    )

  def fifo[T, S](initialState: S,
                 consumer: BiConsumer[T, Instance[T, S]],
                 executorService: ExecutorService): Actor.Boot[T, S] = {
    def scalaExecution(message: T, actor: swaydb.Actor[T, S]) =
      consumer.accept(message, new Instance(actor))

    val scalaActorRef =
      swaydb.Actor[T, S](UUID.randomUUID().toString, initialState)(execution = scalaExecution)(
        ec = ExecutionContext.fromExecutorService(executorService),
        queueOrder = QueueOrder.FIFO
      )

    new Actor.Boot(scalaActorRef)
  }

  def ordered[T, S](initialState: S,
                    consumer: Consumer[T],
                    executorService: ExecutorService,
                    comparator: Comparator[T]): Actor.Boot[T, S] = {
    ordered[T, S](
      initialState = initialState,
      consumer =
        new BiConsumer[T, Instance[T, S]] {
          override def accept(t: T, u: Instance[T, S]): Unit =
            consumer.accept(t)
        },
      executorService = executorService,
      comparator = comparator
    )
  }

  def ordered[T, S](initialState: S,
                    consumer: BiConsumer[T, Instance[T, S]],
                    executorService: ExecutorService,
                    comparator: Comparator[T]): Actor.Boot[T, S] = {
    def scalaExecution(message: T, actor: swaydb.Actor[T, S]) =
      consumer.accept(message, new Instance(actor))

    val scalaActorRef =
      swaydb.Actor[T, S](UUID.randomUUID().toString, initialState)(execution = scalaExecution)(
        ec = ExecutionContext.fromExecutorService(executorService),
        queueOrder = QueueOrder.Ordered(Ordering.comparatorToOrdering(comparator))
      )

    new Actor.Boot(scalaActorRef)
  }
}
