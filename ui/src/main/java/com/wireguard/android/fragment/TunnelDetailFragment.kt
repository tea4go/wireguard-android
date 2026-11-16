/*
 * Copyright © 2017-2025 WireGuard LLC. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.wireguard.android.fragment

import android.content.ClipData
import android.content.ClipboardManager
import android.os.Bundle
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AlertDialog
import androidx.core.view.MenuProvider
import androidx.databinding.DataBindingUtil
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import com.wireguard.android.R
import com.wireguard.android.backend.Tunnel
import com.wireguard.android.databinding.TunnelDetailFragmentBinding
import com.wireguard.android.databinding.TunnelDetailPeerBinding
import com.wireguard.android.model.ObservableTunnel
import com.wireguard.android.util.QuantityFormatter
import com.wireguard.config.Config
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Fragment that shows details about a specific tunnel.
 */
class TunnelDetailFragment : BaseFragment(), MenuProvider {
    private var binding: TunnelDetailFragmentBinding? = null
    private var lastState = Tunnel.State.TOGGLE
    private var timerActive = true

    override fun onMenuItemSelected(menuItem: MenuItem): Boolean {
        val config = binding?.config ?: return false
        when (menuItem.itemId) {
            R.id.menu_action_copy_test_command -> {
                showCopyTestCommandDialog(config)
                return true
            }
            R.id.menu_action_test_tcp -> {
                testTcpForward(config)
                return true
            }
        }
        return false
    }

    override fun onCreateMenu(menu: Menu, menuInflater: MenuInflater) {
        menuInflater.inflate(R.menu.tunnel_detail, menu)
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        super.onCreateView(inflater, container, savedInstanceState)
        binding = TunnelDetailFragmentBinding.inflate(inflater, container, false)
        binding?.executePendingBindings()
        return binding?.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        requireActivity().addMenuProvider(this, viewLifecycleOwner, Lifecycle.State.RESUMED)
    }

    override fun onDestroyView() {
        binding = null
        super.onDestroyView()
    }

    override fun onResume() {
        super.onResume()
        timerActive = true
        lifecycleScope.launch {
            while (timerActive) {
                updateStats()
                delay(1000)
            }
        }
    }

    override fun onSelectedTunnelChanged(oldTunnel: ObservableTunnel?, newTunnel: ObservableTunnel?) {
        val binding = binding ?: return
        binding.tunnel = newTunnel
        if (newTunnel == null) {
            binding.config = null
        } else {
            lifecycleScope.launch {
                try {
                    binding.config = newTunnel.getConfigAsync()
                } catch (_: Throwable) {
                    binding.config = null
                }
            }
        }
        lastState = Tunnel.State.TOGGLE
        lifecycleScope.launch { updateStats() }
    }

    override fun onStop() {
        timerActive = false
        super.onStop()
    }

    override fun onViewStateRestored(savedInstanceState: Bundle?) {
        binding ?: return
        binding!!.fragment = this
        onSelectedTunnelChanged(null, selectedTunnel)
        super.onViewStateRestored(savedInstanceState)
    }

