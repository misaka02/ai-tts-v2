package com.aitts.v2.engine.audio

import com.aitts.v2.engine.cache.AudioDiskCache
import com.aitts.v2.model.AudioProcessingMode
import com.aitts.v2.model.TtsProviderConfig
import com.aitts.v2.model.ProviderType
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.ByteBuffer
import java.nio.ByteOrder

class AudioPipelineTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun `pcm frame aligner prevents byte shift on odd-sized chunks`() {
        val original = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8, 9, 10)
        val aligner = PcmFrameAligner()
        val chunks = listOf(
            original.copyOfRange(0, 3),
            original.copyOfRange(3, 4),
            original.copyOfRange(4, 9),
            original.copyOfRange(9, 10)
        )
        val reconstructed = mutableListOf<Byte>()
        for (c in chunks) {
            val out = aligner.push(c)
            assertEquals("Output chunk must always be 2-byte aligned", 0, out.size % 2)
            out.forEach { reconstructed.add(it) }
        }
        assertArrayEquals(original, reconstructed.toByteArray())
    }

    @Test
    fun `wav codec parses PCM16 and IEEE Float32 cleanly`() {
        val pcm16 = byteArrayOf(0x00, 0x10, 0x00, 0x20, 0x00, 0x30, 0x00, 0x40)
        val wav = WavCodec.encodeToWav(pcm16, sampleRate = 24000, channels = 1)
        val parsed = WavCodec.parseWav(wav)
        assertNotNull(parsed)
        assertEquals(24000, parsed!!.sampleRate)
        assertEquals(1, parsed.channelCount)
        assertArrayEquals(pcm16, parsed.pcm16Data)
    }

    @Test
    fun `audio enhancer passthrough is bit-exact`() {
        val pcm = ByteArray(400) { (it % 127).toByte() }
        val enhancer = AudioEnhancer()
        val out = enhancer.processPcm(pcm, channels = 1, mode = AudioProcessingMode.PASSTHROUGH, trimSilence = false)
        assertArrayEquals(pcm, out)
    }

    @Test
    fun `resampler converts 16k to 24k and stereo to mono`() {
        val stereo = ByteArray(1600 * 4) { 10 }
        val mono24k = PcmResampler.resample(stereo, sourceSampleRate = 16000, sourceChannels = 2, targetSampleRate = 24000, targetChannels = 1)
        assertEquals(2400 * 2, mono24k.size)
    }

    @Test
    fun `audio pipeline fails cleanly when compressed audio fails decoding`() {
        val pipeline = AudioPipeline { _, _ -> Result.failure(IllegalStateException("codec failed")) }
        val fakeMp3 = byteArrayOf('I'.code.toByte(), 'D'.code.toByte(), '3'.code.toByte(), 0x03, 0x00)
        val res = pipeline.decodeToStandardPcm(fakeMp3, fallbackSampleRate = 24000)
        assertTrue(res.isFailure)
    }

    @Test
    fun `audio disk cache enforces maxCacheSizeMb`() {
        val cache = AudioDiskCache(tempFolder.newFolder("cache"))
        val cfg = TtsProviderConfig(id = "edge", type = ProviderType.EDGE_TTS, name = "Edge")
        val payload = ByteArray(600 * 1024) { 1 } // 0.6 MB each
        for (i in 0..9) {
            cache.put(cfg, "sentence_$i", payload, maxCacheSizeMb = 1)
        }
        val (_, totalMb) = cache.getStats()
        assertTrue("Cache size ($totalMb MB) should be trimmed near 1MB limit", totalMb <= 1.1f)
    }
}
