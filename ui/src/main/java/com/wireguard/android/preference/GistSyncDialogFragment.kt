/*
 * Copyright © 2017-2025 WireGuard LLC. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.wireguard.android.preference

import android.app.Dialog
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.DialogFragment
import androidx.lifecycle.lifecycleScope
import com.wireguard.android.R
import com.wireguard.android.sync.GistSync
import com.wireguard.android.sync.GistSyncProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Gitee/GitHub Gist snapshot sync dialog: token + gist id, upload, newest
 * download and a per-version picker with download/delete.
 */
class GistSyncDialogFragment : DialogFragment() {
    private var github = false
    private lateinit var sync: GistSyncProvider
    private lateinit var tokenInput: EditText
    private lateinit var gistIdInput: EditText
    private lateinit var statusText: TextView
    private var busy = false

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        github = requireArguments().getString(ARG_PROVIDER) == "github"
        sync = if (github) GistSyncProvider.github(requireContext()) else GistSyncProvider.gitee(requireContext())

        val view = LayoutInflater.from(requireContext()).inflate(R.layout.gist_sync_dialog, null)
        tokenInput = view.findViewById(R.id.gist_sync_token)
        gistIdInput = view.findViewById(R.id.gist_sync_gist_id)
        statusText = view.findViewById(R.id.gist_sync_status)

        val settings = sync.loadSettings()
        tokenInput.setText(settings.token)
        gistIdInput.setText(settings.gistId)

        statusText.text = getString(if (github) R.string.github_sync_description else R.string.gitee_sync_description)
        view.findViewById<View>(R.id.gist_sync_upload).setOnClickListener { runAction { upload(it) } }
        view.findViewById<View>(R.id.gist_sync_download).setOnClickListener { runAction { downloadLatest(it) } }
        view.findViewById<View>(R.id.gist_sync_versions).setOnClickListener { runAction { listVersions(it) } }

        return AlertDialog.Builder(requireContext())
            .setTitle(if (github) R.string.github_sync else R.string.gitee_sync)
            .setView(view)
            .setPositiveButton(android.R.string.ok) { _, _ -> saveSettings() }
            .setNegativeButton(android.R.string.cancel, null)
            .create()
    }

    private fun saveSettings() {
        sync.saveSettings(
            GistSync.SyncSettings(tokenInput.text.toString().trim(), gistIdInput.text.toString().trim())
        )
    }

    private fun currentSettings() =
        GistSync.SyncSettings(tokenInput.text.toString().trim(), gistIdInput.text.toString().trim())

    private fun runAction(block: suspend (GistSync.SyncSettings) -> Unit) {
        if (busy) return
        busy = true
        statusText.setText(R.string.gist_sync_working)
        lifecycleScope.launch {
            try {
                val settings = currentSettings()
                sync.saveSettings(settings)
                withContext(Dispatchers.IO) { block(settings) }
            } catch (e: Throwable) {
                statusText.text = GistSyncErrorMessages.message(requireContext(), e, github)
            } finally {
                busy = false
            }
        }
    }

    private suspend fun upload(settings: GistSync.SyncSettings) {
        val result = sync.upload(settings)
        gistIdInput.setText(result.gistId)
        statusText.text = getString(
            if (github) R.string.github_upload_success else R.string.gitee_upload_success,
            result.tunnelCount
        )
    }

    private suspend fun downloadLatest(settings: GistSync.SyncSettings) {
        val result = sync.download(settings)
        statusText.text = getString(
            if (github) R.string.github_download_success else R.string.gitee_download_success,
            result.tunnelCount
        )
    }

    private suspend fun listVersions(settings: GistSync.SyncSettings) {
        val info = sync.getSnippetInfo(settings)
        if (info.versions.isEmpty()) {
            statusText.setText(R.string.gist_sync_no_versions)
            return
        }
        withContext(Dispatchers.Main) {
            val labels = info.versions.map { "${it.versionName}  ${it.backupTime}" }.toTypedArray()
            AlertDialog.Builder(requireContext())
                .setTitle(R.string.gist_sync_versions)
                .setItems(labels) { _, index -> showVersionActions(info.versions[index]) }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }
    }

    private fun showVersionActions(version: GistSync.BackupVersion) {
        AlertDialog.Builder(requireContext())
            .setTitle("${version.versionName}  ${version.backupTime}")
            .setItems(arrayOf(getString(R.string.gist_sync_download_version), getString(R.string.delete))) { _, which ->
                if (which == 0) runAction { downloadVersion(it, version) } else runAction { deleteVersion(it, version) }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private suspend fun downloadVersion(settings: GistSync.SyncSettings, version: GistSync.BackupVersion) {
        val result = sync.download(settings, version.fileName)
        statusText.text = getString(
            if (github) R.string.github_download_success else R.string.gitee_download_success,
            result.tunnelCount
        )
    }

    private suspend fun deleteVersion(settings: GistSync.SyncSettings, version: GistSync.BackupVersion) {
        sync.deleteVersion(settings, version.fileName)
        statusText.setText(R.string.gist_sync_version_deleted)
    }

    companion object {
        private const val ARG_PROVIDER = "provider"

        fun newInstance(provider: String): GistSyncDialogFragment = GistSyncDialogFragment().apply {
            arguments = Bundle().apply { putString(ARG_PROVIDER, provider) }
        }
    }
}
