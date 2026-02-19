package com.duoduojuzi

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.database.ContentObserver
import android.net.Uri
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.MediaStore
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.ConcurrentHashMap

/**
 * @Author: DuoDuoJuZi
 * @Date: 2026-02-18
 */
class PhotoSyncService : Service() {

    companion object {
        private const val CHANNEL_ID = "photo_sync_channel"
        private const val NOTIFICATION_ID = 1
        private const val TAG = "PhotoSyncService"
        private const val SERVICE_TYPE = "_photosync._tcp."
        const val ACTION_UPDATE_SERVER_URL = "com.duoduojuzi.ACTION_UPDATE_SERVER_URL"
        const val EXTRA_SERVER_IP = "EXTRA_SERVER_IP"
        const val EXTRA_SERVER_PORT = "EXTRA_SERVER_PORT"
        var isRunning = false
    }

    private lateinit var contentObserver: ContentObserver
    private val serviceScope = CoroutineScope(Dispatchers.IO + Job())
    private var debounceJob: Job? = null
    private val okHttpClient = OkHttpClient()
    
    private lateinit var nsdManager: NsdManager
    private var discoveryListener: NsdManager.DiscoveryListener? = null
    private var serverUrl: String? = null
    
    private val sentImageCache = ConcurrentHashMap<Long, Long>()
    private val CACHE_EXPIRATION_MS = 5000L

    /**
     * 服务生命周期回调：创建服务。
     * 初始化通知渠道、内容观察者并启动 mDNS 发现。
     * 默认预设一个硬编码 IP 以备 mDNS 失败。
     */
    override fun onCreate() {
        super.onCreate()
        
        serverUrl = "http://192.168.1.4:3000/upload"
        Log.i(TAG, "Initialized with default serverUrl: $serverUrl")
        
        createNotificationChannel()
        initContentObserver()
        startServiceDiscovery()
    }

