package com.aitts.v2.engine.audio

/**
 * Ensures arbitrary streaming PCM16 byte chunks are always emitted in 2-byte aligned frames,
 * preventing byte-shift static noise when HTTP/SSE chunks split mid-sample.
 */
class PcmFrameAligner {

    private var hasLeftover = false
    private var leftoverByte: Byte = 0

    fun push(chunk: ByteArray): ByteArray {
        if (chunk.isEmpty()) return ByteArray(0)
        val total = chunk.size + if (hasLeftover) 1 else 0
        val usable = total and 0x7FFFFFFE

        if (usable == 0) {
            leftoverByte = chunk[0]
            hasLeftover = true
            return ByteArray(0)
        }

        val out = ByteArray(usable)
        var outOffset = 0
        var inOffset = 0
        if (hasLeftover) {
            out[0] = leftoverByte
            outOffset = 1
            inOffset = 0
            val copyLen = usable - 1
            System.arraycopy(chunk, 0, out, 1, copyLen)
            inOffset = copyLen
        } else {
            System.arraycopy(chunk, 0, out, 0, usable)
            inOffset = usable
        }

        if (inOffset < chunk.size) {
            leftoverByte = chunk[inOffset]
            hasLeftover = true
        } else {
            hasLeftover = false
        }
        return out
    }

    fun reset() {
        hasLeftover = false
        leftoverByte = 0
    }
}
