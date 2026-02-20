package com.duoduojuzi

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import okhttp3.OkHttpClient

/**
 * @Author: DuoDuoJuZi
 * @Date: 2026-02-18
 *
 * 后台服务核心类。
 * 负责管理服务生命周期、mDNS 发现、通知栏显示以及协调 SmsHandler 和 PhotoHandler。
 * 继承 AccessibilityService 以获取后台剪贴板读取权限。
 */
class FastSyncService : AccessibilityService() {

    companion object {
        private const val CHANNEL_ID = "fast_sync_channel"
        private const val NOTIFICATION_ID = 1
        private const val TAG = "FastSyncService"
        private const val SERVICE_TYPE = "_photosync._tcp."
        const val ACTION_UPDATE_SERVER_URL = "com.duoduojuzi.ACTION_UPDATE_SERVER_URL"
        const val EXTRA_SERVER_IP = "EXTRA_SERVER_IP"
        const val EXTRA_SERVER_PORT = "EXTRA_SERVER_PORT"
        var isRunning = false
    }

    private val serviceScope = CoroutineScope(Dispatchers.IO + Job())
    private val okHttpClient = OkHttpClient()
    
    private lateinit var photoHandler: PhotoHandler
    private lateinit var smsHandler: SmsHandler
    private lateinit var clipboardHandler: ClipboardHandler
    
    private lateinit var nsdManager: NsdManager
    private var discoveryListener: NsdManager.DiscoveryListener? = null
    private var serverUrl: String? = null

    /**
     * 无障碍事件回调。
     * 1. 监听系统剪贴板 UI (如 com.miui.contentextension) 并尝试直接抓取文本。
     */
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        
        val packageName = event.packageName?.toString() ?: ""

        if (packageName == "com.miui.contentextension") {
            Log.d(TAG, "检测到小米传送门/剪贴板弹出，触发临时焦点夺取...")
            if (::clipboardHandler.isInitialized) {
                clipboardHandler.triggerMomentaryFocusRead()
            }
        }
    }

    /**
     * 递归查找指定类名的节点。
     */
    private fun findNodeByClass(node: AccessibilityNodeInfo, className: String): AccessibilityNodeInfo? {
        if (node.className == className) {
            return node
        }
        
        for (i in 0 until node.childCount) {
            val child = node.getChild(i)
            if (child != null) {
                val found = findNodeByClass(child, className)
                if (found != null) {
                    return found 
                }
                child.recycle()
            }
        }
        return null
    }

    /**
     * 递归查找最长文本节点 (排除功能性按钮)。
     */
    private fun findBestText(node: AccessibilityNodeInfo): String? {
        var longestText: String? = null
        
        if (node.text != null && node.text.isNotEmpty()) {
            val text = node.text.toString()
            if (text.length > 1 && 
                text != "搜索" && text != "分享" && text != "复制" && 
                text != "确定" && text != "取消" && text != "编辑") {
                   longestText = text
            }
        }
        
        for (i in 0 until node.childCount) {
            val child = node.getChild(i)
            if (child != null) {
                val childText = findBestText(child)
                child.recycle()
                
                if (childText != null) {
                    if (longestText == null || childText.length > longestText.length) {
                        longestText = childText
                    }
                }
            }
        }
        
        return longestText
    }

    /**
     * 无障碍服务中断回调。
     */
    override fun onInterrupt() {
        Log.w(TAG, "Accessibility Service Interrupted")
    }

    /**
     * 服务创建回调。
     * 初始化各模块、启动服务发现和前台通知。
     */
    override fun onCreate() {
        super.onCreate()
        
        serverUrl = "http://192.168.1.4:3000/upload"
        Log.i(TAG, "Initialized with default serverUrl: $serverUrl")
        
        createNotificationChannel()
        
        photoHandler = PhotoHandler(contentResolver, serviceScope, okHttpClient) { serverUrl }
        photoHandler.init()
        
        smsHandler = SmsHandler(this, serviceScope, okHttpClient) { serverUrl }
        smsHandler.init()

        clipboardHandler = ClipboardHandler(this, serviceScope, okHttpClient) { serverUrl }
        clipboardHandler.init()
        
        startServiceDiscovery()
    }

    /**
     * 服务启动回调。
     * 处理来自 Activity 的 URL 更新指令，并维持前台服务状态。
     */
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_UPDATE_SERVER_URL) {
            val ip = intent.getStringExtra(EXTRA_SERVER_IP)
            val port = intent.getIntExtra(EXTRA_SERVER_PORT, 3000)
            if (ip != null) {
                serverUrl = "http://$ip:$port/upload"
                Log.i(TAG, "Server URL manually updated: $serverUrl")
            }
            return START_STICKY
        }

        val notification = createNotification()
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        
        isRunning = true
        Log.d(TAG, "FastSyncService started")
        
        return START_STICKY
    }
    
    /**
     * 初始化并启动 mDNS 服务发现。
     * 监听局域网内的 _photosync._tcp 服务，解析成功后更新 serverUrl。
     */
    private fun startServiceDiscovery() {
        nsdManager = getSystemService(Context.NSD_SERVICE) as NsdManager
        
        discoveryListener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(regType: String) {
                Log.d(TAG, "Service discovery started")
            }

            override fun onServiceFound(service: NsdServiceInfo) {
                Log.d(TAG, "Service found: $service")
                if (service.serviceType.contains("_photosync._tcp")) { 
                     nsdManager.resolveService(service, object : NsdManager.ResolveListener {
                        override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                            Log.e(TAG, "Resolve failed: $errorCode")
                        }

                        override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                            Log.d(TAG, "Resolve Succeeded. $serviceInfo")
                            val host = serviceInfo.host
                            val port = serviceInfo.port
                            if (host != null) {
                                val url = "http://${host.hostAddress}:$port/upload"
                                serverUrl = url
                                Log.i(TAG, "Server URL discovered and updated: $url")
                            }
                        }
                    })
                }
            }

            override fun onServiceLost(service: NsdServiceInfo) {
                Log.e(TAG, "service lost: $service")
            }

            override fun onDiscoveryStopped(serviceType: String) {
                Log.i(TAG, "Discovery stopped")
            }

            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.e(TAG, "Start Discovery failed: $errorCode")
                nsdManager.stopServiceDiscovery(this)
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.e(TAG, "Stop Discovery failed: $errorCode")
                nsdManager.stopServiceDiscovery(this)
            }
        }

        nsdManager.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discoveryListener)
    }

    /**
     * 服务销毁回调。
     * 释放所有资源，停止各模块工作。
     */
    override fun onDestroy() {
        super.onDestroy()
        isRunning = false
        
        if (::photoHandler.isInitialized) photoHandler.destroy()
        if (::smsHandler.isInitialized) smsHandler.destroy()
        if (::clipboardHandler.isInitialized) clipboardHandler.destroy()
        
        if (discoveryListener != null) {
            nsdManager.stopServiceDiscovery(discoveryListener)
        }
    }

    /**
     * 创建前台服务通知渠道。
     */
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val name = getString(R.string.notification_channel_name)
            val descriptionText = getString(R.string.notification_channel_description)
            val importance = NotificationManager.IMPORTANCE_LOW
            val channel = NotificationChannel(CHANNEL_ID, name, importance).apply {
                description = descriptionText
            }
            val notificationManager: NotificationManager =
                getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }

    /**
     * 构建前台服务通知。
     */
    private fun createNotification(): Notification {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        val pendingIntent: PendingIntent = PendingIntent.getActivity(
            this, 0, intent, PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_upload)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.service_running))
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }
}