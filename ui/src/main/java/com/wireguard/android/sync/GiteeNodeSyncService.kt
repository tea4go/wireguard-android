/*
 * Copyright © 2017-2025 WireGuard LLC. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.wireguard.android.sync

import android.content.Context
import android.util.Log
import com.wireguard.android.configStore.FileConfigStore
import com.wireguard.config.BadConfigException
import com.wireguard.config.Config
import com.wireguard.config.InetEndpoint
import com.wireguard.config.InetNetwork
import com.wireguard.config.Interface
import com.wireguard.config.Peer
import com.wireguard.crypto.Key
import com.wireguard.crypto.KeyPair
import java.io.File

/**
 * Gitee 节点级同步，逐函数对齐 HarmonyOS 客户端的 GiteeNodeSyncService：
 * 本地每个隧道的 [Interface] 与所有 [Peer] 都被视为“节点”，按公钥比对
 * 本地与服务端节点列表（common / local-only / server-only），支持勾选式
 * 同步计划、把服务端节点并入指定隧道或新建隧道。
 */
object GiteeNodeSyncService {
    private const val TAG = "WireGuard/NodeSync"
    const val GITEE_NODE_SYNC_FILE = "default"
    private const val LEGACY_GITEE_NODE_SYNC_FILE = "wireguard-harmony-sync.conf"
    const val INVALID_REMOTE_NODE_CONFIG = "INVALID_REMOTE_NODE_CONFIG"

    data class SyncNode(
        val name: String,
        val publicKey: String,
        val privateKey: String,
        val allowedIPs: String,
        val endpoint: String,
        val listenPort: Int?,
        val persistentKeepalive: Int?,
        val preSharedKey: String,
    )

    data class LocalSyncNode(
        val node: SyncNode,
        val tunnelName: String,
        val peerIndex: Int,
    ) {
        val name get() = node.name
        val publicKey get() = node.publicKey
        val privateKey get() = node.privateKey
        val allowedIPs get() = node.allowedIPs
        val endpoint get() = node.endpoint
        val listenPort get() = node.listenPort
        val persistentKeepalive get() = node.persistentKeepalive
        val preSharedKey get() = node.preSharedKey
    }

    enum class Kind { COMMON, LOCAL_ONLY, SERVER_ONLY }

    enum class Source { LOCAL, SERVER }

    data class NodeSyncItem(
        val itemId: String,
        val kind: Kind,
        val same: Boolean,
        val local: LocalSyncNode?,
        val remote: SyncNode?,
        var enabled: Boolean = true,
        var source: Source = Source.LOCAL,
    )

    data class NodeSyncComparison(
        val localNodes: List<LocalSyncNode>,
        val remoteNodes: List<SyncNode>,
        val interfaceNodes: List<SyncNode>,
        val items: List<NodeSyncItem>,
        val remoteFileName: String,
        val remoteFileNames: List<String>,
        val localTunnelNames: List<String>,
        val selectedLocalTunnelName: String,
    )

    data class NodeSyncSelection(
        val itemId: String,
        val enabled: Boolean,
        val source: Source,
        val asInterface: Boolean,
    )

    data class LocalNodeUpdate(val local: LocalSyncNode, val remote: SyncNode)

    data class NodeSyncPlan(
        val remoteNodes: List<SyncNode>,
        val localUpdates: List<LocalNodeUpdate>,
        val remoteOnlyNodes: List<SyncNode>,
        val localInterfaceNodes: List<SyncNode>,
        val interfaceNodes: List<SyncNode>,
        val remoteFileName: String,
        val targetTunnelName: String,
    )

    data class NodeSyncResult(
        val createdTunnelNames: List<String>,
        val uploadedNodeCount: Int,
        val updatedLocalNodeCount: Int,
    )

    private data class InterfaceSelection(val name: String, val content: String)

    private data class LocalSnapshot(
        val tunnelNames: List<String>,
        val nodes: List<LocalSyncNode>,
        val interfaceNodes: List<LocalSyncNode>,
    )

