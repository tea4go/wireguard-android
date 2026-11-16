/*
 * Copyright © 2017-2025 WireGuard LLC. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.wireguard.android.sync

import android.content.Context
import com.wireguard.android.Application
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.Date

/**
 * Manual, user-initiated synchronization through a private Gitee or GitHub Gist.
 * Both providers share the snapshot format implemented in [GistSync].
 */
class GistSyncProvider(
    private val context: Context,
    private val apiBase: String,
    private val preferencesName: String,
) {
    fun loadSettings(): GistSync.SyncSettings {
        val prefs = context.getSharedPreferences(preferencesName, Context.MODE_PRIVATE)
        return GistSync.SyncSettings(
            token = prefs.getString(KEY_TOKEN, "").orEmpty(),
            gistId = prefs.getString(KEY_GIST_ID, "").orEmpty(),
        )
    }

    fun saveSettings(settings: GistSync.SyncSettings) {
        context.getSharedPreferences(preferencesName, Context.MODE_PRIVATE).edit()
            .putString(KEY_TOKEN, settings.token.trim())
            .putString(KEY_GIST_ID, settings.gistId.trim())
            .apply()
    }

    /** Uploads all local tunnels as a new snapshot file inside the configured Gist. */
    fun upload(settings: GistSync.SyncSettings): GistSync.SyncResult {
        val token = settings.token.trim()
        if (token.isEmpty()) throw GistSync.SyncException("TOKEN_REQUIRED")
        val tunnels = GistSync.readLocalTunnels(context)
        if (tunnels.isEmpty()) throw GistSync.SyncException("NO_TUNNELS")

        val syncContent = GistSync.formatSnapshot(tunnels)
        val fileName = GistSync.backupFileName(Date(), GistSync.utf8ByteLength(syncContent))
        val body = buildString {
            append("{\"description\":").append(GistSync.quote(GistSync.snippetName(context)))
            append(",\"public\":false,\"files\":{")
            append(GistSync.quote(fileName))
            append(":{\"content\":").append(GistSync.quote(syncContent)).append("}}}")
        }

        val currentId = settings.gistId.trim()
        val method = if (currentId.isEmpty()) "POST" else "PATCH"
        val url = if (currentId.isEmpty()) apiBase else "$apiBase/${urlEncode(currentId)}"
        val gist = parseGist(request(url, method, token, body))
        if (gist.id.isEmpty()) throw GistSync.SyncException("INVALID_RESPONSE")

        saveSettings(GistSync.SyncSettings(token, gist.id))
        return GistSync.SyncResult(gist.id, tunnels.size)
    }

    fun getSnippetInfo(settings: GistSync.SyncSettings): GistSync.SnippetInfo {
        val gist = getGist(settings)
        val info = GistSync.parseSnippetName(gist.description)
        return info.copy(versions = GistSync.getBackupVersions(gist.files))
    }

    /**
     * Downloads a snapshot (newest by default) and writes matching local `.conf` files.
     * The whole snapshot is validated before any file is written.
     */
    fun download(settings: GistSync.SyncSettings, fileName: String? = null): GistSync.SyncResult {
        val gist = getGist(settings)
        val file = if (fileName != null) gist.files[fileName] else GistSync.selectSyncFile(gist.files)
        val content = file?.content
        if (content.isNullOrEmpty()) throw GistSync.SyncException("REMOTE_NO_CONFIGS")

        val tunnels = GistSync.parseSnapshot(content)
        tunnels.forEach { GistSync.validateTunnel(it) }
        tunnels.forEach { GistSync.writeTunnel(context, it) }
        return GistSync.SyncResult(settings.gistId.trim(), tunnels.size)
    }

    fun deleteVersion(settings: GistSync.SyncSettings, fileName: String) {
        val token = settings.token.trim()
        val gistId = settings.gistId.trim()
        if (token.isEmpty()) throw GistSync.SyncException("TOKEN_REQUIRED")
        if (gistId.isEmpty()) throw GistSync.SyncException("GIST_ID_REQUIRED")
        if (!GistSync.isBackupFileName(fileName)) throw GistSync.SyncException("INVALID_BACKUP_FILE")
        val body = "{\"files\":{${GistSync.quote(fileName)}:null}}"
        request("$apiBase/${urlEncode(gistId)}", "PATCH", token, body)
    }

    /** Creates a gist with a single named file from raw (non-snapshot) content. */
    fun createRawGist(token: String, description: String, fileName: String, content: String): String {
        if (token.isEmpty()) throw GistSync.SyncException("TOKEN_REQUIRED")
        val body = buildString {
            append("{\"description\":").append(GistSync.quote(description))
            append(",\"public\":false,\"files\":{")
            append(GistSync.quote(fileName))
            append(":{\"content\":").append(GistSync.quote(content)).append("}}}")
        }
        val gist = parseGist(request(apiBase, "POST", token, body))
        if (gist.id.isEmpty()) throw GistSync.SyncException("INVALID_RESPONSE")
        return gist.id
    }

    /** Creates or updates a single named file inside an existing gist. */
    fun patchFile(settings: GistSync.SyncSettings, fileName: String, content: String) {
        val token = settings.token.trim()
        val gistId = settings.gistId.trim()
        if (token.isEmpty()) throw GistSync.SyncException("TOKEN_REQUIRED")
        if (gistId.isEmpty()) throw GistSync.SyncException("GIST_ID_REQUIRED")
        val body = "{\"files\":{${GistSync.quote(fileName)}:{\"content\":${GistSync.quote(content)}}}}"
        request("$apiBase/${urlEncode(gistId)}", "PATCH", token, body)
    }

    /** Fetches the gist document for [settings]. */
    fun getDocument(settings: GistSync.SyncSettings): GistSync.GistDocument = getGist(settings)

    private fun getGist(settings: GistSync.SyncSettings): GistSync.GistDocument {
        val token = settings.token.trim()
        val gistId = settings.gistId.trim()
        if (token.isEmpty()) throw GistSync.SyncException("TOKEN_REQUIRED")
        if (gistId.isEmpty()) throw GistSync.SyncException("GIST_ID_REQUIRED")
        val gist = parseGist(request("$apiBase/${urlEncode(gistId)}", "GET", token, null))
        if (gist.id.isEmpty()) throw GistSync.SyncException("INVALID_RESPONSE")
        return gist
    }

    private fun parseGist(response: String): GistSync.GistDocument {
        val root = try {
            Json5.parse(response) as? Map<*, *>
        } catch (e: Exception) {
            throw GistSync.SyncException("INVALID_RESPONSE", e)
        } ?: throw GistSync.SyncException("INVALID_RESPONSE")
        val files = LinkedHashMap<String, GistSync.GistFile>()
        (root["files"] as? Map<*, *>)?.forEach { (name, value) ->
            val file = value as? Map<*, *> ?: return@forEach
            files[name.toString()] = GistSync.GistFile(
                content = file["content"] as? String,
                size = (file["size"] as? Number)?.toLong(),
            )
        }
        return GistSync.GistDocument(
            id = root["id"] as? String ?: "",
            description = root["description"] as? String ?: "",
            files = files,
        )
    }

    private fun request(url: String, method: String, token: String, body: String?): String {
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = method
            connection.connectTimeout = 15_000
            connection.readTimeout = 30_000
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("Content-Type", "application/json;charset=UTF-8")
            connection.setRequestProperty("User-Agent", Application.USER_AGENT)
            if (token.isNotEmpty()) connection.setRequestProperty("Authorization", "token $token")
            if (body != null) {
                connection.doOutput = true
                connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
            val code = connection.responseCode
            if (code !in 200..299) throw GistSync.SyncException("HTTP_$code")
            return connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } catch (e: GistSync.SyncException) {
            throw e
        } catch (e: IOException) {
            throw GistSync.SyncException("NETWORK_ERROR", e)
        } finally {
            connection.disconnect()
        }
    }

    private fun urlEncode(value: String): String =
        java.net.URLEncoder.encode(value, "UTF-8").replace("+", "%20")

    companion object {
        private const val KEY_TOKEN = "token"
        private const val KEY_GIST_ID = "gist_id"

        fun gitee(context: Context): GistSyncProvider =
            GistSyncProvider(context, "https://gitee.com/api/v5/gists", "gitee_sync")

        fun github(context: Context): GistSyncProvider =
            GistSyncProvider(context, "https://api.github.com/gists", "github_sync")
    }
}
