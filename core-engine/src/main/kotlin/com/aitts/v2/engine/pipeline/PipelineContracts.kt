package com.aitts.v2.engine.pipeline

/**
 * Pluggable contract for pure text normalization or filtering stages.
 */
fun interface TextProcessor {
    fun process(text: String): String
}

/**
 * Pluggable contract for lossless or gain/trimming PCM16 post-processing stages.
 */
interface AudioProcessor {
    fun process(
        pcm16: ByteArray,
        sampleRate: Int,
        gain: Float,
        trimSilence: Boolean
    ): ByteArray
}