    /** 与服务端节点文件相同的 `[Peer]` 块格式，逐节点解析。 */
    fun parseNodeFile(content: String): List<SyncNode> {
        val nodes = ArrayList<SyncNode>()
        var name = ""
        var publicKey = ""
        var privateKey = ""
        var allowedIPs = ""
        var endpoint = ""
        var listenPort: Int? = null
        var persistentKeepalive: Int? = null
        var preSharedKey = ""
        var inPeer = false
        var hasCurrent = false

        fun flush() {
            if (!hasCurrent) return
            val node = SyncNode(name, publicKey, privateKey, allowedIPs, endpoint, listenPort, persistentKeepalive, preSharedKey)
            validateSyncNode(node)
            nodes.add(node)
        }

        for (rawLine in content.replace("\r\n", "\n").split("\n")) {
            val line = rawLine.trim()
            if (line.isEmpty() || line.startsWith("#")) continue
            if (line.startsWith("[")) {
                if (line.lowercase() != "[peer]") throw GistSync.SyncException(INVALID_REMOTE_NODE_CONFIG)
                flush()
                name = ""
                publicKey = ""
                privateKey = ""
                allowedIPs = ""
                endpoint = ""
                listenPort = null
                persistentKeepalive = null
                preSharedKey = ""
                inPeer = true
                hasCurrent = true
                continue
            }
            if (!inPeer) throw GistSync.SyncException(INVALID_REMOTE_NODE_CONFIG)
            val separator = line.indexOf('=')
            if (separator <= 0) throw GistSync.SyncException(INVALID_REMOTE_NODE_CONFIG)
            val key = line.substring(0, separator).trim().lowercase()
            val value = line.substring(separator + 1).trim()
            when (key) {
                "name" -> name = value
                "publickey" -> publicKey = value
                "privatekey" -> privateKey = value
                "allowedips" -> allowedIPs = value
                "endpoint" -> endpoint = value
                "persistentkeepalive" -> {
                    persistentKeepalive = value.toIntOrNull()?.takeIf { it >= 0 }
                        ?: throw GistSync.SyncException(INVALID_REMOTE_NODE_CONFIG)
                }
                "presharedkey" -> preSharedKey = value
                else -> throw GistSync.SyncException(INVALID_REMOTE_NODE_CONFIG)
            }
        }
        flush()
        return nodes
    }

    fun formatNodeFile(nodes: List<SyncNode>): String {
        if (nodes.isEmpty()) return ""
        return nodes.joinToString("\n\n") { node ->
            validateSyncNode(node)
            val lines = mutableListOf("[Peer]", "Name = ${node.name}", "PublicKey = ${node.publicKey}")
            if (node.privateKey.isNotEmpty()) lines.add("PrivateKey = ${node.privateKey}")
            lines.add("AllowedIPs = ${node.allowedIPs}")
            if (node.endpoint.isNotEmpty()) lines.add("Endpoint = ${node.endpoint}")
            if (node.persistentKeepalive != null) lines.add("PersistentKeepalive = ${node.persistentKeepalive}")
            if (node.preSharedKey.isNotEmpty()) lines.add("PreSharedKey = ${node.preSharedKey}")
            lines.joinToString("\n")
        } + "\n"
    }

    fun validateSyncNode(node: SyncNode) {
        if (node.name.isBlank() || node.publicKey.isBlank() || node.allowedIPs.isBlank())
            throw GistSync.SyncException(INVALID_REMOTE_NODE_CONFIG)
        try {
            Key.fromBase64(node.publicKey.trim())
            if (node.privateKey.isNotBlank()) {
                val privateKey = Key.fromBase64(node.privateKey.trim())
                if (KeyPair(privateKey).publicKey.toBase64() != Key.fromBase64(node.publicKey.trim()).toBase64())
                    throw GistSync.SyncException(INVALID_REMOTE_NODE_CONFIG)
            }
            for (allowedIp in node.allowedIPs.split(","))
                InetNetwork.parse(allowedIp.trim())
            if (node.endpoint.isNotBlank()) InetEndpoint.parse(node.endpoint.trim())
            if (node.preSharedKey.isNotBlank()) Key.fromBase64(node.preSharedKey.trim())
        } catch (e: GistSync.SyncException) {
            throw e
        } catch (e: Exception) {
            throw GistSync.SyncException(INVALID_REMOTE_NODE_CONFIG, e)
        }
        if (node.persistentKeepalive != null && (node.persistentKeepalive < 0 || node.persistentKeepalive > 65535))
            throw GistSync.SyncException(INVALID_REMOTE_NODE_CONFIG)
    }