    private suspend fun updateStats() {
        val binding = binding ?: return
        val tunnel = binding.tunnel ?: return
        if (!isResumed) return
        val state = tunnel.state
        if (state != Tunnel.State.UP && lastState == state) return
        lastState = state
        try {
            val statistics = tunnel.getStatisticsAsync()
            for (i in 0 until binding.peersLayout.childCount) {
                val peer: TunnelDetailPeerBinding = DataBindingUtil.getBinding(binding.peersLayout.getChildAt(i))
                    ?: continue
                val publicKey = peer.item!!.publicKey
                val peerStats = statistics.peer(publicKey)
                if (peerStats == null || (peerStats.rxBytes == 0L && peerStats.txBytes == 0L)) {
                    peer.transferLabel.visibility = View.GONE
                    peer.transferText.visibility = View.GONE
                } else {
                    peer.transferText.text = getString(
                        R.string.transfer_rx_tx,
                        QuantityFormatter.formatBytes(peerStats.rxBytes),
                        QuantityFormatter.formatBytes(peerStats.txBytes)
                    )
                    peer.transferLabel.visibility = View.VISIBLE
                    peer.transferText.visibility = View.VISIBLE
                }
                if (peerStats == null || peerStats.latestHandshakeEpochMillis == 0L) {
                    peer.latestHandshakeLabel.visibility = View.GONE
                    peer.latestHandshakeText.visibility = View.GONE
                } else {
                    peer.latestHandshakeText.text = QuantityFormatter.formatEpochAgo(peerStats.latestHandshakeEpochMillis)
                    peer.latestHandshakeLabel.visibility = View.VISIBLE
                    peer.latestHandshakeText.visibility = View.VISIBLE
                }
            }
        } catch (e: Throwable) {
            for (i in 0 until binding.peersLayout.childCount) {
                val peer: TunnelDetailPeerBinding = DataBindingUtil.getBinding(binding.peersLayout.getChildAt(i))
                    ?: continue
                peer.transferLabel.visibility = View.GONE
                peer.transferText.visibility = View.GONE
                peer.latestHandshakeLabel.visibility = View.GONE
                peer.latestHandshakeText.visibility = View.GONE
            }
        }
    }

    private fun firstIpv4Address(config: Config): String =
        config.`interface`.addresses.map { it.address.hostAddress }.firstOrNull { !it.contains(':') } ?: ""

    private fun tcpListenIp(config: Config): String {
        val rule = config.`interface`.tcpForward ?: return ""
        return rule.listenIp ?: firstIpv4Address(config)
    }

    private fun showCopyTestCommandDialog(config: Config) {
        val rule = config.`interface`.tcpForward ?: return
        val ip = tcpListenIp(config)
        if (ip.isEmpty()) return
        val port = rule.listenPort
        val scripts = arrayOf(
            getString(R.string.copy_linux) to "nc -zvw 3 $ip $port",
            getString(R.string.copy_macos) to "nc -zv -G 3 $ip $port",
            getString(R.string.copy_windows) to
                "\$c = New-Object Net.Sockets.TcpClient; try { \$r = \$c.BeginConnect('$ip', $port, \$null, \$null); " +
                "if (\$r.AsyncWaitHandle.WaitOne(3000)) { \$c.EndConnect(\$r); Write-Host '通' } else { Write-Host '超时' } } " +
                "catch { Write-Host '不通' } finally { \$c.Close() }",
        )
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.copy_test_command)
            .setItems(scripts.map { it.first }.toTypedArray()) { _, which ->
                copyToClipboard(scripts[which].second)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun copyToClipboard(text: String) {
        val clipboard = requireContext().getSystemService(ClipboardManager::class.java) ?: return
        clipboard.setPrimaryClip(ClipData.newPlainText(getString(R.string.copy_test_command), text))
    }

    private fun testTcpForward(config: Config) {
        val rule = config.`interface`.tcpForward ?: return
        val target = "${rule.targetIp}:${rule.targetPort}"
        val dialog = AlertDialog.Builder(requireContext())
            .setTitle(R.string.test_tcp)
            .setMessage(R.string.tcp_testing)
            .setNegativeButton(android.R.string.cancel, null)
            .create()
        dialog.show()
        lifecycleScope.launch {
            val ok = withContext(Dispatchers.IO) {
                try {
                    Socket().use { socket -> socket.connect(InetSocketAddress(rule.targetIp, rule.targetPort), TCP_TEST_TIMEOUT_MS) }
                    true
                } catch (_: Throwable) {
                    false
                }
            }
            if (!isAdded) return@launch
            dialog.setMessage(getString(if (ok) R.string.tcp_test_ok else R.string.tcp_test_fail, target))
        }
    }

    companion object {
        private const val TCP_TEST_TIMEOUT_MS = 5000
    }
}
