package swaydb.core.segment.block.hashindex

import swaydb.core.compression.CompressionInternal
import swaydb.config.UncompressedBlockInfo
import swaydb.core.util.CRC32
import swaydb.slice.{Slice, SliceMut}

import scala.beans.BeanProperty

private[block] final class HashIndexBlockState(var hit: Int,
                                               var miss: Int,
                                               val format: HashIndexEntryFormat,
                                               val minimumNumberOfKeys: Int,
                                               val minimumNumberOfHits: Int,
                                               val writeAbleLargestValueSize: Int,
                                               @BeanProperty var minimumCRC: Long,
                                               val maxProbe: Int,
                                               var compressibleBytes: SliceMut[Byte],
                                               val cacheableBytes: Slice[Byte],
                                               var header: Slice[Byte],
                                               val compressions: UncompressedBlockInfo => Iterable[CompressionInternal]) {

  def blockSize: Int =
    header.size + compressibleBytes.size

  def hasMinimumHits: Boolean =
    hit >= minimumNumberOfHits

  //CRC can be -1 when HashIndex is not fully copied.
  def minimumCRCToWrite(): Long =
    if (minimumCRC == CRC32.disabledCRC)
      0
    else
      minimumCRC

  val hashMaxOffset: Int =
    compressibleBytes.allocatedSize - writeAbleLargestValueSize
}