    fun buildSyncPlan(comparison: NodeSyncComparison, selections: List<NodeSyncSelection>): NodeSyncPlan {
        val selected = selections.associateBy { it.itemId }
        val remoteNodes = comparison.remoteNodes.map { it.copy() }.toMutableList()
        val localUpdates = ArrayList<LocalNodeUpdate>()
        val remoteOnlyNodes = ArrayList<SyncNode>()
        val localInterfaceNodes = ArrayList<SyncNode>()

        for (item in comparison.items) {
            val selection = selected[item.itemId]
            if (selection == null || !selection.enabled) continue
            if (item.kind == Kind.SERVER_ONLY && item.remote != null && selection.asInterface &&
                canUseNodeAsLocalInterface(item.remote)) {
                localInterfaceNodes.add(item.remote.copy())
            }
            when (item.kind) {
                Kind.COMMON -> {
                    val local = item.local ?: continue
                    val remote = item.remote ?: continue
                    if (selection.source == Source.LOCAL) replaceNode(remoteNodes, remote, local.node)
                    else localUpdates.add(LocalNodeUpdate(local, remote))
                }
                Kind.LOCAL_ONLY -> item.local?.let { addUniqueNode(remoteNodes, it.node) }
                Kind.SERVER_ONLY -> item.remote?.let { remoteOnlyNodes.add(it) }
            }
        }

        val interfaceSelection = selected["__interface__"]
        val interfaceNodes = if (interfaceSelection?.enabled == true)
            comparison.interfaceNodes.map { it.copy() } else emptyList()
        for (node in interfaceNodes) addUniqueNode(remoteNodes, node)

        return NodeSyncPlan(
            remoteNodes = remoteNodes,
            localUpdates = localUpdates,
            remoteOnlyNodes = remoteOnlyNodes,
            localInterfaceNodes = localInterfaceNodes,
            interfaceNodes = interfaceNodes,
            remoteFileName = comparison.remoteFileName,
            targetTunnelName = comparison.selectedLocalTunnelName.trim(),
        )
    }

    /** 拉取服务端节点文件并与本地隧道快照比对。 */
    fun getNodeComparison(
        context: Context,
        settings: GistSync.SyncSettings,
        remoteFileName: String? = null,
        localTunnelName: String? = null,
    ): NodeSyncComparison {
        val provider = GistSyncProvider.gitee(context)
        val token = settings.token.trim()
        val gistId = settings.gistId.trim()
        if (token.isEmpty()) throw GistSync.SyncException("TOKEN_REQUIRED")
        if (gistId.isEmpty()) throw GistSync.SyncException("GIST_ID_REQUIRED")
        val gist = provider.getDocument(settings)
        val files = gist.files.mapValues { it.value.content.orEmpty() }
        val remoteFileNames = getRemoteNodeFileNames(files)
        val syncFile = selectRemoteNodeFile(files, remoteFileName)
        val remoteNodes = if (syncFile != null && syncFile.content.isNotEmpty())
            parseNodeFile(syncFile.content) else emptyList()
        val tunnelNames = listLocalTunnelNames(context)
        val selectedLocalTunnelName = selectLocalTunnelName(tunnelNames, localTunnelName)
        val snapshot = collectLocalSnapshot(context, listOfNotNull(selectedLocalTunnelName.ifEmpty { null }))
        return createComparison(
            snapshot.nodes, remoteNodes, snapshot.interfaceNodes,
            syncFile?.name ?: GITEE_NODE_SYNC_FILE, remoteFileNames, tunnelNames, selectedLocalTunnelName,
        )
    }

    /** 把本地全部节点作为新 gist 首次上传，并保存返回的 gistId。 */
    fun uploadInitial(context: Context, settings: GistSync.SyncSettings): String {
        val provider = GistSyncProvider.gitee(context)
        val token = settings.token.trim()
        if (token.isEmpty()) throw GistSync.SyncException("TOKEN_REQUIRED")
        val snapshot = collectLocalSnapshot(context)
        val nodes = snapshot.nodes.map { it.node } + snapshot.interfaceNodes.map { it.node }
        if (nodes.isEmpty()) throw GistSync.SyncException("NO_TUNNELS")
        val gistId = provider.createRawGist(token, "wireguard-harmony-node-sync", GITEE_NODE_SYNC_FILE, formatNodeFile(nodes))
        if (gistId.isEmpty()) throw GistSync.SyncException("INVALID_RESPONSE")
        provider.saveSettings(GistSync.SyncSettings(token, gistId))
        return gistId
    }