    /**
     * 服务生命周期回调：启动命令。
     * 将服务提升为前台服务并显示持续通知。
     *
     * @param intent 启动 Intent
     * @param flags 启动标志
     * @param startId 启动 ID
     * @return 粘性启动模式 (START_STICKY)
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
        Log.d(TAG, "PhotoSyncService started")
        
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
     * 注册 ContentObserver 监听相册变化。
     * 监听 EXTERNAL_CONTENT_URI 的变动。
     */
    private fun initContentObserver() {
        contentObserver = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean, uri: Uri?) {
                super.onChange(selfChange, uri)
                Log.d(TAG, "Content changed: $uri")
                handleContentChange(uri)
            }
        }

        contentResolver.registerContentObserver(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            true,
            contentObserver
        )
    }

    /**
     * 处理相册内容变更事件。
     * 使用防抖动机制（500ms），避免短时间内频繁触发。
     *
     * @param uri 变更的具体 URI，若为空则全量扫描
     */
    private fun handleContentChange(uri: Uri?) {
        debounceJob?.cancel()
        debounceJob = serviceScope.launch {
            delay(500)
            if (uri != null) {
                fetchImageByUri(uri)
            } else {
                fetchLatestImage()
            }
        }
    }
    
    /**
     * 根据 URI 获取单张图片并尝试上传。
     * 包含去重逻辑和 Pending 状态检查。
     *
     * @param uri 图片的 Content URI
     */
    private suspend fun fetchImageByUri(uri: Uri) = withContext(Dispatchers.IO) {
         val projection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.IS_PENDING
        )
        
        try {
            contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val pendingColumn = cursor.getColumnIndex(MediaStore.Images.Media.IS_PENDING)
                    if (pendingColumn != -1 && cursor.getInt(pendingColumn) == 1) {
                        Log.d(TAG, "Image is pending, waiting... $uri")
                        return@use
                    }
                    
                    val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
                    val id = cursor.getLong(idColumn)
                    
                    // De-duplication check
                    val now = System.currentTimeMillis()
                    if (sentImageCache.containsKey(id) && (now - sentImageCache[id]!!) < CACHE_EXPIRATION_MS) {
                        return@use
                    }
                    sentImageCache[id] = now
                    
                    if (sentImageCache.size > 100) {
                        sentImageCache.entries.removeIf { (now - it.value) > CACHE_EXPIRATION_MS }
                    }

                    val nameColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
                    val name = cursor.getString(nameColumn) ?: "unknown.jpg"
                    
                    val imageData = readImageBytes(uri)
                    if (imageData != null) {
                        uploadRawImage(imageData, name)
                    }
                }
            }
        } catch (e: Exception) {
             Log.e(TAG, "Error querying URI: $uri", e)
        }
    }

    /**
     * 获取最新添加的一张非 Pending 状态的图片并上传。
     * 用于无法获取具体变更 URI 时的全量扫描回退。
     */
    private suspend fun fetchLatestImage() = withContext(Dispatchers.IO) {
        val projection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DATE_ADDED,
            MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.IS_PENDING
        )
        
        val selection = "${MediaStore.Images.Media.IS_PENDING} = 0"
        val sortOrder = "${MediaStore.Images.Media.DATE_ADDED} DESC"

        try {
            contentResolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                projection,
                selection,
                null,
                sortOrder
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
                    val id = cursor.getLong(idColumn)
                    
                    val now = System.currentTimeMillis()
                    if (sentImageCache.containsKey(id) && (now - sentImageCache[id]!!) < CACHE_EXPIRATION_MS) {
                        return@use
                    }
                    sentImageCache[id] = now

                    val nameColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
                    val name = cursor.getString(nameColumn)
                    val contentUri = Uri.withAppendedPath(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id.toString())

                    val imageData = readImageBytes(contentUri)
                    if (imageData != null) {
                        uploadRawImage(imageData, name)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error querying media store", e)
        }
    }

    /**
     * 从 Content URI 读取原始图片字节流。
     *
     * @param uri 图片 URI
     * @return 图片字节数组，读取失败返回 null
     */
    private fun readImageBytes(uri: Uri): ByteArray? {
        return try {
            contentResolver.openInputStream(uri)?.use { inputStream ->
                inputStream.readBytes()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error reading file", e)
            null
        }
    }
    
    /**
     * 调度图片上传任务到后台线程。
     * 检查 serverUrl 是否已就绪。
     *
     * @param data 图片原始字节数据
     * @param fileName 图片文件名
     */
    private suspend fun uploadRawImage(data: ByteArray, fileName: String) = withContext(Dispatchers.Default) {
        if (serverUrl == null) {
            Log.e(TAG, "Server URL not found yet.")
            return@withContext
        }

        uploadData(data, fileName)
    }

    /**
     * 执行实际的 HTTP Multipart 上传操作。
     * 使用 OkHttp 异步发送请求。
     *
     * @param data 图片字节数据
     * @param fileName 文件名
     */
    private fun uploadData(data: ByteArray, fileName: String) {
        val url = serverUrl ?: return
        Log.d(TAG, "Uploading raw image to $url...")
        
        val requestBody = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart(
                "data",
                fileName,
                data.toRequestBody("application/octet-stream".toMediaType())
            )
            .build()

        val request = Request.Builder()
            .url(url)
            .post(requestBody)
            .build()

        okHttpClient.newCall(request).enqueue(object : okhttp3.Callback {
            override fun onFailure(call: okhttp3.Call, e: java.io.IOException) {
                Log.e(TAG, "Upload failed: ${e.message}")
            }

            override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                if (response.isSuccessful) {
                    Log.i(TAG, "Upload successful: ${response.code}")
                } else {
                    Log.e(TAG, "Upload failed: ${response.code}")
                }
                response.close()
            }
        })
    }

    /**
     * 绑定服务回调。本服务不支持绑定，返回 null。
     *
     * @param intent 绑定 Intent
     * @return null
     */
    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    /**
     * 服务销毁回调。
     * 释放资源，注销监听器，取消协程任务。
     */
    override fun onDestroy() {
        super.onDestroy()
        isRunning = false
        contentResolver.unregisterContentObserver(contentObserver)
        debounceJob?.cancel()
        if (discoveryListener != null) {
            nsdManager.stopServiceDiscovery(discoveryListener)
        }
    }

    /**
     * 创建前台服务所需的通知渠道。
     * 仅在 Android O (API 26) 及以上版本生效。
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
     * 构建前台服务的常驻通知对象。
     * 点击通知可跳转回主界面。
     *
     * @return 构建好的 Notification 对象
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
