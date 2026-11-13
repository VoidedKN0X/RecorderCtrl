package com.vknxworks.recorderctrl.transcribe

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** What a whisper.cpp ggml model file says about itself in its first 48 bytes. */
data class ModelHeader(
    val vocab: Int,
    val audioCtx: Int,
    val audioState: Int,
    val audioHeads: Int,
    val audioLayers: Int,
    val textCtx: Int,
    val textState: Int,
    val textHeads: Int,
    val textLayers: Int,
    val mels: Int,
    val ftype: Int,
) {
    /** English-only models (".en") have one token fewer. */
    val multilingual: Boolean get() = vocab >= 51865

    val size: ModelSize
        get() = when (audioLayers) {
            4 -> ModelSize.TINY
            6 -> ModelSize.BASE
            12 -> ModelSize.SMALL
            24 -> ModelSize.MEDIUM
            32 -> if (textLayers < audioLayers) ModelSize.TURBO else ModelSize.LARGE
            else -> ModelSize.OTHER
        }

    /** The weight format: f32, f16, or a quantisation such as q5_1. */
    val format: String
        get() = when (ftype % 1000) {
            0 -> "f32"
            1 -> "f16"
            2 -> "q4_0"
            3 -> "q4_1"
            7 -> "q8_0"
            8 -> "q5_0"
            9 -> "q5_1"
            10 -> "q2_k"
            11 -> "q3_k"
            12 -> "q4_k"
            13 -> "q5_k"
            14 -> "q6_k"
            else -> "type ${ftype % 1000}"
        }
}

enum class ModelSize { TINY, BASE, SMALL, MEDIUM, LARGE, TURBO, OTHER }

/** Why a file isn't a model RecorderCtrl can use. */
enum class ModelProblem { TOO_SMALL, TOO_BIG, NOT_GGML, NOT_WHISPER }

sealed interface ModelCheck {
    data class Ok(val header: ModelHeader) : ModelCheck
    data class Bad(val problem: ModelProblem) : ModelCheck
}

/**
 * Checks a file before it's copied into the app: the ggml magic number, sane whisper hyper-parameters and a plausible
 * size. whisper.cpp itself checks the rest (tensor names and shapes) when it loads the model.
 */
object ModelHeaderCheck {
    /** 4-byte magic and 11 int32 hyper-parameters, little-endian. */
    const val HEADER_BYTES = 48
    const val GGML_MAGIC = 0x67676d6c

    /** The smallest real model (tiny, quantised) is about 31 MB; large-v3 in f32 is about 6 GB. */
    const val MIN_BYTES = 20L * 1024 * 1024
    const val MAX_BYTES = 7L * 1024 * 1024 * 1024

    private val STATES = setOf(384, 512, 768, 1024, 1280)

    fun check(header: ByteArray, fileSize: Long): ModelCheck {
        if (fileSize in 0 until MIN_BYTES) return ModelCheck.Bad(ModelProblem.TOO_SMALL)
        if (fileSize > MAX_BYTES) return ModelCheck.Bad(ModelProblem.TOO_BIG)
        if (header.size < HEADER_BYTES) return ModelCheck.Bad(if (fileSize >= 0) ModelProblem.NOT_GGML else ModelProblem.TOO_SMALL)
        val b = ByteBuffer.wrap(header, 0, HEADER_BYTES).order(ByteOrder.LITTLE_ENDIAN)
        if (b.int != GGML_MAGIC) return ModelCheck.Bad(ModelProblem.NOT_GGML)
        val h = ModelHeader(b.int, b.int, b.int, b.int, b.int, b.int, b.int, b.int, b.int, b.int, b.int)
        val ok = h.vocab in 51864..52000 &&
            h.audioCtx == 1500 &&
            h.textCtx == 448 &&
            h.audioState in STATES && h.textState == h.audioState &&
            h.audioHeads in 1..64 && h.textHeads in 1..64 &&
            h.audioLayers in 1..64 && h.textLayers in 1..64 &&
            (h.mels == 80 || h.mels == 128) &&
            h.ftype >= 0 && h.ftype % 1000 in 0..40
        return if (ok) ModelCheck.Ok(h) else ModelCheck.Bad(ModelProblem.NOT_WHISPER)
    }
}
