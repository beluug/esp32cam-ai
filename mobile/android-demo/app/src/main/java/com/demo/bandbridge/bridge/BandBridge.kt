package com.demo.bandbridge.bridge

import android.content.Context
import com.demo.bandbridge.model.BandSendResult
import com.demo.bandbridge.model.ConnectionState
import com.demo.bandbridge.model.PhoneMessage
import com.xiaomi.xms.wearable.Wearable
import com.xiaomi.xms.wearable.auth.AuthApi
import com.xiaomi.xms.wearable.auth.Permission
import com.xiaomi.xms.wearable.message.MessageApi
import com.xiaomi.xms.wearable.message.OnMessageReceivedListener
import com.xiaomi.xms.wearable.node.Node
import com.xiaomi.xms.wearable.node.NodeApi
import org.json.JSONObject

interface BandBridge {
    fun refreshConnection(callback: (ConnectionState) -> Unit)

    fun send(message: PhoneMessage, callback: (BandSendResult) -> Unit)
}

class WearableBandBridge(
    context: Context,
    private val onLog: (String) -> Unit,
    private val onCommand: (PhoneMessage) -> Unit = {}
) : BandBridge {
    private val appContext = context.applicationContext
    private val nodeApi: NodeApi? = runCatching { Wearable.getNodeApi(appContext) }
        .onFailure { onLog("手环 SDK 初始化失败(NodeApi): ${it.message}") }
        .getOrNull()
    private val messageApi: MessageApi? = runCatching { Wearable.getMessageApi(appContext) }
        .onFailure { onLog("手环 SDK 初始化失败(MessageApi): ${it.message}") }
        .getOrNull()
    private val authApi: AuthApi? = runCatching { Wearable.getAuthApi(appContext) }
        .onFailure { onLog("手环 SDK 初始化失败(AuthApi): ${it.message}") }
        .getOrNull()

    @Volatile
    private var cachedNode: Node? = null

    @Volatile
    private var listeningNodeId: String? = null

    private val incomingMessageListener = OnMessageReceivedListener { nodeId, message ->
        handleIncomingMessage(nodeId, message)
    }

    override fun refreshConnection(callback: (ConnectionState) -> Unit) {
        val api = nodeApi
        if (api == null) {
            callback(
                ConnectionState(
                    title = "手环能力未就绪",
                    detail = "当前手机上的手环通信 SDK 初始化失败，已跳过手环检查。",
                    connected = false
                )
            )
            onLog("NodeApi 不可用，已跳过手环连接检查")
            return
        }

        api.connectedNodes
            .addOnSuccessListener { nodes ->
                cachedNode = nodes.firstOrNull()
                val node = cachedNode
                if (node != null) {
                    ensureMessageListener(node)
                    callback(
                        ConnectionState(
                            title = "手环已连接",
                            detail = "已发现手环节点，发送时如首次授权会弹出确认。",
                            connected = true
                        )
                    )
                    onLog("手环节点已就绪: ${node.id}")
                } else {
                    callback(
                        ConnectionState(
                            title = "未发现手环",
                            detail = "请先确认手环已配对，并安装了当前同包名同签名的 RPK。",
                            connected = false
                        )
                    )
                    onLog("未找到手环节点")
                }
            }
            .addOnFailureListener { error ->
                callback(
                    ConnectionState(
                        title = "手环状态查询失败",
                        detail = error.message ?: "无法读取手环节点状态。",
                        connected = false
                    )
                )
                onLog("查询手环状态失败: ${error.message}")
            }
    }

    override fun send(message: PhoneMessage, callback: (BandSendResult) -> Unit) {
        sendWithNodeRefreshRetry(message, callback)
    }

    private fun sendWithNodeRefreshRetry(
        message: PhoneMessage,
        callback: (BandSendResult) -> Unit,
        allowRetry: Boolean = true
    ) {
        withConnectedNode(
            onNodeReady = { node ->
                ensureDeviceManagerPermission(
                    node = node,
                    onGranted = {
                        sendPayload(
                            node = node,
                            message = message,
                            callback = callback,
                            allowRetry = allowRetry
                        )
                    },
                    onDenied = { reason ->
                        callback(BandSendResult(false, reason))
                    }
                )
            },
            onNodeMissing = { errorText ->
                onLog("发送前阻塞: $errorText")
                callback(BandSendResult(false, errorText))
            }
        )
    }

    private fun ensureDeviceManagerPermission(
        node: Node,
        onGranted: () -> Unit,
        onDenied: (String) -> Unit
    ) {
        val api = authApi
        if (api == null) {
            onDenied("手环权限模块初始化失败。")
            return
        }

        val permissions = arrayOf(Permission.DEVICE_MANAGER)
        api.checkPermissions(node.id, permissions)
            .addOnSuccessListener { grantResults ->
                val granted = grantResults.isNotEmpty() && grantResults[0]
                if (granted) {
                    onLog("手环发送权限已存在")
                    onGranted()
                } else {
                    onLog("缺少手环发送权限，开始申请")
                    requestDeviceManagerPermission(node, onGranted, onDenied)
                }
            }
            .addOnFailureListener { error ->
                onLog("检查手环权限失败: ${error.message}")
                requestDeviceManagerPermission(node, onGranted, onDenied)
            }
    }

    private fun requestDeviceManagerPermission(
        node: Node,
        onGranted: () -> Unit,
        onDenied: (String) -> Unit
    ) {
        val api = authApi
        if (api == null) {
            onDenied("手环权限模块初始化失败。")
            return
        }

        api.requestPermission(node.id, Permission.DEVICE_MANAGER)
            .addOnSuccessListener { grantedPermissions ->
                val granted = grantedPermissions.any { permission ->
                    permission == Permission.DEVICE_MANAGER
                }
                if (granted) {
                    onLog("手环发送权限申请成功")
                    onGranted()
                } else {
                    onLog("权限申请结束，但用户未授予 DEVICE_MANAGER")
                    onDenied("手环侧没有授予发送权限，请在弹窗里允许。")
                }
            }
            .addOnFailureListener { error ->
                onLog("申请手环权限失败: ${error.message}")
                onDenied(error.message ?: "手环权限申请失败。")
            }
    }

    private fun sendPayload(
        node: Node,
        message: PhoneMessage,
        callback: (BandSendResult) -> Unit,
        allowRetry: Boolean
    ) {
        val api = messageApi
        if (api == null) {
            callback(BandSendResult(false, "手环消息模块初始化失败。"))
            return
        }

        val payload = message.toPayload().toByteArray(Charsets.UTF_8)
        api.sendMessage(node.id, payload)
            .addOnSuccessListener {
                onLog("消息已发到手环节点 ${node.id}")
                callback(BandSendResult(true, "文本已发到手环。"))
            }
            .addOnFailureListener { error ->
                onLog("手环消息发送失败: ${error.message}")
                if (allowRetry) {
                    cachedNode = null
                    onLog("正在刷新手环节点并自动重试一次")
                    sendWithNodeRefreshRetry(
                        message = message,
                        callback = callback,
                        allowRetry = false
                    )
                    return@addOnFailureListener
                }
                callback(
                    BandSendResult(
                        ok = false,
                        message = error.message ?: "手环消息发送失败。"
                    )
                )
            }
    }

    private fun withConnectedNode(
        onNodeReady: (Node) -> Unit,
        onNodeMissing: (String) -> Unit
    ) {
        cachedNode?.let {
            ensureMessageListener(it)
            onNodeReady(it)
            return
        }

        val api = nodeApi
        if (api == null) {
            onNodeMissing("手环节点模块初始化失败。")
            return
        }

        api.connectedNodes
            .addOnSuccessListener { nodes ->
                val node = nodes.firstOrNull()
                if (node == null) {
                    onNodeMissing("当前没有发现手环节点，请先确认配对和安装状态。")
                } else {
                    cachedNode = node
                    ensureMessageListener(node)
                    onNodeReady(node)
                }
            }
            .addOnFailureListener { error ->
                onNodeMissing(error.message ?: "无法读取手环节点。")
            }
    }

    private fun ensureMessageListener(node: Node) {
        val api = messageApi ?: return
        if (listeningNodeId == node.id) {
            return
        }

        val previousNodeId = listeningNodeId
        if (!previousNodeId.isNullOrBlank()) {
            api.removeListener(previousNodeId)
        }

        api.addListener(node.id, incomingMessageListener)
            .addOnSuccessListener {
                listeningNodeId = node.id
                onLog("已注册手环指令监听 ${node.id}")
            }
            .addOnFailureListener { error ->
                onLog("注册手环指令监听失败: ${error.message}")
            }
    }

    private fun handleIncomingMessage(nodeId: String, message: ByteArray) {
        val payload = runCatching { String(message, Charsets.UTF_8) }.getOrElse {
            onLog("手环上行指令解析失败: ${it.message}")
            return
        }
        onLog("收到手环上行消息($nodeId): $payload")

        val json = runCatching { JSONObject(payload) }.getOrNull() ?: return
        if (json.optString("type") != "watch_command") {
            return
        }

        onCommand(
            PhoneMessage(
                type = "watch_command",
                title = json.optString("title").ifBlank { "手环指令" },
                content = json.optString("content"),
                sentAt = json.optLong("sentAt", System.currentTimeMillis())
            )
        )
    }
}
