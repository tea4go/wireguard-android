/*
 * Copyright © 2017-2025 WireGuard LLC. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.wireguard.android.preference

import android.content.Context
import com.wireguard.android.R
import com.wireguard.android.sync.GistSync

/** Maps [GistSync.SyncException] error codes onto user-facing strings. */
object GistSyncErrorMessages {
    fun message(context: Context, e: Throwable, github: Boolean): String {
        val provider = if (github) context.getString(R.string.github) else context.getString(R.string.gitee)
        val reason = (e as? GistSync.SyncException)?.reason ?: ""
        return when {
            reason.startsWith("HTTP_401") || reason.startsWith("HTTP_403") ->
                context.getString(R.string.sync_auth_failed, provider)
            reason.startsWith("HTTP_404") -> context.getString(R.string.sync_gist_not_found)
            reason.startsWith("HTTP_") -> context.getString(R.string.sync_failed, provider)
            else -> when (reason.substringBefore(':')) {
                "TOKEN_REQUIRED" -> context.getString(R.string.sync_token_required, provider)
                "GIST_ID_REQUIRED" -> context.getString(R.string.sync_gist_id_required)
                "NO_TUNNELS" -> context.getString(R.string.sync_no_local_tunnels)
                "REMOTE_NO_CONFIGS" -> context.getString(R.string.sync_remote_empty)
                "INVALID_REMOTE_CONFIG" -> context.getString(R.string.sync_remote_invalid)
                "INVALID_REMOTE_NODE_CONFIG" -> context.getString(R.string.sync_node_remote_invalid)
                "INVALID_BACKUP_FILE" -> context.getString(R.string.sync_remote_invalid)
                "LOCAL_CONFIG_NOT_FOUND" -> context.getString(R.string.sync_local_config_missing)
                "NETWORK_ERROR" -> context.getString(R.string.sync_network_error)
                else -> context.getString(R.string.sync_failed, provider)
            }
        }
    }
}
