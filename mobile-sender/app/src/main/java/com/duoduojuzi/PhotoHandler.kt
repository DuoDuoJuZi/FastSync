package com.duoduojuzi

import android.content.ContentResolver
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.Log
import kotlinx.coroutines.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.ConcurrentHashMap

/**
 * @Author: DuoDuoJuZi
 * @Date: 2026-02-19
 *
 * 处理照片监听与上传逻辑的专用 Handler。
 * 负责注册 ContentObserver、防抖动处理相册变更、去重检查及上传照片数据。
 */
class PhotoHandler(
    private val contentResolver: ContentResolver,
    private val scope: CoroutineScope,
    private val client: OkHttpClient,
    private val getServerUrl: () -> String?
) {
    private val TAG = "PhotoHandler"
    private lateinit var contentObserver: ContentObserver
    
    private var debounceJob: Job? = null
    
    /** 发送记录缓存，用于短时间内去重 (ID -> Timestamp) */
    private val sentImageCache = ConcurrentHashMap<Long, Long>()
    private val CACHE_EXPIRATION_MS = 5000L

    /**
     * 初始化 ContentObserver 并开始监听相册变化。
     * 监听 `MediaStore.Images.Media.EXTERNAL_CONTENT_URI`。
     */
    fun init() {
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
     * 注销 ContentObserver，释放资源。
     */
    fun destroy() {
        if (::contentObserver.isInitialized) {
            contentResolver.unregisterContentObserver(contentObserver)
        }
        debounceJob?.cancel()
    }

    /**
     * 处理内容变更事件，应用 500ms 防抖动。
     *
     * @param uri 变更的具体 URI，若为空则触发全量扫描逻辑
     */
    private fun handleContentChange(uri: Uri?) {
        debounceJob?.cancel()
        debounceJob = scope.launch {
            delay(500)
            if (uri != null) {
                fetchImageByUri(uri)
            } else {
                fetchLatestImage()
            }
        }
    }

    /**
     * 根据具体 URI 查询图片并上传。
     * 包含 Pending 状态过滤和 ID 去重逻辑。
     *
     * @param uri 图片 URI
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
     * 获取最新一张非 Pending 状态的图片并上传。
     * 用于无法获取具体 URI 时的回退处理。
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
     * 读取图片文件的原始字节流。
     *
     * @param uri 图片 URI
     * @return 字节数组，失败返回 null
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
     * 上传图片原始数据到 PC 端。
     *
     * @param data 图片字节数据
     * @param fileName 文件名
     */
    private suspend fun uploadRawImage(data: ByteArray, fileName: String) = withContext(Dispatchers.Default) {
        val serverUrl = getServerUrl()
        if (serverUrl == null) {
            Log.e(TAG, "Server URL not found yet.")
            return@withContext
        }

        uploadData(data, fileName, serverUrl)
    }

    /**
     * 执行 HTTP Multipart 上传请求。
     *
     * @param data 图片数据
     * @param fileName 文件名
     * @param url 目标服务器地址
     */
    private fun uploadData(data: ByteArray, fileName: String, url: String) {
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

        client.newCall(request).enqueue(object : okhttp3.Callback {
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
}