    fun applySyncPlan(context: Context, settings: GistSync.SyncSettings, plan: NodeSyncPlan): NodeSyncResult {
        val provider = GistSyncProvider.gitee(context)
        val token = settings.token.trim()
        val gistId = settings.gistId.trim()
        if (token.isEmpty()) throw GistSync.SyncException("TOKEN_REQUIRED")
        if (gistId.isEmpty()) throw GistSync.SyncException("GIST_ID_REQUIRED")

        val store = FileConfigStore(context)
        val updatedTunnels = LinkedHashMap<String, Config>()
        for (update in plan.localUpdates) {
            val tunnelName = update.local.tunnelName
            if (tunnelName.isEmpty()) continue
            var config = updatedTunnels[tunnelName] ?: loadLocalConfig(context, tunnelName)
                ?: throw GistSync.SyncException("LOCAL_CONFIG_NOT_FOUND")
            config = if (update.local.peerIndex < 0) {
                replaceConfigInterface(config, update.remote)
            } else {
                val peers = config.peers.toMutableList()
                if (update.local.peerIndex >= peers.size)
                    throw GistSync.SyncException("LOCAL_CONFIG_NOT_FOUND")
                peers[update.local.peerIndex] = toPeer(update.remote)
                buildConfig(config.`interface`, peers)
            }
            updatedTunnels[tunnelName] = config
        }

        val existingNames = store.enumerate()
        val createdNames = ArrayList<String>()
        val targetTunnelName = plan.targetTunnelName.trim()
        if (targetTunnelName.isNotEmpty()) {
            var config = updatedTunnels[targetTunnelName] ?: loadLocalConfig(context, targetTunnelName)
                ?: throw GistSync.SyncException("LOCAL_CONFIG_NOT_FOUND")
            val interfaceIds = plan.localInterfaceNodes.map { nodeId(it) }.toSet()
            if (plan.localInterfaceNodes.isNotEmpty())
                config = replaceConfigInterface(config, plan.localInterfaceNodes[0])
            val peers = config.peers.toMutableList()
            val existingPeerIds = peers.map { it.publicKey.toBase64() }.toMutableSet()
            for (node in plan.remoteOnlyNodes) {
                val nodeId = nodeId(node)
                if (nodeId in interfaceIds || nodeId in existingPeerIds) continue
                peers.add(toPeer(node))
                existingPeerIds.add(nodeId)
            }
            updatedTunnels[targetTunnelName] = buildConfig(config.`interface`, peers)
        } else if (plan.remoteOnlyNodes.isNotEmpty()) {
            val name = uniqueTunnelName(plan.remoteFileName.ifEmpty { GITEE_NODE_SYNC_FILE }, existingNames.toList(), createdNames)
            store.create(name, configFromNodes(plan.remoteOnlyNodes, plan.localInterfaceNodes))
            createdNames.add(name)
        }

        for ((name, config) in updatedTunnels) store.save(name, config)

        val remoteContent = formatNodeFile(plan.remoteNodes)
        provider.patchFile(settings, plan.remoteFileName.ifEmpty { GITEE_NODE_SYNC_FILE }, remoteContent)
        Log.i(TAG, "applySyncPlan complete: file=${plan.remoteFileName} remoteNodes=${plan.remoteNodes.size}")
        return NodeSyncResult(createdNames, plan.remoteNodes.size, updatedTunnels.size)
    }

