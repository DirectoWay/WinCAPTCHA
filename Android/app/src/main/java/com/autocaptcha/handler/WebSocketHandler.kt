package com.autocaptcha.handler

import android.content.Context
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.autocaptcha.dataclass.PairedDeviceInfo
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONException
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import java.io.IOException
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket
import java.net.SocketException

/** 为 WebSocket 连接时提供工具方法 */
class WebSocketWorker(context: Context, workerParams: WorkerParameters) :
    CoroutineWorker(context, workerParams) {
    private val webSocketHandler = WebSocketHandler()
    private val tag = "WebSocketWorker"

    companion object {

        private const val KEY_MESSAGE = "message"

        /** WebSocket 连接超时时间 */
        private const val CONNECTION_TIMEOUT = 3 * 60 * 1000L

        /** 创建给 Win 端发送消息的 WorkRequest */
        fun sendWebSocketMessage(context: Context, message: String) {

            val inputData = Data.Builder().putString(KEY_MESSAGE, message).build()

            val expeditedWorkRequest =
                OneTimeWorkRequestBuilder<WebSocketWorker>().setInputData(inputData)
                    .setBackoffCriteria(
                        BackoffPolicy.EXPONENTIAL, 2_000, // 最小退避时间
                        TimeUnit.MILLISECONDS
                    ).setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST).build()

            WorkManager.getInstance(context.applicationContext).enqueue(expeditedWorkRequest)
        }
    }

    override suspend fun doWork(): Result {
        val appContext = applicationContext
        val message = inputData.getString(KEY_MESSAGE)
        return try {
            val webSocketInfo = webSocketHandler.getOnlineDevices(9224)

            if (webSocketInfo.isEmpty()) {
                Log.e(tag, "该局域网中暂无在线设备")
                return Result.failure()
            }

            connectWebSocket(webSocketInfo, message)
            Result.success()
        } catch (e: TimeoutCancellationException) {
            // 处理超时情况
            Log.e(tag, "WebSocket 连接超时")
            Result.failure()
        } catch (e: Exception) {
            Log.e(tag, "WebSocket 连接失败", e)
            Result.retry()
        }
    }

    private suspend fun connectWebSocket(
        webSocketInfo: List<String>, message: String? = null
    ) {
        withContext(Dispatchers.IO) {
            /** 每个设备的 WebSocket连接状态 */
            val webSocketRefs = webSocketInfo.map { AtomicReference<WebSocket>() }

            val deferredResults = webSocketInfo.mapIndexed { index, serverUrl ->
                async {
                    try {
                        val client = OkHttpClient.Builder()
                            .pingInterval(30, TimeUnit.SECONDS) // 保持连接活跃
                            .build()

                        val request = Request.Builder().url(serverUrl).build()

                        val connectionResult = CompletableDeferred<WebSocket?>()

                        val webSocketListener = object : WebSocketListener() {
                            override fun onOpen(
                                webSocket: WebSocket, response: okhttp3.Response
                            ) {
                                Log.d(tag, "WebSocket 连接成功: $serverUrl")
                                webSocketRefs[index].set(webSocket)
                                message?.let { webSocket.send(it) }
                                connectionResult.complete(webSocket)
                            }

                            override fun onMessage(webSocket: WebSocket, text: String) {
                                Log.d(tag, "收到消息: $text 设备: $serverUrl")
                                resetConnectionTimeout()
                            }

                            override fun onFailure(
                                webSocket: WebSocket, t: Throwable, response: okhttp3.Response?
                            ) {
                                Log.e(tag, "WebSocket 连接失败: ${t.message}, 设备: $serverUrl")
                                connectionResult.complete(null)
                            }

                            override fun onClosed(
                                webSocket: WebSocket, code: Int, reason: String
                            ) {
                                Log.d(tag, "WebSocket 已关闭: $reason, 设备: $serverUrl")
                                connectionResult.complete(webSocket)
                            }
                        }

                        // 创建连接
                        client.newWebSocket(request, webSocketListener)

                        // 返回WebSocket连接（成功则是WebSocket对象，失败则是null）
                        connectionResult.await()
                    } catch (e: Exception) {
                        Log.e(tag, "连接 WebSocket 过程中出现异常: ${e.message}")
                        null
                    }
                }
            }

            val webSockets = deferredResults.awaitAll()

            /** WebSocket 连接失败的设备 */
            val failedDevices = webSockets.filter { it == null }
            if (failedDevices.isNotEmpty()) {
                Log.w(tag, "${failedDevices.size}台设备未能成功连接")
            }
        }
    }

    private fun resetConnectionTimeout() {
        Log.d(tag, "重置连接超时计时器")
    }

}

