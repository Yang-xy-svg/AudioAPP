package com.example.audio_app.data

import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

data class Recording(
    val name: String,
    val sizeLabel: String? = null,
)

data class DownloadState(
    val received: Long = 0,
    val total: Long = -1,
)

object OpenMicProtocol {
    private val fileNamePattern = Regex(
        pattern = """^\d+-[A-Za-z0-9._-]*\.wav$""",
        option = RegexOption.IGNORE_CASE,
    )

    private val tableRowPattern = Regex(
        pattern = """href\s*=\s*["'][^"']*?/files/download\?name=([^"'&#]+)["'][^>]*>\s*([^<]+?)\s*</a>\s*</td>\s*<td[^>]*>\s*([^<]+?)\s*</td>""",
        options = setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
    )

    private val linkPattern = Regex(
        pattern = """href\s*=\s*["'][^"']*?/files/download\?name=([^"'&#]+)["'][^>]*>\s*([^<]+?)\s*</a>""",
        options = setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
    )

    fun normalizeBaseUrl(input: String): String {
        val trimmed = input.trim()
        require(trimmed.isNotEmpty()) { "请输入录音卡地址" }
        val withScheme = if ("://" in trimmed) trimmed else "http://$trimmed"
        val uri = URI(withScheme)
        require(uri.scheme.equals("http", ignoreCase = true) ||
            uri.scheme.equals("https", ignoreCase = true)) {
            "只支持 http:// 或 https:// 地址"
        }
        require(uri.userInfo == null) { "地址中不要包含用户名或密码" }
        require(!uri.host.isNullOrBlank()) { "地址格式不正确，例如 192.168.1.50 或 mic.local" }
        return "${uri.scheme.lowercase(Locale.ROOT)}://${uri.rawAuthority}"
    }

    fun parseFileList(html: String): List<Recording> {
        if (html.contains("No SD card mounted", ignoreCase = true)) {
            throw IllegalStateException("录音卡没有挂载 SD 卡")
        }
        if (!html.contains("/files/download", ignoreCase = true) &&
            !html.contains("Recordings", ignoreCase = true) &&
            !html.contains("No recordings yet", ignoreCase = true)
        ) {
            throw IllegalStateException("该地址不是 OpenMic 录音卡文件页面")
        }

        val rows = tableRowPattern.findAll(html).mapNotNull { match ->
            val encodedName = match.groupValues[1]
            val name = decodeName(encodedName)
            if (!isValidFileName(name)) {
                null
            } else {
                Recording(
                    name = name,
                    sizeLabel = match.groupValues[3].trim(),
                )
            }
        }.toList()

        val recordings = if (rows.isNotEmpty()) {
            rows
        } else {
            linkPattern.findAll(html).mapNotNull { match ->
                val name = decodeName(match.groupValues[1])
                if (isValidFileName(name)) Recording(name) else null
            }.toList()
        }

        return recordings.distinctBy { it.name.lowercase(Locale.ROOT) }
    }

    fun isValidFileName(name: String): Boolean = fileNamePattern.matches(name)

    fun displayName(name: String): String {
        val stem = name.substringBeforeLast('.')
        val parts = stem.split('-', limit = 2)
        val sequence = parts.firstOrNull()?.toIntOrNull()
        val timestamp = parts.getOrNull(1).orEmpty()
        val formattedTime = runCatching {
            val parser = SimpleDateFormat("yyyyMMdd'T'HHmmss", Locale.US).apply {
                isLenient = false
            }
            val date = parser.parse(timestamp)
            if (date == null) null else {
                SimpleDateFormat("yyyy-MM-dd  HH:mm:ss", Locale.CHINA).apply {
                    timeZone = TimeZone.getDefault()
                }.format(date)
            }
        }.getOrNull()

        return formattedTime ?: if (sequence != null) "录音 %04d".format(sequence) else stem
    }

    fun sequenceLabel(name: String): String? {
        return name.substringBefore('-').toIntOrNull()?.let { "#%04d".format(it) }
    }

    private fun decodeName(value: String): String {
        return runCatching {
            URLDecoder.decode(value.replace("&amp;", "&"), StandardCharsets.UTF_8.name())
        }.getOrDefault(value.replace("&amp;", "&"))
    }
}

fun formatByteCount(bytes: Long): String {
    if (bytes < 0) return "大小未知"
    if (bytes < 1024) return "$bytes B"
    val kib = bytes / 1024.0
    if (kib < 1024) return "%.1f KB".format(Locale.ROOT, kib)
    val mib = kib / 1024.0
    if (mib < 1024) return "%.1f MB".format(Locale.ROOT, mib)
    return "%.1f GB".format(Locale.ROOT, mib / 1024.0)
}
