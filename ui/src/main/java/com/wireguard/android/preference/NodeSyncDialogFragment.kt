/*
 * Copyright © 2017-2025 WireGuard LLC. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.wireguard.android.preference

import android.app.Dialog
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioGroup
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.DialogFragment
import androidx.lifecycle.lifecycleScope
import com.wireguard.android.R
import com.wireguard.android.sync.GistSync
import com.wireguard.android.sync.GistSyncProvider
import com.wireguard.android.sync.GiteeNodeSyncService
import com.wireguard.android.sync.GiteeNodeSyncService.Kind
import com.wireguard.android.sync.GiteeNodeSyncService.Source
import com.wireguard.android.sync.GiteeNodeSyncService.SyncNode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Gitee node sync dialog: compares local peers against the peers stored in a
 * gist file, lets the user pick per-node direction, then applies the plan and
 * uploads the merged node file back.
 */
class NodeSyncDialogFragment : DialogFragment() {
    private data class Card(
        val itemId: String?,
        val enabled: CheckBox,
        val source: RadioGroup,
        val asInterface: CheckBox?,
    )

    private lateinit var sync: GistSyncProvider
    private lateinit var tokenInput: EditText
    private lateinit var gistIdInput: EditText
    private lateinit var statusText: TextView
    private lateinit var comparisonContainer: View
    private lateinit var fileButton: Button
    private lateinit var tunnelButton: Button
    private lateinit var itemsContainer: LinearLayout
    private val cardViews = ArrayList<Card>()
    private var comparison: GiteeNodeSyncService.NodeSyncComparison? = null
    private var selectedRemoteFileName: String? = null
    private var selectedLocalTunnelName: String? = null
    private var busy = false

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        sync = GistSyncProvider.gitee(requireContext())
        val view = LayoutInflater.from(requireContext()).inflate(R.layout.node_sync_dialog, null)
        tokenInput = view.findViewById(R.id.node_sync_token)
        gistIdInput = view.findViewById(R.id.node_sync_gist_id)
        statusText = view.findViewById(R.id.node_sync_status)
        comparisonContainer = view.findViewById(R.id.node_sync_comparison)
        fileButton = view.findViewById(R.id.node_sync_file)
        tunnelButton = view.findViewById(R.id.node_sync_tunnel)
        itemsContainer = view.findViewById(R.id.node_sync_items)

        val settings = sync.loadSettings()
        tokenInput.setText(settings.token)
        gistIdInput.setText(settings.gistId)

        view.findViewById<View>(R.id.node_sync_start).setOnClickListener { runAction { loadComparison() } }
        view.findViewById<View>(R.id.node_sync_execute).setOnClickListener { execute() }
        fileButton.setOnClickListener { openFilePicker() }
        tunnelButton.setOnClickListener { openTunnelPicker() }

