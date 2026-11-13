package com.vknxworks.recorderctrl.transcribe

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class ModelHeaderTest {
    private val mb = 1024L * 1024

    /** A header as whisper.cpp's convert script writes it. */
    private fun header(
        magic: Int = 0x67676d6c,
        vocab: Int = 51865, audioCtx: Int = 1500, audioState: Int = 512, audioHeads: Int = 8, audioLayers: Int = 6,
        textCtx: Int = 448, textState: Int = 512, textHeads: Int = 8, textLayers: Int = 6, mels: Int = 80, ftype: Int = 1,
    ): ByteArray = ByteBuffer.allocate(48).order(ByteOrder.LITTLE_ENDIAN).apply {
        putInt(magic)
        listOf(vocab, audioCtx, audioState, audioHeads, audioLayers, textCtx, textState, textHeads, textLayers, mels, ftype).forEach { putInt(it) }
    }.array()

    private fun ok(bytes: ByteArray, size: Long = 148 * mb): ModelHeader {
        val r = ModelHeaderCheck.check(bytes, size)
        assertTrue("expected Ok, got $r", r is ModelCheck.Ok)
        return (r as ModelCheck.Ok).header
    }

    private fun bad(bytes: ByteArray, size: Long = 148 * mb): ModelProblem {
        val r = ModelHeaderCheck.check(bytes, size)
        assertTrue("expected Bad, got $r", r is ModelCheck.Bad)
        return (r as ModelCheck.Bad).problem
    }

    @Test
    fun acceptsBase() {
        val h = ok(header())
        assertEquals(ModelSize.BASE, h.size)
        assertTrue(h.multilingual)
        assertEquals("f16", h.format)
    }

    @Test
    fun recognisesSizesAndVariants() {
        assertEquals(ModelSize.TINY, ok(header(audioState = 384, audioHeads = 6, audioLayers = 4, textState = 384, textHeads = 6, textLayers = 4), 75 * mb).size)
        assertEquals(ModelSize.SMALL, ok(header(audioState = 768, audioHeads = 12, audioLayers = 12, textState = 768, textHeads = 12, textLayers = 12)).size)
        assertEquals(ModelSize.MEDIUM, ok(header(audioState = 1024, audioHeads = 16, audioLayers = 24, textState = 1024, textHeads = 16, textLayers = 24)).size)
        val large = ok(header(vocab = 51866, audioState = 1280, audioHeads = 20, audioLayers = 32, textState = 1280, textHeads = 20, textLayers = 32, mels = 128), 3000 * mb)
        assertEquals(ModelSize.LARGE, large.size)
        val turbo = ok(header(vocab = 51866, audioState = 1280, audioHeads = 20, audioLayers = 32, textState = 1280, textHeads = 20, textLayers = 4, mels = 128), 1600 * mb)
        assertEquals(ModelSize.TURBO, turbo.size)
    }

    @Test
    fun englishOnlyModel() {
        assertFalse(ok(header(vocab = 51864)).multilingual)
    }

    @Test
    fun quantisedFormats() {
        // Quantised files store ftype + 1000 * quantisation version.
        assertEquals("q5_1", ok(header(ftype = 2009), 57 * mb).format)
        assertEquals("q8_0", ok(header(ftype = 2007), 80 * mb).format)
        assertEquals("q5_0", ok(header(ftype = 8), 60 * mb).format)
    }

    @Test
    fun rejectsWrongMagic() {
        assertEquals(ModelProblem.NOT_GGML, bad(header(magic = 0x46554747))) // "GGUF"
        assertEquals(ModelProblem.NOT_GGML, bad(header(magic = 0x6c6d6767))) // byte-swapped
        assertEquals(ModelProblem.NOT_GGML, bad("PK\u0003\u0004 a zip file, not a model at all......".toByteArray().copyOf(48)))
    }

    @Test
    fun rejectsOtherGgmlModels() {
        // A ggml LLaMA-style file: right magic, wrong hyper-parameters.
        assertEquals(ModelProblem.NOT_WHISPER, bad(header(vocab = 32000, audioCtx = 4096)))
        assertEquals(ModelProblem.NOT_WHISPER, bad(header(mels = 64)))
        assertEquals(ModelProblem.NOT_WHISPER, bad(header(textCtx = 2048)))
        assertEquals(ModelProblem.NOT_WHISPER, bad(header(audioState = 500, textState = 500)))
        assertEquals(ModelProblem.NOT_WHISPER, bad(header(textState = 768)))
        assertEquals(ModelProblem.NOT_WHISPER, bad(header(audioLayers = 0)))
        assertEquals(ModelProblem.NOT_WHISPER, bad(header(ftype = -1)))
    }

    @Test
    fun checksSize() {
        assertEquals(ModelProblem.TOO_SMALL, bad(header(), 1 * mb))
        assertEquals(ModelProblem.TOO_SMALL, bad(header(), 0))
        assertEquals(ModelProblem.TOO_BIG, bad(header(), 8L * 1024 * mb))
        // Unknown size (-1) is checked again after copying.
        ok(header(), -1)
    }

    @Test
    fun rejectsTruncatedHeader() {
        assertEquals(ModelProblem.NOT_GGML, bad(header().copyOf(20)))
        assertEquals(ModelProblem.NOT_GGML, bad(ByteArray(0)))
    }
}