    fun createComparison(
        localNodes: List<LocalSyncNode>,
        remoteNodes: List<SyncNode>,
        interfaceNodes: List<LocalSyncNode>,
        remoteFileName: String,
        remoteFileNames: List<String> = listOf(remoteFileName),
        localTunnelNames: List<String> = emptyList(),
        selectedLocalTunnelName: String = "",
    ): NodeSyncComparison {
        val remoteIds = remoteNodes.map { nodeId(it) }.toSet()
        val mergedLocalNodes = LinkedHashMap<String, LocalSyncNode>()
        for (node in localNodes) mergedLocalNodes[nodeId(node)] = node
        val visibleInterfaceNodes = LinkedHashMap<String, SyncNode>()
        for (node in interfaceNodes) {
            val id = nodeId(node)
            if (id in remoteIds) mergedLocalNodes[id] = node
            else visibleInterfaceNodes.putIfAbsent(id, node.node)
        }
        val localById = mergedLocalNodes.values.associateBy { nodeId(it) }
        val remoteById = remoteNodes.associateBy { nodeId(it) }
        fun sortNode(id: String): SyncNode? = localById[id]?.node ?: remoteById[id]
        val sortedIds = (localById.keys + remoteById.keys).sortedWith { left, right ->
            compareNodeCards(sortNode(left), sortNode(right), left, right)
        }
        val items = sortedIds.map { id ->
            val local = localById[id]
            val remote = remoteById[id]
            val kind = when {
                local != null && remote != null -> Kind.COMMON
                local != null -> Kind.LOCAL_ONLY
                else -> Kind.SERVER_ONLY
            }
            createNodeSyncItem(id, kind, local, remote)
        }
        return NodeSyncComparison(
            localNodes = localNodes,
            remoteNodes = remoteNodes,
            interfaceNodes = visibleInterfaceNodes.values.toList(),
            items = items,
            remoteFileName = remoteFileName,
            remoteFileNames = remoteFileNames,
            localTunnelNames = localTunnelNames,
            selectedLocalTunnelName = selectedLocalTunnelName,
        )
    }

    private fun getRemoteNodeFileNames(files: Map<String, String>): List<String> {
        val names = files.keys.sorted().filter { name ->
            val content = files[name]?.trim().orEmpty()
            content.isNotEmpty() && try {
                parseNodeFile(content).isNotEmpty()
            } catch (e: Exception) {
                false
            }
        }
        return if (GITEE_NODE_SYNC_FILE in names)
            listOf(GITEE_NODE_SYNC_FILE) + names.filter { it != GITEE_NODE_SYNC_FILE }
        else names
    }

    private fun selectRemoteNodeFile(files: Map<String, String>, remoteFileName: String?): InterfaceSelection? {
        val names = getRemoteNodeFileNames(files)
        if (!remoteFileName.isNullOrBlank()) {
            val trimmed = remoteFileName.trim()
            val file = files[trimmed]
            if (file != null && trimmed in names) return InterfaceSelection(trimmed, file)
            throw GistSync.SyncException("REMOTE_NO_CONFIGS")
        }
        for (name in listOf(GITEE_NODE_SYNC_FILE, LEGACY_GITEE_NODE_SYNC_FILE))
            if (name in names) return InterfaceSelection(name, files.getValue(name))
        return names.firstOrNull()?.let { InterfaceSelection(it, files.getValue(it)) }
    }

    private fun collectLocalSnapshot(
        context: Context,
        selectedTunnelNames: List<String>? = null,
    ): LocalSnapshot {
        val tunnelNames = listLocalTunnelNames(context)
        val names = if (selectedTunnelNames == null) tunnelNames
            else tunnelNames.filter { it in selectedTunnelNames }
        val nodes = ArrayList<LocalSyncNode>()
        val interfaceNodes = ArrayList<LocalSyncNode>()
        for (name in names) {
            val config = loadLocalConfig(context, name) ?: continue
            val iface = config.`interface`
            val addresses = iface.addresses.map { it.toString() }
            val interfaceName = addresses.firstOrNull() ?: name
            interfaceNodes.add(LocalSyncNode(SyncNode(
                name = interfaceName,
                publicKey = iface.keyPair.publicKey.toBase64(),
                privateKey = iface.keyPair.privateKey.toBase64(),
                allowedIPs = addresses.joinToString(", "),
                endpoint = "",
                listenPort = iface.listenPort.orElse(null),
                persistentKeepalive = null,
                preSharedKey = "",
            ), name, -1))
            config.peers.forEachIndexed { index, peer ->
                val allowedIPs = peer.allowedIps.joinToString(", ") { it.toString() }
                nodes.add(LocalSyncNode(SyncNode(
                    name = allowedIPs.split(",").firstOrNull()?.trim()?.ifEmpty { null }
                        ?: peer.publicKey.toBase64(),
                    publicKey = peer.publicKey.toBase64(),
                    privateKey = "",
                    allowedIPs = allowedIPs,
                    endpoint = peer.endpoint.orElse(null)?.toString().orEmpty(),
                    listenPort = null,
                    persistentKeepalive = peer.persistentKeepalive.orElse(null),
                    preSharedKey = peer.preSharedKey.orElse(null)?.toBase64().orEmpty(),
                ), name, index))
            }
        }
        return LocalSnapshot(tunnelNames, nodes, interfaceNodes)
    }

