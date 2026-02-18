package com.duoduojuzi

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.duoduojuzi.R

/**
 * @Author: DuoDuoJuZi
 * @Date: 2026-02-18
 */
class MainActivity : AppCompatActivity() {

    private lateinit var tvStatus: TextView
    private lateinit var btnStartService: Button

    private val permissionsLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.entries.all { it.value }
        if (allGranted) {
            checkBatteryOptimizationAndStartService()
        } else {
            Toast.makeText(this, "需要所有权限才能运行", Toast.LENGTH_SHORT).show()
            updateStatus("权限不足")
        }
    }

    /**
     * 初始化 Activity 界面与事件监听。
     *
     * @param savedInstanceState 保存的实例状态 Bundle
     */
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvStatus = findViewById(R.id.tvStatus)
        btnStartService = findViewById(R.id.btnStartService)

        btnStartService.setOnClickListener {
            checkPermissions()
        }
        
        updateStatus(if (PhotoSyncService.isRunning) getString(R.string.service_running) else getString(R.string.service_stopped))
    }

    /**
     * 检查并请求运行所需的运行时权限。
     * 根据 Android 版本动态决定请求 READ_MEDIA_IMAGES 或 READ_EXTERNAL_STORAGE。
     */
    private fun checkPermissions() {
        val permissions = mutableListOf<String>()
        
        // Android 13+ (Tiramisu)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.READ_MEDIA_IMAGES)
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            permissions.add(Manifest.permission.READ_EXTERNAL_STORAGE)
        }

        val missingPermissions = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (missingPermissions.isNotEmpty()) {
            permissionsLauncher.launch(missingPermissions.toTypedArray())
        } else {
            checkBatteryOptimizationAndStartService()
        }
    }

    /**
     * 检查电池优化设置并启动后台服务。
     * 若应用受电池优化限制，将引导用户跳转至设置页面。
     */
    private fun checkBatteryOptimizationAndStartService() {
        val packageName = packageName
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        
        if (!pm.isIgnoringBatteryOptimizations(packageName)) {
            Toast.makeText(this, "请允许忽略电池优化以保持后台运行", Toast.LENGTH_LONG).show()
            try {
                val intent = Intent().apply {
                    action = Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS
                    data = Uri.parse("package:$packageName")
                }
                startActivity(intent)
            } catch (e: Exception) {
                e.printStackTrace()
                try {
                     startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                } catch (e2: Exception) {
                    Toast.makeText(this, "无法打开电池优化设置，请手动开启", Toast.LENGTH_LONG).show()
                }
            }
        }
        
        startService()
    }

    /**
     * 启动 PhotoSyncService 前台服务。
     * 适配 Android O 及以上版本的启动方式。
     */
    private fun startService() {
        val intent = Intent(this, PhotoSyncService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
        updateStatus(getString(R.string.service_running))
        Toast.makeText(this, "服务已启动", Toast.LENGTH_SHORT).show()
    }

    /**
     * 更新 UI 上的服务状态文本。
     *
     * @param status 要显示的状态描述字符串
     */
    private fun updateStatus(status: String) {
        tvStatus.text = getString(R.string.status_label) + status
    }
}
