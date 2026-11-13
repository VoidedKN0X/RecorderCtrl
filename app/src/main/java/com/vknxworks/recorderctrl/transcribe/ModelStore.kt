package com.vknxworks.recorderctrl.transcribe

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream

/** The speech-to-text model in RecorderCtrl's storage. */
data class ModelInfo(val file: File, val name: String, val sizeBytes: Long, val header: ModelHeader)

sealed interface ImportResult {
    data class Ok(val model: ModelInfo) : ImportResult
    data class Bad(val problem: ModelProblem) : ImportResult
    data object NoSpace : ImportResult
    data object Failed : ImportResult
}

/**
 * Holds one whisper.cpp model, imported by the user through the system file picker and copied into the app's private
 * storage. RecorderCtrl never downloads a model itself (it has no internet access).
 */
class ModelStore(private val context: Context) {
    private val dir: File get() = File(context.filesDir, "models").apply { mkdirs() }

    private val _model = MutableStateFlow<ModelInfo?>(null)
    val model: StateFlow<ModelInfo?> = _model

    /** 0..1 while a model is being copied in, null otherwise. */
    private val _importing = MutableStateFlow<Float?>(null)
    val importing: StateFlow<Float?> = _importing

    suspend fun load() = withContext(Dispatchers.IO) {
        // A copy that was cut off (the app was stopped) is useless.
        dir.listFiles().orEmpty().filter { it.name.endsWith(".part") }.forEach { it.delete() }
        _model.value = dir.listFiles().orEmpty().filter { it.isFile }.maxByOrNull { it.lastModified() }?.let { read(it) }
    }

    private fun read(f: File): ModelInfo? {
        val head = runCatching { f.inputStream().use { it.readUpTo(ModelHeaderCheck.HEADER_BYTES) } }.getOrNull() ?: return null
        val check = ModelHeaderCheck.check(head, f.length())
        return (check as? ModelCheck.Ok)?.let { ModelInfo(f, f.name, f.length(), it.header) }
    }

    /** Checks and copies the file at [uri]; replaces the current model when it's good. */
    suspend fun import(uri: Uri): ImportResult = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        val (displayName, declaredSize) = runCatching {
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
                if (!c.moveToFirst()) null to -1L
                else c.getString(0) to (if (c.isNull(1)) -1L else c.getLong(1))
            }
        }.getOrNull() ?: (null to -1L)

        // The header first, before copying hundreds of megabytes.
        val head = runCatching { resolver.openInputStream(uri)?.use { it.readUpTo(ModelHeaderCheck.HEADER_BYTES) } }.getOrNull()
            ?: return@withContext ImportResult.Failed
        val check = ModelHeaderCheck.check(head, declaredSize)
        if (check is ModelCheck.Bad) return@withContext ImportResult.Bad(check.problem)

        val current = _model.value
        val free = dir.usableSpace + (current?.sizeBytes ?: 0L)
        if (declaredSize > 0 && free < declaredSize + SPARE_BYTES) return@withContext ImportResult.NoSpace

        val name = safeFileName(displayName)
        val part = File(dir, "$name.part")
        _importing.value = 0f
        try {
            val copied = resolver.openInputStream(uri)?.use { input ->
                part.outputStream().use { out -> copy(input, out, declaredSize) }
            } ?: return@withContext ImportResult.Failed
            if (copied < 0) {
                part.delete()
                return@withContext ImportResult.Bad(ModelProblem.TOO_BIG)
            }
            // The size the provider declared may be missing; check the real one too.
            val again = ModelHeaderCheck.check(head, copied)
            if (again is ModelCheck.Bad) {
                part.delete()
                return@withContext ImportResult.Bad(again.problem)
            }
            if (declaredSize > 0 && copied != declaredSize) {
                part.delete()
                return@withContext ImportResult.Failed
            }
            // Only one model is kept: the new one replaces the old.
            dir.listFiles().orEmpty().filter { it != part }.forEach { it.delete() }
            val target = File(dir, name)
            if (!part.renameTo(target)) {
                part.delete()
                return@withContext ImportResult.Failed
            }
            val info = read(target) ?: run {
                target.delete()
                return@withContext ImportResult.Failed
            }
            _model.value = info
            ImportResult.Ok(info)
        } catch (e: java.io.IOException) {
            part.delete()
            if (dir.usableSpace < SPARE_BYTES) ImportResult.NoSpace else ImportResult.Failed
        } catch (e: SecurityException) {
            part.delete()
            ImportResult.Failed
        } finally {
            _importing.value = null
        }
    }

    /** Copies at most [ModelHeaderCheck.MAX_BYTES]; returns the bytes copied, or -1 when the file is bigger. */
    private fun copy(input: InputStream, out: java.io.OutputStream, total: Long): Long {
        val buf = ByteArray(1 shl 20)
        var copied = 0L
        var lastShown = 0L
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            copied += n
            if (copied > ModelHeaderCheck.MAX_BYTES) return -1
            out.write(buf, 0, n)
            if (total > 0 && copied - lastShown > 4 shl 20) {
                lastShown = copied
                _importing.value = (copied.toFloat() / total).coerceIn(0f, 1f)
            }
        }
        return copied
    }

    suspend fun delete() = withContext(Dispatchers.IO) {
        dir.listFiles().orEmpty().forEach { it.delete() }
        _model.value = null
    }

    companion object {
        /** Room left on the phone after a model is copied in. */
        private const val SPARE_BYTES = 100L * 1024 * 1024

        /** Where to find whisper.cpp models; opened in the browser, never downloaded by the app. */
        const val MODELS_PAGE = "https://github.com/ggml-org/whisper.cpp/blob/master/models/README.md"

        /** A plain file name: no folders, no odd characters. */
        fun safeFileName(name: String?): String {
            val base = name?.substringAfterLast('/')?.substringAfterLast('\\')
                ?.replace(Regex("[^A-Za-z0-9._-]"), "_")
                ?.removeSuffix(".part")
                ?.trim('.', '_')
                ?.take(80)
            return if (base.isNullOrBlank()) "model.bin" else base
        }
    }
}

/** Reads up to [n] bytes (fewer only at the end of the stream). */
internal fun InputStream.readUpTo(n: Int): ByteArray {
    val out = ByteArray(n)
    var got = 0
    while (got < n) {
        val r = read(out, got, n - got)
        if (r < 0) break
        got += r
    }
    return if (got == n) out else out.copyOf(got)
}
