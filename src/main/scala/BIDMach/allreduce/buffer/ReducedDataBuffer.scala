package BIDMach.allreduce.buffer

import java.util

case class ReducedDataBuffer(maxBlockSize: Int,
                             minBlockSize: Int,
                             totalDataSize: Int,
                             peerSize: Int,
                             maxLag: Int,
                             completionThreshold: Float,
                             maxChunkSize: Int) extends AllReduceBuffer(maxBlockSize, peerSize, maxLag, maxChunkSize) {

  val minChunkRequired: Int = {
    val minNumChunks = getNumChunk(minBlockSize)
    val totalChunks = numChunks * (peerSize - 1) + minNumChunks
    (completionThreshold * totalChunks).toInt
  }

  private val countReduceFilled: Array[Array[Int]] = Array.ofDim[Int](maxLag, peerSize * numChunks)

  def store(data: Array[Float], row: Int, srcId: Int, chunkId: Int, count: Int) = {
    super.store(data, row, srcId, chunkId)
    countReduceFilled(timeIdx(row))(srcId * numChunks + chunkId) = count
  }

  def getWithCounts(row: Int): (Array[Float], Array[Int]) = {
    val output = temporalBuffer(timeIdx(row))
    val countOverPeerChunks = countReduceFilled(timeIdx(row))

    val dataOutput = Array.fill[Float](totalDataSize)(0.0f)
    val countOutput = Array.fill[Int](totalDataSize)(0)
    var transferred = 0
    var countTransferred = 0

    for (i <- 0 until peerSize) {
      val blockFromPeer = output(i)
      val blockSize = Math.min(totalDataSize - transferred, blockFromPeer.size)
      System.arraycopy(blockFromPeer, 0, dataOutput, transferred, blockSize)

      for (j <- 0 until numChunks) {
        val countChunkSize = {
          val countSize = Math.min(maxChunkSize, maxBlockSize - maxChunkSize * j)
          Math.min(totalDataSize - countTransferred, countSize)
        }
        // duplicate count from chunk to element level
        util.Arrays.fill(countOutput, countTransferred, countTransferred + countChunkSize, countOverPeerChunks(i * numChunks + j))
        countTransferred += countChunkSize
      }
      transferred += blockSize
    }

    (dataOutput, countOutput)
  }

  override def up(): Unit = {
    super.up()
    countReduceFilled(timeIdx(maxLag - 1)) = Array.fill(peerSize * numChunks)(0)
  }

  def reachCompletionThreshold(row: Int): Boolean = {
    var chunksCompleteReduce = 0
    for (i <- 0 until countFilled(row).length) {
      chunksCompleteReduce += countFilled(timeIdx(row))(i);
    }
    chunksCompleteReduce == minChunkRequired
  }
}

object ReducedDataBuffer {
  def empty = {
    ReducedDataBuffer(0, 0, 0, 0, 0, 0f, 1024)
  }
}