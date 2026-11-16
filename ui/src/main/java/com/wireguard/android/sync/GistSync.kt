/*
 * Copyright © 2017-2025 WireGuard LLC. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.wireguard.android.sync

import android.content.Context
import android.os.Build
import android.provider.Settings
import android.util.Log
import com.wireguard.config.Config
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Shared snapshot format and helpers for Gitee/GitHub Gist synchronization.
 * The snapshot format mirrors the HarmonyOS client: `{version: 1, updatedAt, tunnels: [{name, content}]}`
 * stored as one JSON5 file per backup named `<version>(yyyyMMdd-HHmmss)[<bytes>Byte].json5`.
 */
object GistSync {
    private const val TAG = "WireGuard/GistSync"
    private const val LEGACY_JSON5_FILE_NAME = "wireguard-harmony.json5"
    private const val LEGACY_SYNC_FILE_NAME = "wireguard-harmony.json"
    private val BACKUP_FILE_NAME_REGEX = Regex("""^\d+\.\d+\.\d+\(\d{8}-\d{6}\)\[\d+Byte]\.json5$""")
    private val VERSIONED_FILE_NAME_REGEX = Regex("""^\d+\.\d+\.\d+\(\d{8}-\d{6}\)(?:\[\d+Byte])?\.json5$""")
    private val LEGACY_VERSION_FILE_NAME_REGEX = Regex("""^\d+\.\d+\.\d+\.json5$""")

    /** A single tunnel snapshot entry. */
    data class SyncTunnel(val name: String, val content: String)

    data class BackupVersion(
        val fileName: String,
        val versionName: String,
        val backupTime: String,
        val fileSize: String,
    )

    data class SnippetInfo(
        val modelName: String,
        val phoneModel: String,
        val phoneName: String,
        val versions: List<BackupVersion>,
    )

    /** Settings persisted per provider. */
    data class SyncSettings(val token: String = "", val gistId: String = "")

    data class SyncResult(val gistId: String, val tunnelCount: Int)

    /** Thrown for user-facing sync failures; [reason] is a stable error code. */
    class SyncException(val reason: String, cause: Throwable? = null) : Exception(reason, cause)

    /** A gist file entry returned by the API. */
    data class GistFile(val content: String?, val size: Long?)

    /** A gist document returned by the API. */
    data class GistDocument(val id: String, val description: String, val files: Map<String, GistFile>)

    fun readLocalTunnels(context: Context): List<SyncTunnel> {
        val names = context.fileList().filter { it.endsWith(".conf") }.sorted()
        return names.mapNotNull { fileName ->
            val name = fileName.substring(0, fileName.length - ".conf".length)
            val file = File(context.filesDir, fileName)
            try {
                SyncTunnel(name, file.readText(StandardCharsets.UTF_8))
            } catch (e: IOException) {
                Log.w(TAG, "Unable to read local tunnel $name", e)
                null
            }
        }
    }

    fun writeTunnel(context: Context, tunnel: SyncTunnel) {
        File(context.filesDir, "${tunnel.name}.conf").writeText(tunnel.content, StandardCharsets.UTF_8)
    }

    /** Validates a remote tunnel entry; every snapshot entry is checked before any file is written. */
    fun validateTunnel(tunnel: SyncTunnel) {
        val name = tunnel.name
        if (name.isEmpty() || name.length > 128 || name.contains('/') || name.contains('\\'))
            throw SyncException("INVALID_REMOTE_CONFIG")
        try {
            Config.parse(ByteArrayInputStream(tunnel.content.toByteArray(StandardCharsets.UTF_8)))
        } catch (e: Exception) {
            throw SyncException("INVALID_REMOTE_CONFIG:$name", e)
        }
    }

    /** Gist description identifying the source device: `wg|品牌|型号|设备名`. */
    fun snippetName(context: Context): String {
        val modelName = sanitizeFileNamePart(Build.MANUFACTURER.ifEmpty { "unknown-model-name" })
        val model = sanitizeFileNamePart(Build.MODEL.ifEmpty { Build.MANUFACTURER.ifEmpty { "unknown-model" } })
        val deviceName = sanitizeFileNamePart(
            try {
                Settings.Global.getString(context.contentResolver, Settings.Global.DEVICE_NAME)?.trim()
            } catch (e: Exception) {
                null
            }?.ifEmpty { null } ?: modelName
        )
        return "wg|$modelName|$model|$deviceName"
    }

