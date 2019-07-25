/*
 * Copyright (c) 2019 Simer Plaha (@simerplaha)
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

package swaydb.data.io

import java.io.FileNotFoundException
import java.nio.ReadOnlyBufferException
import java.nio.channels.{AsynchronousCloseException, ClosedChannelException, OverlappingFileLockException}
import java.nio.file.{NoSuchFileException, Path}

import swaydb.ErrorHandler
import swaydb.data.Reserve
import swaydb.data.slice.Slice

object Core {

  /**
    * Exception types for all known [[Error]]s that can occur. Each [[Error]] can be converted to
    * Exception which which can then be converted back to [[Error]].
    *
    * SwayDB's code itself does not use these exception it uses [[Error]] type. These types are handy when
    * converting an [[IO]] type to [[scala.util.Try]] by the client using [[toTry]].
    */
  object Exception {
    case class Busy(error: Error.Busy) extends Exception("Is busy")
    case class OpeningFile(file: Path, busy: Reserve[Unit]) extends Exception(s"Failed to open file $file")

    case class DecompressingIndex(busy: Reserve[Unit]) extends Exception("Failed to decompress index")
    case class DecompressionValues(busy: Reserve[Unit]) extends Exception("Failed to decompress values")
    case class ReservedValue(busy: Reserve[Unit]) extends Exception("Failed to fetch value")
    case class ReadingHeader(busy: Reserve[Unit]) extends Exception("Failed to read header")
    case class NullMappedByteBuffer(exception: Exception, busy: Reserve[Unit]) extends Exception(exception)
    case class BusyFuture(busy: Reserve[Unit]) extends Exception("Busy future")

    case object OverlappingPushSegment extends Exception("Contains overlapping busy Segments")
    case object NoSegmentsRemoved extends Exception("No Segments Removed")
    case object NotSentToNextLevel extends Exception("Not sent to next Level")
    case class ReceivedKeyValuesToMergeWithoutTargetSegment(keyValueCount: Int) extends Exception(s"Received key-values to merge without target Segment - keyValueCount: $keyValueCount")

    /**
      * [[functionID]] itself is not logged or printed to console since it may contain sensitive data but instead this Exception
      * with the [[functionID]] is returned to the client for reads and the exception's string message is only logged.
      *
      * @param functionID the id of the missing function.
      */
    case class FunctionNotFound(functionID: Slice[Byte]) extends Exception("Function not found for ID.")
    case class OverlappingFileLock(exception: OverlappingFileLockException) extends Exception("Failed to get directory lock.")
    object SegmentCorruptionException {
      def apply(message: String): SegmentCorruptionException =
        new SegmentCorruptionException(message, new Exception(message))
    }
    case class SegmentCorruptionException(message: String, cause: Throwable) extends Exception(message, cause)
    case class FailedToWriteAllBytes(written: Int, expected: Int, bytesSize: Int) extends Exception(s"Failed to write all bytes written: $written, expected : $expected, bytesSize: $bytesSize")
    case class CannotCopyInMemoryFiles(file: Path) extends Exception(s"Cannot copy in-memory files $file")
    case class SegmentFileMissing(path: Path) extends Exception(s"$path: Segment file missing.")
    case class InvalidKeyValueId(id: Int) extends Exception(s"Invalid keyValueId: $id.")

    case class InvalidDecompressorId(id: Int) extends Exception(s"Invalid decompressor id: $id")

  }

  sealed trait Error {
    def exception: Throwable
  }

  object Error {

    /**
      * Private Errors a restricted to DB's internals only. They should
      * not go out to the clients.
      */
    sealed trait Private extends Error
    sealed trait Initialisation extends Private
    sealed trait API extends Private

    object Private {
      implicit object ErrorHandler extends ErrorHandler[Error.Private] {
        override def toException(e: Error.Private): Throwable =
          e.exception

        override def fromException[F <: Error.Private](e: Throwable): F =
          Error(e).asInstanceOf[F]

        override def reserve(e: Error.Private): Option[Reserve[Unit]] =
          e match {
            case busy: Error.Busy =>
              Some(busy.reserve)

            case _: Error =>
              None
          }
      }
    }

    implicit object InitialisationErrorHandler extends ErrorHandler[Error.Initialisation] {
      override def toException(e: Error.Initialisation): Throwable =
        e.exception

      override def fromException[F <: Error.Initialisation](e: Throwable): F =
        Error(e) match {
          case initialisation: Error.Initialisation =>
            initialisation.asInstanceOf[F]

          case other: Error =>
            Error.Fatal(other.exception).asInstanceOf[F]
        }

      override def reserve(e: Error.Initialisation): Option[Reserve[Unit]] =
        None
    }

    implicit object APIErrorHandler extends ErrorHandler[Error.API] {
      override def toException(e: Error.API): Throwable =
        e.exception

      override def fromException[F <: Error.API](e: Throwable): F =
        Error(e) match {
          case initialisation: Error.API =>
            initialisation.asInstanceOf[F]

          case other: Error =>
            Error.Fatal(other.exception).asInstanceOf[F]
        }

      override def reserve(e: Error.API): Option[Reserve[Unit]] =
        None
    }

    def apply[T](exception: Throwable): Error =
      exception match {
        //known Exception that can occur which can return their typed Error version.
        case exception: Exception.Busy => exception.error
        case exception: Exception.OpeningFile => Error.OpeningFile(exception.file, exception.busy)
        case exception: Exception.DecompressingIndex => Error.DecompressingIndex(exception.busy)
        case exception: Exception.DecompressionValues => Error.DecompressingValues(exception.busy)
        case exception: Exception.ReservedValue => Error.ReservedValue(exception.busy)
        case exception: Exception.ReadingHeader => Error.ReadingHeader(exception.busy)
        case exception: Exception.ReceivedKeyValuesToMergeWithoutTargetSegment => Error.ReceivedKeyValuesToMergeWithoutTargetSegment(exception.keyValueCount)
        case exception: Exception.NullMappedByteBuffer => Error.NullMappedByteBuffer(exception)

        //the following Exceptions will occur when a file was being read but
        //it was closed or deleted when it was being read. There is no AtomicBoolean busy
        //associated with these exception and should simply be retried.
        case exception: NoSuchFileException => Error.NoSuchFile(exception)
        case exception: FileNotFoundException => Error.FileNotFound(exception)
        case exception: AsynchronousCloseException => Error.AsynchronousClose(exception)
        case exception: ClosedChannelException => Error.ClosedChannel(exception)

        case Exception.OverlappingPushSegment => Error.OverlappingPushSegment
        case Exception.NoSegmentsRemoved => Error.NoSegmentsRemoved
        case Exception.NotSentToNextLevel => Error.NotSentToNextLevel
        case exception: Exception.OverlappingFileLock => Core.Error.UnableToLockDirectory(exception)

        case exception: ReadOnlyBufferException => Error.ReadOnlyBuffer(exception)

        case exception: Exception.FailedToWriteAllBytes => Error.FailedToWriteAllBytes(exception.written, exception.expected, exception.bytesSize, exception)
        case exception: Exception.CannotCopyInMemoryFiles => Error.CannotCopyInMemoryFiles(exception.file, exception)
        case exception: Exception.SegmentFileMissing => Error.SegmentFileMissing(exception.path, exception)
        case exception: Exception.InvalidKeyValueId => Error.InvalidKeyValueId(exception.id, exception)

        case exception @ (_: ArrayIndexOutOfBoundsException | _: IndexOutOfBoundsException | _: IllegalArgumentException | _: NegativeArraySizeException) =>
          Error.Corruption("Please see the exception to find out the cause", exception)

        case exception: Exception.SegmentCorruptionException =>
          Error.Corruption(exception.message, exception)

        //Fatal error. This error is not expected to occur on a healthy database. This error would indicate corruption.
        //AppendixRepairer can be used to repair map files.
        case exception: Throwable => Error.Fatal(exception)
      }

    sealed trait Busy extends Private {
      def reserve: Reserve[Unit]
      def isFree: Boolean =
        !reserve.isBusy
    }

    case class OpeningFile(file: Path, reserve: Reserve[Unit]) extends Busy {
      override def exception: Exception.OpeningFile = Exception.OpeningFile(file, reserve)
    }

    object NoSuchFile {
      def apply(exception: NoSuchFileException) =
        new NoSuchFile(None, Some(exception))

      def apply(path: Path) =
        new NoSuchFile(Some(path), None)
    }

    case class NoSuchFile(path: Option[Path], exp: Option[NoSuchFileException]) extends Busy {
      override def reserve: Reserve[Unit] = Reserve()
      override def exception: Throwable = exp getOrElse {
        path match {
          case Some(path) =>
            new NoSuchFileException(path.toString)

          case None =>
            new NoSuchFileException("No path set.")
        }
      }
    }

    case class FileNotFound(exception: FileNotFoundException) extends Busy {
      override def reserve: Reserve[Unit] = Reserve()
    }

    case class AsynchronousClose(exception: AsynchronousCloseException) extends Busy {
      override def reserve: Reserve[Unit] = Reserve()
    }

    case class ClosedChannel(exception: ClosedChannelException) extends Busy {
      override def reserve: Reserve[Unit] = Reserve()
    }

    case class NullMappedByteBuffer(exception: Exception.NullMappedByteBuffer) extends Busy {
      override def reserve: Reserve[Unit] = Reserve()
    }

    case class DecompressingIndex(reserve: Reserve[Unit]) extends Busy {
      override def exception: Exception.DecompressingIndex = Exception.DecompressingIndex(reserve)
    }

    case class DecompressingValues(reserve: Reserve[Unit]) extends Busy {
      override def exception: Exception.DecompressionValues = Exception.DecompressionValues(reserve)
    }

    case class ReadingHeader(reserve: Reserve[Unit]) extends Busy {
      override def exception: Exception.ReadingHeader = Exception.ReadingHeader(reserve)
    }

    case class ReservedValue(reserve: Reserve[Unit]) extends Busy {
      override def exception: Exception.ReservedValue = Exception.ReservedValue(reserve)
    }

    case class BusyFuture(reserve: Reserve[Unit]) extends Busy {
      override def exception: Exception.BusyFuture = Exception.BusyFuture(reserve)
    }

    /**
      * This error can also be turned into Busy and LevelActor can use it to listen to when
      * there are no more overlapping Segments.
      */
    case object OverlappingPushSegment extends Private {
      override def exception: Throwable = Exception.OverlappingPushSegment
    }

    case object NoSegmentsRemoved extends Private {
      override def exception: Throwable = Exception.NoSegmentsRemoved
    }

    case object NotSentToNextLevel extends Private {
      override def exception: Throwable = Exception.NotSentToNextLevel
    }

    case class ReceivedKeyValuesToMergeWithoutTargetSegment(keyValueCount: Int) extends Private {
      override def exception: Exception.ReceivedKeyValuesToMergeWithoutTargetSegment =
        Exception.ReceivedKeyValuesToMergeWithoutTargetSegment(keyValueCount)
    }

    case class ReadOnlyBuffer(exception: ReadOnlyBufferException) extends Private

    case class FunctionNotFound(functionID: Slice[Byte]) extends Error.API {
      override def exception: Throwable = Core.Exception.FunctionNotFound(functionID)
    }

    case class UnableToLockDirectory(exception: Core.Exception.OverlappingFileLock) extends Error.Initialisation
    case class Corruption(message: String, exception: Throwable) extends Error.API with Error.Initialisation
    case class SegmentFileMissing(path: Path, exception: Throwable) extends Error.Initialisation

    case class FailedToWriteAllBytes(written: Int, expected: Int, bytesSize: Int, exception: Throwable) extends Error.Private
    case class CannotCopyInMemoryFiles(file: Path, exception: Throwable) extends Error.Private
    case class InvalidKeyValueId(id: Int, exception: Throwable) extends Error.Private

    /**
      * Error that are not known and indicate something unexpected went wrong like a file corruption.
      *
      * Pre-cautions are implemented in place to even recover from these failures using tools like AppendixRepairer.
      * This Error is not expected to occur on healthy databases.
      */
    object Fatal {
      def apply(message: String): Fatal =
        new Fatal(new Exception(message))
    }

    case class Fatal(exception: Throwable) extends Error.API with Error.Initialisation
  }
}