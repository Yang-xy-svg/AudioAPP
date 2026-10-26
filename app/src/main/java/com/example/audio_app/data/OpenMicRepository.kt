package com.example.audio_app.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

class OpenMicRepository(context: Context) {
    private val recordingsDir = File(context.filesDir, "recordings").apply { mkdirs() }

    suspend fun fetchRecordings(baseUrl: String): List<Recording> = withContext(Dispatchers.IO) {
        val connection = open("/files", baseUrl)
        try {
            val stream = responseStream(connection)
            stream.bufferedReader(Charsets.UTF_8).use { reader ->
                val html = readAtMost(reader, MAX_LISTING_BYTES)
                OpenMicProtocol.parseFileList(html)
            }
        } finally {
            connection.disconnect()
        }
    }

    suspend fun download(
        baseUrl: String,
        recording: Recording,
        onProgress: (received: Long, total: Long) -> Unit,
    ): File = withContext(Dispatchers.IO) {
        val encodedName = URLEncoder.encode(recording.name, Charsets.UTF_8.name())
        val connection = open("/files/download?name=$encodedName", baseUrl)
        val destination = fileFor(recording.name)
        val temporary = File(recordingsDir, "${recording.name}.part")
        try {
            val input = responseStream(connection)
            val total = connection.contentLengthLong
            input.use { source ->
                FileOutputStream(temporary).use { output ->
                    val buffer = ByteArray(BUFFER_SIZE)
                    var received = 0L
                    var header = ByteArray(0)
                    while (true) {
                        ensureActive()
                        val count = source.read(buffer)
                        if (count < 0) break
                        if (header.size < 12) {
                            header = header + buffer.copyOf(count).let {
                                it.copyOfRange(0, minOf(count, 12 - header.size))
                            }
                        }
                        output.write(buffer, 0, count)
                        received += count
                        onProgress(received, total)
                    }
                    output.fd.sync()
                    validateWavHeader(header)
                }
            }
            if (destination.exists() && !destination.delete()) {
                throw IOException("无法替换本地录音文件")
            }
            if (!temporary.renameTo(destination)) {
                throw IOException("无法保存本地录音文件")
            }
            destination
        } catch (error: Throwable) {
            temporary.delete()
            throw error
        } finally {
            connection.disconnect()
        }
    }

    fun fileFor(name: String): File = File(recordingsDir, name)

    fun isCached(name: String): Boolean = fileFor(name).let { it.isFile && it.length() > 44 }

    fun cachedRecordings(): List<Recording> {
        return recordingsDir.listFiles()
            ?.asSequence()
            ?.filter { it.isFile && OpenMicProtocol.isValidFileName(it.name) }
            ?.sortedWith(
                compareByDescending<File> { it.name.substringBefore('-').toIntOrNull() ?: -1 }
                    .thenByDescending { it.name },
            )
            ?.map { Recording(it.name, formatByteCount(it.length())) }
            ?.toList()
            .orEmpty()
    }

    fun deleteCached(name: String): Boolean {
        val file = fileFor(name)
        return !file.exists() || file.delete()
    }

    private fun open(path: String, baseUrl: String): HttpURLConnection {
        return (URL("$baseUrl$path").openConnection() as HttpURLConnection).apply {
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            requestMethod = "GET"
            instanceFollowRedirects = true
            setRequestProperty("Accept", "text/html,audio/wav,*/*")
            setRequestProperty("User-Agent", "OpenMic-Android/1.0")
        }
    }

    private fun responseStream(connection: HttpURLConnection): java.io.InputStream {
        val code = connection.responseCode
        if (code !in 200..299) {
            val detail = connection.errorStream
                ?.bufferedReader(Charsets.UTF_8)
                ?.use { readAtMost(it, 1024).trim() }
                .orEmpty()
            val message = when (code) {
                503 -> detail.ifBlank { "录音卡暂时不可用" }
                404 -> "设备上找不到这个录音"
                else -> "录音卡返回 HTTP $code"
            }
            throw IOException(message)
        }
        return connection.inputStream
    }

    private fun validateWavHeader(header: ByteArray) {
        if (header.size < 12 ||
            String(header, 0, 4, Charsets.US_ASCII) != "RIFF" ||
            String(header, 8, 4, Charsets.US_ASCII) != "WAVE"
        ) {
            throw IOException("设备返回的不是有效 WAV 录音")
        }
    }

    private fun readAtMost(reader: java.io.Reader, limit: Int): String {
        val result = StringBuilder()
        val buffer = CharArray(4096)
        while (result.length < limit) {
            val count = reader.read(buffer, 0, minOf(buffer.size, limit - result.length))
            if (count < 0) break
            result.append(buffer, 0, count)
        }
        return result.toString()
    }

    private companion object {
        const val CONNECT_TIMEOUT_MS = 6_000
        const val READ_TIMEOUT_MS = 30_000
        const val MAX_LISTING_BYTES = 512 * 1024
        const val BUFFER_SIZE = 64 * 1024
    }
}