    fun parseSnippetName(snippetName: String): SnippetInfo {
        val parts = snippetName.split('|')
        if (parts.size == 4 && parts[0] == "wg") {
            return SnippetInfo(
                modelName = parts[1].ifEmpty { "-" },
                phoneModel = parts[2].ifEmpty { "-" },
                phoneName = parts[3].ifEmpty { "-" },
                versions = emptyList(),
            )
        }
        return SnippetInfo("-", "-", "-", emptyList())
    }

    fun backupFileName(backupTime: Date, fileSize: Int): String {
        val timestamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ENGLISH).format(backupTime)
        return "${appVersion()}($timestamp)[${fileSize}Byte].json5"
    }

    private fun appVersion(): String {
        val versionLabel = com.wireguard.android.BuildConfig.VERSION_NAME.removePrefix("v")
        return sanitizeFileNamePart(versionLabel.split("-B")[0].ifEmpty { "unknown-version" })
    }

    fun utf8ByteLength(content: String): Int = content.toByteArray(StandardCharsets.UTF_8).size

    fun isBackupFileName(fileName: String): Boolean =
        BACKUP_FILE_NAME_REGEX.matches(fileName) ||
            VERSIONED_FILE_NAME_REGEX.matches(fileName) ||
            LEGACY_VERSION_FILE_NAME_REGEX.matches(fileName) ||
            fileName == LEGACY_JSON5_FILE_NAME || fileName == LEGACY_SYNC_FILE_NAME

    fun getBackupVersions(files: Map<String, GistFile>): List<BackupVersion> {
        return files.keys
            .filter { isBackupFileName(it) }
            .sortedWith { left, right ->
                val leftTimestamp = getBackupTimestamp(left)
                val rightTimestamp = getBackupTimestamp(right)
                when {
                    leftTimestamp.isNotEmpty() && rightTimestamp.isNotEmpty() -> rightTimestamp.compareTo(leftTimestamp)
                    leftTimestamp.isNotEmpty() -> -1
                    rightTimestamp.isNotEmpty() -> 1
                    LEGACY_VERSION_FILE_NAME_REGEX.matches(left) && LEGACY_VERSION_FILE_NAME_REGEX.matches(right) ->
                        compareVersionFileNames(right, left)
                    else -> left.compareTo(right)
                }
            }
            .map { fileName ->
                BackupVersion(
                    fileName = fileName,
                    versionName = getBackupVersionName(fileName),
                    backupTime = getBackupTimeDisplay(fileName),
                    fileSize = "${fileSizeBytes(fileName, files[fileName])}B",
                )
            }
    }

    private fun fileSizeBytes(fileName: String, file: GistFile?): Long {
        val sizeMatch = Regex("""\[(\d+)Byte]\.json5$""").find(fileName)
        if (sizeMatch != null) return sizeMatch.groupValues[1].toLong()
        if (file?.size != null) return file.size
        return utf8ByteLength(file?.content ?: "").toLong()
    }

    private fun getBackupVersionName(fileName: String): String {
        val openParenthesis = fileName.lastIndexOf('(')
        if (openParenthesis > 0) return fileName.substring(0, openParenthesis)
        if (LEGACY_VERSION_FILE_NAME_REGEX.matches(fileName)) return fileName.removeSuffix(".json5")
        return fileName
    }

    private fun getBackupTimeDisplay(fileName: String): String {
        val timestamp = getBackupTimestamp(fileName)
        if (timestamp.length != 15) return ""
        return "${timestamp.substring(2, 4)}-${timestamp.substring(4, 6)}-" +
            "${timestamp.substring(6, 8)} ${timestamp.substring(9, 11)}:" +
            timestamp.substring(11, 13)
    }

    private fun getBackupTimestamp(fileName: String): String {
        val openParenthesis = fileName.lastIndexOf('(')
        val closeParenthesis = fileName.lastIndexOf(')')
        if (openParenthesis < 0 || closeParenthesis <= openParenthesis) return ""
        return fileName.substring(openParenthesis + 1, closeParenthesis)
    }

    private fun compareVersionFileNames(left: String, right: String): Int {
        val leftParts = left.removeSuffix(".json5").split('.')
        val rightParts = right.removeSuffix(".json5").split('.')
        for (index in 0 until 3) {
            val difference = leftParts[index].toInt() - rightParts[index].toInt()
            if (difference != 0) return difference
        }
        return 0
    }

    /** Picks the newest snapshot file, with fallbacks for the earlier file formats. */
    fun selectSyncFile(files: Map<String, GistFile>): GistFile? {
        val backupFileNames = files.keys
            .filter { VERSIONED_FILE_NAME_REGEX.matches(it) }
            .sortedByDescending { getBackupTimestamp(it) }
        if (backupFileNames.isNotEmpty()) return files[backupFileNames[0]]

        val versionFileNames = files.keys
            .filter { LEGACY_VERSION_FILE_NAME_REGEX.matches(it) }
            .sortedWith { left, right -> compareVersionFileNames(right, left) }
        if (versionFileNames.isNotEmpty()) return files[versionFileNames[0]]

        return files[LEGACY_JSON5_FILE_NAME] ?: files[LEGACY_SYNC_FILE_NAME]
    }

    private fun sanitizeFileNamePart(value: String): String {
        val sanitized = value.trim()
            .map { if (it.isISOControl() || it in "\\/:*?\"<>|") '-' else it }
            .joinToString("")
            .replace(Regex("""\s+"""), "-")
            .replace(Regex("-+"), "-")
            .replace(Regex("""^[.-]+|[.-]+$"""), "")
        return (sanitized.ifEmpty { "unknown" }).take(64)
    }

    /** Serializes a snapshot as strict JSON (valid JSON5), readable in a Gist viewer. */
    fun formatSnapshot(tunnels: List<SyncTunnel>): String {
        val updatedAt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.ENGLISH)
            .apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }
            .format(Date())
        val sb = StringBuilder()
        sb.append("{\n")
        sb.append("    version: 1,\n")
        sb.append("    updatedAt: ").append(quote(updatedAt)).append(",\n")
        sb.append("    tunnels: [\n")
        tunnels.forEachIndexed { index, tunnel ->
            sb.append("        {\n")
            sb.append("            name: ").append(quote(tunnel.name)).append(",\n")
            sb.append("            content: ").append(quote(tunnel.content)).append('\n')
            sb.append(if (index == tunnels.size - 1) "        }\n" else "        },\n")
        }
        sb.append("    ]\n")
        sb.append("}\n")
        return sb.toString()
    }

    /** Parses a snapshot produced by either client (strict JSON or JSON5). */
    fun parseSnapshot(content: String): List<SyncTunnel> {
        val root = try {
            Json5.parse(content)
        } catch (e: Exception) {
            throw SyncException("INVALID_REMOTE_CONFIG", e)
        }
        val map = root as? Map<*, *> ?: throw SyncException("INVALID_REMOTE_CONFIG")
        val version = map["version"]
        if (version != null && version.toString() != "1") throw SyncException("REMOTE_NO_CONFIGS")
        val tunnels = map["tunnels"] as? List<*> ?: throw SyncException("REMOTE_NO_CONFIGS")
        if (tunnels.isEmpty()) throw SyncException("REMOTE_NO_CONFIGS")
        return tunnels.map { item ->
            val tunnel = item as? Map<*, *> ?: throw SyncException("INVALID_REMOTE_CONFIG")
            val name = tunnel["name"] as? String ?: throw SyncException("INVALID_REMOTE_CONFIG")
            val text = tunnel["content"] as? String ?: throw SyncException("INVALID_REMOTE_CONFIG")
            SyncTunnel(name, text)
        }
    }

    fun quote(value: String): String {
        val sb = StringBuilder(value.length + 2)
        sb.append('"')
        for (c in value) {
            when (c) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                '\b' -> sb.append("\\b")
                '\u000C' -> sb.append("\\f")
                else -> if (c < ' ' || c == '\u2028' || c == '\u2029') {
                    sb.append(String.format(Locale.ENGLISH, "\\u%04x", c.code))
                } else {
                    sb.append(c)
                }
            }
        }
        sb.append('"')
        return sb.toString()
    }
}
