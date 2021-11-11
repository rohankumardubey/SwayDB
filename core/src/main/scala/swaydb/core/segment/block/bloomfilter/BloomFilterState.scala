package swaydb.core.segment.block.bloomfilter

import swaydb.compression.CompressionInternal
import swaydb.data.config.UncompressedBlockInfo
import swaydb.data.slice.Slice

class BloomFilterState(val numberOfBits: Int,
                       val maxProbe: Int,
                       var compressibleBytes: Slice[Byte],
                       val cacheableBytes: Slice[Byte],
                       var header: Slice[Byte],
                       val compressions: UncompressedBlockInfo => Iterable[CompressionInternal]) {

  def blockSize: Int =
    header.size + compressibleBytes.size

  def blockBytes: Slice[Byte] =
    header ++ compressibleBytes

  def written =
    compressibleBytes.size

  override def hashCode(): Int =
    compressibleBytes.hashCode()
}