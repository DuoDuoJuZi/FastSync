package com.duoduojuzi

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.provider.Telephony
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * @Author: DuoDuoJuZi
 * @Date: 2026-02-19
 *
 * 处理短信接收与同步逻辑的专用 Handler。
 * 负责注册广播接收器、提取短信验证码以及向服务端上传短信数据。
 */
class SmsHandler(
    private val context: Context,
    private val scope: CoroutineScope,
    private val client: OkHttpClient,
    private val getServerUrl: () -> String?
) {
    private val TAG = "SmsHandler"
    private lateinit var smsReceiver: BroadcastReceiver

    /**
     * 初始化并注册 SMS 广播接收器。
     * 监听 `android.provider.Telephony.SMS_RECEIVED` 广播。
     */
    fun init() {
        smsReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.action == Telephony.Sms.Intents.SMS_RECEIVED_ACTION) {
                    val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent)
                    messages?.forEach { sms ->
                        val sender = sms.originatingAddress ?: "Unknown"
                        val content = sms.messageBody ?: ""
                        Log.d(TAG, "SMS Received from $sender: $content")
                        handleSms(sender, content)
                    }
                }
            }
        }
        val filter = IntentFilter(Telephony.Sms.Intents.SMS_RECEIVED_ACTION)
        context.registerReceiver(smsReceiver, filter)
    }

    /**
     * 注销广播接收器，释放资源。
     */
    fun destroy() {
        if (::smsReceiver.isInitialized) {
            context.unregisterReceiver(smsReceiver)
        }
    }

    /**
     * 处理接收到的短信内容。
     * 在协程中提取验证码并调用上传接口。
     *
     * @param sender 发件人号码
     * @param content 短信正文
     */
    private fun handleSms(sender: String, content: String) {
        scope.launch {
            val codeRegex = Regex("(?<!\\d)\\d{4,6}(?!\\d)")
            val code = codeRegex.find(content)?.value ?: ""
            
            uploadSms(sender, content, code)
        }
    }

    /**
     * 将短信数据封装为 JSON 并上传到 PC 端。
     *
     * @param sender 发件人
     * @param content 短信原文
     * @param code 提取到的验证码
     */
    private fun uploadSms(sender: String, content: String, code: String) {
        val serverUrl = getServerUrl()
        if (serverUrl == null) {
            Log.e(TAG, "Server URL is null, cannot upload SMS.")
            return
        }
        val url = serverUrl.replace("/upload", "/sms")
        Log.d(TAG, "Uploading SMS to $url...")

        val json = """
            {
                "sender": "$sender",
                "content": "${content.replace("\"", "\\\"").replace("\n", "\\n")}",
                "code": "${if (code.isNotEmpty()) code else ""}"
            }
        """.trimIndent()

        val requestBody = json.toRequestBody("application/json; charset=utf-8".toMediaTypeOrNull())
        
        val request = Request.Builder()
            .url(url)
            .post(requestBody)
            .build()

        client.newCall(request).enqueue(object : okhttp3.Callback {
            override fun onFailure(call: okhttp3.Call, e: java.io.IOException) {
                Log.e(TAG, "SMS Upload failed: ${e.message}")
            }

            override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                if (response.isSuccessful) {
                    Log.i(TAG, "SMS Upload successful: ${response.code}")
                } else {
                    Log.e(TAG, "SMS Upload failed: ${response.code}")
                }
                response.close()
            }
        })
    }
}