    private fun listLocalTunnelNames(context: Context): List<String> =
        FileConfigStore(context).enumerate().sorted()

    private fun selectLocalTunnelName(tunnelNames: List<String>, requestedTunnelName: String?): String {
        if (tunnelNames.size == 1) return tunnelNames[0]
        val trimmed = requestedTunnelName?.trim().orEmpty()
        return if (trimmed.isNotEmpty() && trimmed in tunnelNames) trimmed else ""
    }

    private fun loadLocalConfig(context: Context, name: String): Config? = try {
        FileConfigStore(context).load(name)
    } catch (e: Exception) {
        Log.w(TAG, "Unable to load local tunnel $name", e)
        null
    }

    private fun toPeer(node: SyncNode): Peer {
        val builder = Peer.Builder()
        for (part in node.allowedIPs.split(",")) {
            val value = part.trim()
            if (value.isNotEmpty()) builder.addAllowedIp(InetNetwork.parse(value))
        }
        if (node.endpoint.isNotBlank()) builder.setEndpoint(InetEndpoint.parse(node.endpoint.trim()))
        if (node.persistentKeepalive != null) builder.setPersistentKeepalive(node.persistentKeepalive)
        if (node.preSharedKey.isNotBlank()) builder.setPreSharedKey(Key.fromBase64(node.preSharedKey.trim()))
        builder.setPublicKey(Key.fromBase64(node.publicKey.trim()))
        return builder.build()
    }

    private fun configFromNodes(nodes: List<SyncNode>, localInterfaceNodes: List<SyncNode>): Config {
        val interfaceIds = localInterfaceNodes.map { nodeId(it) }.toSet()
        val interfaceSourceNodes = nodes.filter { nodeId(it) in interfaceIds }
        val addresses = LinkedHashSet<InetNetwork>()
        if (interfaceSourceNodes.isNotEmpty()) {
            for (node in interfaceSourceNodes)
                for (part in node.allowedIPs.split(",")) {
                    val value = part.trim()
                    if (value.isNotEmpty()) addresses.add(InetNetwork.parse(value))
                }
        } else {
            addresses.add(InetNetwork.parse(nodes[0].allowedIPs.split(",")[0].trim()))
        }
        val peerNodes = if (interfaceSourceNodes.isNotEmpty())
            nodes.filter { nodeId(it) !in interfaceIds } else nodes
        val interfacePrivateKey = interfaceSourceNodes.firstOrNull { it.privateKey.isNotBlank() }?.privateKey?.trim()
        val ifaceBuilder = Interface.Builder()
        ifaceBuilder.addAddresses(addresses)
        ifaceBuilder.setKeyPair(if (interfacePrivateKey != null) KeyPair(Key.fromBase64(interfacePrivateKey)) else KeyPair())
        ifaceBuilder.setListenPort(interfaceSourceNodes.firstOrNull()?.listenPort ?: 0)
        return buildConfig(ifaceBuilder.build(), peerNodes.map { toPeer(it) })
    }

    private fun replaceConfigInterface(config: Config, remote: SyncNode): Config {
        val current = config.`interface`
        val builder = Interface.Builder()
        for (part in remote.allowedIPs.split(",")) {
            val value = part.trim()
            if (value.isNotEmpty()) builder.addAddress(InetNetwork.parse(value))
        }
        builder.addDnsServers(current.dnsServers)
        builder.addDnsSearchDomains(current.dnsSearchDomains)
        builder.excludeApplications(current.excludedApplications)
        builder.includeApplications(current.includedApplications)
        builder.setKeyPair(
            if (remote.privateKey.isNotBlank()) KeyPair(Key.fromBase64(remote.privateKey.trim()))
            else current.keyPair
        )
        current.listenPort.ifPresent { builder.setListenPort(it) }
        current.mtu.ifPresent { builder.setMtu(it) }
        return buildConfig(builder.build(), config.peers)
    }

    private fun buildConfig(iface: Interface, peers: List<Peer>): Config {
        val builder = Config.Builder()
        builder.setInterface(iface)
        builder.addPeers(peers)
        return builder.build()
    }

    private fun nodeId(node: LocalSyncNode): String = node.publicKey.trim()

    private fun nodeId(node: SyncNode): String = node.publicKey.trim()