class WebSocketHandler {
    private val tag = "WebSocketHandler"

    /** 局域网中获取在线的设备 */
    suspend fun getOnlineDevices(targetPort: Int): List<String> {
        val localNetworkPrefix = getLocalNetworkPrefix()
        val devices = mutableListOf<String>()

        Log.d(tag, "开始扫描局域网设备...")
        val startTime = System.currentTimeMillis()
        val chunkSize = 50 // 每批次 50 个 IP
        val ipBatches = (1..254).chunked(chunkSize)

        coroutineScope {
            ipBatches.forEach { batch ->
                val jobs = batch.map { i ->
                    launch(Dispatchers.IO) {
                        val ip = "$localNetworkPrefix.$i"
                        try {
                            withTimeout(1000) { // 扫描任务超时时间
                                Socket().use { socket ->
                                    val address = InetSocketAddress(ip, targetPort)
                                    socket.connect(address, 200) // 连接探测的超时时间
                                    synchronized(devices) {
                                        val websocketUrl = "ws://$ip:$targetPort"
                                        devices.add(websocketUrl)
                                        Log.d(tag, "目标设备在线: $websocketUrl")
                                    }
                                }
                            }
                        } catch (e: TimeoutCancellationException) {
                            Log.d(tag, "局域网设备扫描超时: IP = $ip")
                        } catch (e: IOException) {
                            // 扫到不在线的设备的时候, 这里异常会非常多, 不处理异常, 继续操作
                        } catch (e: Exception) {
                            Log.e(tag, "扫描局域网 IP = $ip 出现异常: ${e.message}")
                        }
                    }
                }

                jobs.joinAll()
            }
        }

        val endTime = System.currentTimeMillis()
        Log.d(tag, "局域网设备扫描结束，在线设备总数: ${devices.size}")
        Log.d(tag, "局域网设备扫描耗时: ${endTime - startTime} ms")

        return devices
    }

    /** 获取局域网 IP 的前缀 */
    private fun getLocalNetworkPrefix(): String? {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            for (networkInterface in interfaces) {
                val addresses = networkInterface.inetAddresses
                for (address in addresses) {
                    if (address is Inet4Address && !address.isLoopbackAddress) {
                        val ip = address.hostAddress
                        val prefix = ip?.substring(0, ip.lastIndexOf('.'))
                        return prefix
                    }
                }
            }
        } catch (e: SocketException) {
            Log.e(tag, "获取局域网 IP 前缀失败")
        }
        return ""
    }

    /** 获取所有已配对的设备信息 */
    fun getAllDevicesInfo(context: Context): List<PairedDeviceInfo> {
        val sharedPreferences =
            context.applicationContext.getSharedPreferences("KnownDevices", Context.MODE_PRIVATE)
        val allDeviceIds = sharedPreferences.all.keys
        val allDevicesInfo = mutableListOf<PairedDeviceInfo>()

        for (deviceId in allDeviceIds) {
            val deviceInfoString = sharedPreferences.getString(deviceId, null)

            if (deviceInfoString != null) {
                try {
                    val deviceInfo = JSONObject(deviceInfoString)
                    val pairingInfo = PairedDeviceInfo(
                        deviceIP = deviceInfo.getString("deviceIP"),
                        webSocketPort = deviceInfo.getString("webSocketPort"),
                        deviceName = deviceInfo.getString("deviceName"),
                        deviceId = deviceInfo.getString("deviceId"),
                        deviceType = deviceInfo.getString("deviceType"),
                        windowsPublicKey = deviceInfo.getString("windowsPublicKey"),
                    )
                    allDevicesInfo.add(pairingInfo)
                } catch (e: JSONException) {
                    Log.e(tag, "获取设备信息失败: deviceId: $deviceId", e)
                }
            }
        }

        return allDevicesInfo
    }
}