        return AlertDialog.Builder(requireContext())
            .setTitle(R.string.gitee_node_sync)
            .setView(view)
            .setPositiveButton(android.R.string.ok) { _, _ -> saveSettings() }
            .setNegativeButton(android.R.string.cancel, null)
            .create()
    }

    private fun saveSettings() {
        sync.saveSettings(currentSettings())
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
                block(settings)
            } catch (e: Throwable) {
                statusText.text = GistSyncErrorMessages.message(requireContext(), e, false)
            } finally {
                busy = false
            }
        }
    }

    private suspend fun loadComparison() {
        val settings = currentSettings()
        if (settings.gistId.isEmpty()) {
            val gistId = withContext(Dispatchers.IO) {
                GiteeNodeSyncService.uploadInitial(requireContext(), settings)
            }
            gistIdInput.setText(gistId)
            statusText.setText(R.string.gitee_node_sync_initial_success)
            return
        }
        val result = withContext(Dispatchers.IO) {
            GiteeNodeSyncService.getNodeComparison(
                requireContext(), settings, selectedRemoteFileName, selectedLocalTunnelName
            )
        }
        renderComparison(result)
        statusText.text = ""
    }

    private fun renderComparison(result: GiteeNodeSyncService.NodeSyncComparison) {
        comparison = result
        selectedRemoteFileName = result.remoteFileName
        selectedLocalTunnelName = result.selectedLocalTunnelName
        comparisonContainer.visibility = View.VISIBLE
        fileButton.text = getString(R.string.gitee_node_sync_file) + ": " + result.remoteFileName
        tunnelButton.text = if (result.selectedLocalTunnelName.isNotEmpty())
            getString(R.string.gitee_node_sync_local_tunnel) + ": " + result.selectedLocalTunnelName
        else getString(R.string.gitee_node_sync_select_local_tunnel)
        itemsContainer.removeAllViews()
        cardViews.clear()
        for (item in result.items) addItemCard(item)
        if (result.interfaceNodes.isNotEmpty()) addInterfaceCard(result.interfaceNodes.size)
    }

    private fun addItemCard(item: GiteeNodeSyncService.NodeSyncItem) {
        val node = item.local?.node ?: item.remote ?: return
        val card = inflateCard(nodeLabel(node), itemDetail(item))
        card.enabled.isChecked = item.enabled
        if (item.kind == Kind.COMMON) {
            card.source.visibility = View.VISIBLE
            card.source.check(
                if (item.source == Source.SERVER) R.id.node_sync_item_source_server
                else R.id.node_sync_item_source_local
            )
        }
        val remote = item.remote
        val asInterface = card.asInterface
        if (item.kind == Kind.SERVER_ONLY && remote != null &&
            GiteeNodeSyncService.canUseNodeAsLocalInterface(remote) && asInterface != null) {
            asInterface.visibility = View.VISIBLE
        }
        cardViews.add(card.copy(itemId = item.itemId))
    }

    private fun addInterfaceCard(count: Int) {
        val card = inflateCard(getString(R.string.gitee_node_sync_interface_number, count),
            getString(R.string.gitee_node_sync_interface_address_hint))
        cardViews.add(card.copy(itemId = "__interface__"))
    }

    private fun inflateCard(title: String, detail: String): Card {
        val view = LayoutInflater.from(requireContext()).inflate(R.layout.node_sync_item, itemsContainer, false)
        view.findViewById<TextView>(R.id.node_sync_item_title).text = title
        view.findViewById<TextView>(R.id.node_sync_item_detail).text = detail
        itemsContainer.addView(view)
        return Card(
            itemId = null,
            enabled = view.findViewById(R.id.node_sync_item_enabled),
            source = view.findViewById(R.id.node_sync_item_source),
            asInterface = view.findViewById(R.id.node_sync_item_as_interface),
        )
    }

    private fun nodeLabel(node: SyncNode): String =
        node.name.ifBlank { node.allowedIPs.ifBlank { node.publicKey } }
            .substringBefore('@').ifBlank { node.publicKey }

    private fun itemDetail(item: GiteeNodeSyncService.NodeSyncItem): String {
        val kindLabel = when (item.kind) {
            Kind.COMMON ->
                if (item.same) getString(R.string.gitee_node_sync_same)
                else getString(R.string.gitee_node_sync_both)
            Kind.LOCAL_ONLY -> getString(R.string.gitee_node_sync_local_only)
            Kind.SERVER_ONLY -> getString(R.string.gitee_node_sync_server_only)
        }
        val node = item.local?.node ?: item.remote ?: return kindLabel
        return listOfNotNull(
            kindLabel,
            node.allowedIPs.ifBlank { null },
            node.endpoint.ifBlank { null },
        ).joinToString(" · ")
    }

    private fun collectSelections(): List<GiteeNodeSyncService.NodeSyncSelection> =
        cardViews.mapNotNull { card ->
            val itemId = card.itemId ?: return@mapNotNull null
            val source = if (card.source.visibility == View.VISIBLE &&
                card.source.checkedRadioButtonId == R.id.node_sync_item_source_server)
                Source.SERVER else Source.LOCAL
            val asInterface = card.asInterface?.let { it.visibility == View.VISIBLE && it.isChecked } ?: false
            GiteeNodeSyncService.NodeSyncSelection(itemId, card.enabled.isChecked, source, asInterface)
        }

    private fun execute() {
        val current = comparison ?: return
        if (current.localTunnelNames.size > 1 && current.selectedLocalTunnelName.isEmpty()) {
            statusText.setText(R.string.gitee_node_sync_local_tunnel_required)
            return
        }
        val plan = GiteeNodeSyncService.buildSyncPlan(current, collectSelections())
        runAction { settings ->
            val result = withContext(Dispatchers.IO) {
                GiteeNodeSyncService.applySyncPlan(requireContext(), settings, plan)
            }
            val lines = ArrayList<String>()
            lines.add(getString(R.string.gitee_node_sync_started))
            lines.add(getString(R.string.gitee_node_sync_uploaded, result.uploadedNodeCount))
            lines.add(getString(R.string.gitee_node_sync_updated, result.updatedLocalNodeCount))
            if (result.createdTunnelNames.isNotEmpty())
                lines.add(getString(R.string.gitee_node_sync_created, result.createdTunnelNames.joinToString(", ")))
            lines.add(getString(R.string.gitee_node_sync_completed))
            statusText.text = lines.joinToString("\n")
        }
    }

    private fun openFilePicker() {
        val current = comparison ?: return
        if (current.remoteFileNames.isEmpty()) return
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.gitee_node_sync_file)
            .setItems(current.remoteFileNames.toTypedArray()) { _, which ->
                selectedRemoteFileName = current.remoteFileNames[which]
                runAction { loadComparison() }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun openTunnelPicker() {
        val current = comparison ?: return
        if (current.localTunnelNames.isEmpty()) return
        val labels = ArrayList<String>()
        labels.add(getString(R.string.gitee_node_sync_select_local_tunnel))
        labels.addAll(current.localTunnelNames)
        val checked = current.localTunnelNames.indexOf(current.selectedLocalTunnelName) + 1
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.gitee_node_sync_local_tunnel)
            .setSingleChoiceItems(labels.toTypedArray(), checked) { dialog, which ->
                selectedLocalTunnelName = if (which == 0) "" else current.localTunnelNames[which - 1]
                dialog.dismiss()
                runAction { loadComparison() }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }
}