    private fun compareNodeCards(left: SyncNode?, right: SyncNode?, leftId: String, rightId: String): Int {
        val leftSort = getNodeSortInfo(left)
        val rightSort = getNodeSortInfo(right)
        if (leftSort.first != rightSort.first) return leftSort.first - rightSort.first
        if (leftSort.first == 0 && rightSort.first == 0 && leftSort.second != rightSort.second)
            return leftSort.second - rightSort.second
        val addressCompare = leftSort.third.compareTo(rightSort.third)
        if (addressCompare != 0) return addressCompare
        return leftId.compareTo(rightId)
    }

    /** (rank, lastOctet, address)：IPv4 升序在前，其余按地址字符串排序。 */
    private fun getNodeSortInfo(node: SyncNode?): Triple<Int, Int, String> {
        val address = node?.allowedIPs?.split(",")?.firstOrNull()?.trim()
            ?.ifEmpty { null } ?: node?.name?.trim().orEmpty()
        val ip = address.split("/")[0].trim()
        val ipv4Parts = ip.split(".")
        if (ipv4Parts.size == 4 && ipv4Parts.all { part -> part.isNotEmpty() && part.all { it.isDigit() } })
            return Triple(0, ipv4Parts[3].toIntOrNull() ?: Int.MAX_VALUE, address)
        return Triple(1, Int.MAX_VALUE, address)
    }

    private fun createNodeSyncItem(
        itemId: String,
        kind: Kind,
        local: LocalSyncNode?,
        remote: SyncNode?,
    ): NodeSyncItem = NodeSyncItem(
        itemId = itemId,
        kind = kind,
        same = kind == Kind.COMMON && local != null && remote != null && areNodesEquivalent(local, remote),
        local = local,
        remote = remote,
    )

    private fun areNodesEquivalent(local: LocalSyncNode, remote: SyncNode): Boolean =
        if (local.peerIndex < 0) areInterfaceNodesEquivalent(local, remote)
        else arePeerNodesEquivalent(local, remote)

    private fun arePeerNodesEquivalent(local: LocalSyncNode, remote: SyncNode): Boolean =
        normalizeAllowedIPs(local.allowedIPs) == normalizeAllowedIPs(remote.allowedIPs) &&
            normalizeEndpoint(local.endpoint) == normalizeEndpoint(remote.endpoint) &&
            arePrivateKeysEquivalent(local.privateKey, remote.privateKey)

    private fun areInterfaceNodesEquivalent(local: LocalSyncNode, remote: SyncNode): Boolean =
        normalizeAllowedIPs(local.allowedIPs) == normalizeAllowedIPs(remote.allowedIPs) &&
            arePrivateKeysEquivalent(local.privateKey, remote.privateKey)

    private fun normalizeAllowedIPs(value: String): String =
        value.split(",").map { it.trim() }.filter { it.isNotEmpty() }.sorted().joinToString(", ")

    private fun normalizeEndpoint(value: String): String {
        val trimmed = value.trim()
        return if (trimmed.isEmpty()) "" else InetEndpoint.parse(trimmed).toString()
    }

    private fun arePrivateKeysEquivalent(localPrivateKey: String, remotePrivateKey: String): Boolean {
        val left = localPrivateKey.trim()
        val right = remotePrivateKey.trim()
        return left.isEmpty() || right.isEmpty() || left == right
    }

    private fun replaceNode(nodes: MutableList<SyncNode>, target: SyncNode, replacement: SyncNode) {
        val index = nodes.indexOfFirst { nodeId(it) == nodeId(target) }
        if (index >= 0) nodes[index] = replacement.copy()
    }

    private fun addUniqueNode(nodes: MutableList<SyncNode>, node: SyncNode) {
        val id = nodeId(node)
        if (nodes.none { nodeId(it) == id }) nodes.add(node.copy())
    }

    private fun uniqueTunnelName(base: String, existing: Collection<String>, created: Collection<String>): String {
        val sanitized = base.trim().replace(Regex("[\\\\/:*?\"<>|]"), "_").ifEmpty { "synced-node" }
        var candidate = sanitized
        var index = 2
        while (candidate in existing || candidate in created) {
            candidate = "${sanitized}_$index"
            index++
        }
        return candidate
    }

    fun canUseNodeAsLocalInterface(node: SyncNode): Boolean = node.privateKey.isNotBlank()
}
