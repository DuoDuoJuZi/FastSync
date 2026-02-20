/*
 * @Author: DuoDuoJuZi
 * @Date: 2026-02-20
 *
 * 剪贴板同步处理器模块。
 */
package com.duoduojuzi

import android.content.ClipboardManager
import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import kotlinx.coroutines.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

/**
 * 剪贴板同步处理器类。
 */
class ClipboardHandler(
    private val context: Context,
    private val scope: CoroutineScope,
    private val client: OkHttpClient,
    private val getServerUrl: () -> String?
) {
    private val TAG = "ClipboardHandler"
    
    private lateinit var clipboardManager: ClipboardManager
    private lateinit var windowManager: WindowManager
    private var dummyView: View? = null
    
    private var clipboardListener: ClipboardManager.OnPrimaryClipChangedListener? = null
    
    private var lastSentText: String = ""
    private var lastSentTime: Long = 0
    private var isReading: Boolean = false
    
    /**
     * 初始化。
     */
    fun init() {
        clipboardManager = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        
        clipboardListener = ClipboardManager.OnPrimaryClipChangedListener {
            Log.i(TAG, "System clipboard changed, triggering focus steal...")
            triggerMomentaryFocusRead()
        }
        clipboardManager.addPrimaryClipChangedListener(clipboardListener)
        
        Log.i(TAG, "ClipboardHandler initialized (Hybrid mode: Listener + Focus Stealing)")
    }

    /**
     * 销毁资源。
     */
    fun destroy() {
        if (clipboardListener != null) {
            clipboardManager.removePrimaryClipChangedListener(clipboardListener)
            clipboardListener = null
        }
        removeOverlayView()
    }

    /**
     * 触发短暂的焦点读取。
     * 创建一个不可见的悬浮窗抢占焦点 -> 读取剪贴板 -> 立即销毁。
     */
    fun triggerMomentaryFocusRead() {
        if (isReading) return
        isReading = true
        
        scope.launch(Dispatchers.Main) {
            try {
                // 1. 创建并添加悬浮窗
                if (dummyView == null) {
                    dummyView = View(context).apply {
                        alpha = 0f // 全透明
                        isFocusable = true
                        isFocusableInTouchMode = true
                    }
                }
                
                val params = WindowManager.LayoutParams(
                    1, 1,
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
                    else
                        WindowManager.LayoutParams.TYPE_PHONE,
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or 
                            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or 
                            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                    PixelFormat.TRANSPARENT
                )
                params.gravity = Gravity.TOP or Gravity.LEFT
                params.x = 0
                params.y = 0

                windowManager.addView(dummyView, params)
                dummyView?.requestFocus()
                
                delay(70)
                
                val clipData = clipboardManager.primaryClip
                if (clipData != null && clipData.itemCount > 0) {
                    val text = clipData.getItemAt(0).text?.toString()
                    if (!text.isNullOrEmpty()) {
                        handleText(text)
                    }
                }
                
            } catch (e: Exception) {
                Log.e(TAG, "Failed to read clipboard via focus stealing: ${e.message}")
            } finally {
                removeOverlayView()
                isReading = false
            }
        }
    }
    
    /**
     * 移除悬浮窗。
     */
    private fun removeOverlayView() {
        try {
            if (dummyView != null && dummyView?.windowToken != null) {
                windowManager.removeView(dummyView)
                Log.d(TAG, "Momentary overlay removed")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to remove overlay: ${e.message}")
        }
    }

    /**
     * 处理读取到的文本。
     */
    private fun handleText(text: String) {
        val now = System.currentTimeMillis()
        
        if (text == lastSentText) {
            return
        }
        
        lastSentText = text
        lastSentTime = now
        
        Log.i(TAG, "Successfully captured clipboard content. Uploading...")

        scope.launch(Dispatchers.IO) {
            uploadClipboardText(text)
        }
    }
    
    /**
     * 兼容旧接口：直接上传文本（备用）
     */
    fun uploadText(text: String) {
        handleText(text)
    }

    /**
     * 上传剪贴板文本到 PC 端。
     *
     * @param text 剪贴板文本内容
     */
    private suspend fun uploadClipboardText(text: String) = withContext(Dispatchers.IO) {
        val baseUrl = getServerUrl()
        if (baseUrl == null) {
            Log.e(TAG, "Server URL not found yet.")
            return@withContext
        }
        
        val clipboardUrl = baseUrl.replace("/upload", "/clipboard")
        
        val json = JSONObject().apply {
            put("text", text)
            put("timestamp", System.currentTimeMillis())
        }
        
        val requestBody = json.toString().toRequestBody("application/json".toMediaType())
        
        val request = Request.Builder()
            .url(clipboardUrl)
            .post(requestBody)
            .build()
            
        client.newCall(request).enqueue(object : okhttp3.Callback {
            override fun onFailure(call: okhttp3.Call, e: java.io.IOException) {
                Log.e(TAG, "Clipboard sync failed: ${e.message}")
            }

            override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                if (response.isSuccessful) {
                    Log.i(TAG, "Clipboard sync successful: ${response.code}")
                } else {
                    Log.e(TAG, "Clipboard sync failed: ${response.code}")
                }
                response.close()
            }
        })
    }
}
