package com.duoduojuzi

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.delay
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.Collections

/**
 * @Author: DuoDuoJuZi
 * @Date: 2026-02-19
 */

// Data class for DeviceInfo
data class DeviceInfo(val name: String, val ip: String, val port: Int)

class MainActivity : ComponentActivity() {

    private lateinit var nsdManager: NsdManager
    private var discoveryListener: NsdManager.DiscoveryListener? = null
    
    private val discoveredDevices = mutableStateListOf<DeviceInfo>()
    private var isServiceRunning by mutableStateOf(false)
    private var selectedDeviceIp by mutableStateOf<String?>(null)

    private val SERVICE_TYPE = "_photosync._tcp."
    private val TAG = "MainActivity"

    /**
     * Activity 生命周期回调：创建时调用。
     * 初始化界面、状态恢复及自动开始服务发现。
     *
     * @param savedInstanceState 保存的实例状态
     */
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        initNsdManager()
        
        val prefs = getSharedPreferences("FastSyncPrefs", Context.MODE_PRIVATE)
        selectedDeviceIp = prefs.getString("target_ip", null)
        
        isServiceRunning = FastSyncService.isRunning

        setContent {
            FastSyncTheme {
                MainScreen(
                    discoveredDevices = discoveredDevices,
                    isServiceRunning = isServiceRunning,
                    selectedDeviceIp = selectedDeviceIp,
                    onStartService = { checkPermissionsAndStart() },
                    onStopService = { stopPhotoService() },
                    onDeviceSelected = { device -> onDeviceSelected(device) },
                    onManualAdd = { ip, port -> addManualDevice(ip, port) },
                    onScan = { restartDiscovery() }
                )
            }
        }
        
        startDiscovery()
    }

    /**
     * Activity 生命周期回调：恢复前台时调用。
     * 重新检查服务运行状态。
     */
    override fun onResume() {
        super.onResume()
        isServiceRunning = FastSyncService.isRunning
    }

    /**
     * Activity 生命周期回调：销毁时调用。
     * 停止 mDNS 服务发现以释放资源。
     */
    override fun onDestroy() {
        super.onDestroy()
        stopDiscovery()
    }

    /**
     * 初始化 NsdManager 系统服务。
     */
    private fun initNsdManager() {
        nsdManager = getSystemService(Context.NSD_SERVICE) as NsdManager
    }

    /**
     * 启动 mDNS 服务发现。
     * 监听 _photosync._tcp 类型的服务，并将发现的设备添加到列表中。
     */
    private fun startDiscovery() {
        if (discoveryListener != null) return

        discoveredDevices.clear()
        
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
                            val name = serviceInfo.serviceName
                            
                            if (host != null) {
                                val deviceInfo = DeviceInfo(name, host.hostAddress ?: "", port)
                                runOnUiThread {
                                    addDevice(deviceInfo)
                                }
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

        try {
            nsdManager.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discoveryListener)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start discovery", e)
        }
    }

    /**
     * 停止 mDNS 服务发现。
     */
    private fun stopDiscovery() {
        discoveryListener?.let {
            try {
                nsdManager.stopServiceDiscovery(it)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to stop discovery", e)
            }
            discoveryListener = null
        }
    }

    /**
     * 重启服务发现流程。
     * 用于用户手动刷新设备列表。
     */
    private fun restartDiscovery() {
        stopDiscovery()
        startDiscovery()
        Toast.makeText(this, "正在重新扫描...", Toast.LENGTH_SHORT).show()
    }

    /**
     * 将发现的设备添加到列表中。
     * 包含简单的去重逻辑。
     *
     * @param device 设备信息对象
     */
    private fun addDevice(device: DeviceInfo) {
        if (discoveredDevices.none { it.ip == device.ip && it.port == device.port }) {
            discoveredDevices.add(device)
        }
    }
    
    /**
     * 手动添加设备 IP 和端口。
     *
     * @param ip 目标 IP 地址
     * @param port 目标端口
     */
    private fun addManualDevice(ip: String, port: Int) {
        val device = DeviceInfo("手动添加设备", ip, port)
        addDevice(device)
        onDeviceSelected(device)
    }

    /**
     * 处理设备选中事件。
     * 保存设备信息并通知后台服务更新目标地址。
     *
     * @param device 被选中的设备信息
     */
    private fun onDeviceSelected(device: DeviceInfo) {
        selectedDeviceIp = device.ip
        saveDeviceToPrefs(device)
        
        Toast.makeText(this, "已选择：${device.name}", Toast.LENGTH_SHORT).show()

        val intent = Intent(this, FastSyncService::class.java).apply {
            action = FastSyncService.ACTION_UPDATE_SERVER_URL
            putExtra(FastSyncService.EXTRA_SERVER_IP, device.ip)
            putExtra(FastSyncService.EXTRA_SERVER_PORT, device.port)
        }
        startService(intent)
    }

    /**
     * 将选中的设备信息保存到 SharedPreferences。
     *
     * @param device 设备信息
     */
    private fun saveDeviceToPrefs(device: DeviceInfo) {
        val sharedPref = getSharedPreferences("FastSyncPrefs", Context.MODE_PRIVATE)
        with(sharedPref.edit()) {
            putString("target_ip", device.ip)
            putInt("target_port", device.port)
            apply()
        }
    }

    private val permissionsLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.entries.all { it.value }
        if (allGranted) {
            checkBatteryOptimizationAndStartService()
        } else {
            Toast.makeText(this, "需要所有权限才能运行", Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * 检查所需权限并启动服务。
     * 根据 Android 版本动态申请 READ_MEDIA_IMAGES 或 READ_EXTERNAL_STORAGE。
     */
    private fun checkPermissionsAndStart() {
        val permissions = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.READ_MEDIA_IMAGES)
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            permissions.add(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
        
        // Add SMS permissions
        permissions.add(Manifest.permission.READ_SMS)
        permissions.add(Manifest.permission.RECEIVE_SMS)

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
     * 检查电池优化白名单状态并启动服务。
     * 若未在白名单中，尝试引导用户跳转设置页面。
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
            }
        }
        
        startPhotoService()
    }

    /**
     * 启动 PhotoSyncService 后台服务。
     * 适配 Android O 前台服务启动要求。
     */
    private fun startPhotoService() {
        val intent = Intent(this, FastSyncService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
        isServiceRunning = true
        Toast.makeText(this, "服务已启动", Toast.LENGTH_SHORT).show()
    }
    
    /**
     * 停止 PhotoSyncService 服务。
     */
    private fun stopPhotoService() {
        val intent = Intent(this, FastSyncService::class.java)
        stopService(intent)
        isServiceRunning = false
        FastSyncService.isRunning = false
        Toast.makeText(this, "服务已停止", Toast.LENGTH_SHORT).show()
    }
}

val BackgroundLight = Color(0xFFFFF8E7)
val BackgroundDark = Color(0xFF363430)
val PrimaryColor = Color(0xFF007BFF) // Unified button color
val OnPrimaryColor = Color(0xFFFFFFFF)

@Composable
fun FastSyncTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) {
        darkColorScheme(
            primary = PrimaryColor,
            onPrimary = OnPrimaryColor,
            background = BackgroundDark,
            surface = BackgroundDark,
            onBackground = Color.White,
            onSurface = Color.White
        )
    } else {
        lightColorScheme(
            primary = PrimaryColor,
            onPrimary = OnPrimaryColor,
            background = BackgroundLight,
            surface = BackgroundLight,
            onBackground = Color.Black,
            onSurface = Color.Black
        )
    }

    val view = androidx.compose.ui.platform.LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as android.app.Activity).window
            window.statusBarColor = colorScheme.background.toArgb()
            val wic = androidx.core.view.WindowCompat.getInsetsController(window, view)
            wic.isAppearanceLightStatusBars = !darkTheme
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        content = content
    )
}

@Composable
fun MainScreen(
    discoveredDevices: List<DeviceInfo>,
    isServiceRunning: Boolean,
    selectedDeviceIp: String?,
    onStartService: () -> Unit,
    onStopService: () -> Unit,
    onDeviceSelected: (DeviceInfo) -> Unit,
    onManualAdd: (String, Int) -> Unit,
    onScan: () -> Unit
) {
    var selectedTab by remember { mutableStateOf(0) }
    
    // 预留的设备名称状态
    var deviceName by remember { mutableStateOf(Build.MODEL) }

    Scaffold(
        bottomBar = {
            NavigationBar(
                containerColor = MaterialTheme.colorScheme.surface
            ) {
                NavigationBarItem(
                    icon = { Icon(Icons.Default.Wifi, contentDescription = "首页") },
                    label = { Text("首页") },
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 }
                )
                NavigationBarItem(
                    icon = { Icon(Icons.Default.Computer, contentDescription = "设备") },
                    label = { Text("设备") },
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 }
                )
                NavigationBarItem(
                    icon = { Icon(Icons.Default.Check, contentDescription = "设置") }, // Replace with Settings icon if available, using Check for now
                    label = { Text("设置") },
                    selected = selectedTab == 2,
                    onClick = { selectedTab = 2 }
                )
            }
        }
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .padding(paddingValues)
        ) {
            when (selectedTab) {
                0 -> HomeTab(isServiceRunning, onStartService, onStopService)
                1 -> DevicesTab(discoveredDevices, selectedDeviceIp, onDeviceSelected, onManualAdd, onScan)
                2 -> SettingsTab(deviceName, onNameChange = { deviceName = it })
            }
        }
    }
}

@Composable
fun HomeTab(
    isServiceRunning: Boolean,
    onStartService: () -> Unit,
    onStopService: () -> Unit
) {
    var syncPhotos by remember { mutableStateOf(true) }
    var syncClipboard by remember { mutableStateOf(true) }
    var syncSms by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        HeaderCard(isServiceRunning)
        
        Spacer(modifier = Modifier.height(24.dp))
        
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            shape = RoundedCornerShape(12.dp),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f))
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "同步数据选择",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onBackground
                )
                Spacer(modifier = Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = syncPhotos, onCheckedChange = { syncPhotos = it })
                    Text("图片", color = MaterialTheme.colorScheme.onBackground)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = syncClipboard, onCheckedChange = { syncClipboard = it })
                    Text("剪切板", color = MaterialTheme.colorScheme.onBackground)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = syncSms, onCheckedChange = { syncSms = it })
                    Text("短信", color = MaterialTheme.colorScheme.onBackground)
                }
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        Button(
            onClick = if (isServiceRunning) onStopService else onStartService,
            modifier = Modifier.fillMaxWidth().height(50.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary
            ),
            shape = RoundedCornerShape(25.dp)
        ) {
            Text(if (isServiceRunning) "暂停服务" else "开启服务", fontSize = 16.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
fun DevicesTab(
    discoveredDevices: List<DeviceInfo>,
    selectedDeviceIp: String?,
    onDeviceSelected: (DeviceInfo) -> Unit,
    onManualAdd: (String, Int) -> Unit,
    onScan: () -> Unit
) {
    var showManualDialog by remember { mutableStateOf(false) }
    var showPinDialog by remember { mutableStateOf(false) }
    var pendingDevice by remember { mutableStateOf<DeviceInfo?>(null) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "发现设备",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground
            )
            Row {
                TextButton(onClick = onScan) { Text("刷新") }
                TextButton(onClick = { showManualDialog = true }) { Text("手动添加") }
            }
        }
        
        Spacer(modifier = Modifier.height(16.dp))

        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            items(discoveredDevices) { device ->
                DeviceItem(
                    device = device,
                    isSelected = device.ip == selectedDeviceIp,
                    onClick = { 
                        // 点击设备时预留输入PIN码逻辑
                        pendingDevice = device
                        showPinDialog = true
                    }
                )
            }
        }
    }

    if (showManualDialog) {
        ManualIpDialog(
            onDismiss = { showManualDialog = false },
            onConfirm = { ip, port -> 
                onManualAdd(ip, port)
                showManualDialog = false
            }
        )
    }

    if (showPinDialog && pendingDevice != null) {
        PinDialog(
            onDismiss = { showPinDialog = false },
            onConfirm = { pin ->
                // 预留校验逻辑，输入正确后才可同步
                onDeviceSelected(pendingDevice!!)
                showPinDialog = false
            }
        )
    }
}

@Composable
fun SettingsTab(deviceName: String, onNameChange: (String) -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        Text(
            text = "设置",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground
        )
        Spacer(modifier = Modifier.height(24.dp))
        
        OutlinedTextField(
            value = deviceName,
            onValueChange = onNameChange,
            label = { Text("设备名称") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "默认使用当前手机名称",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f)
        )
    }
}

@Composable
fun PinDialog(onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var pin by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("输入设备 PIN 码") },
        text = {
            OutlinedTextField(
                value = pin,
                onValueChange = { if (it.length <= 4) pin = it },
                label = { Text("4位数 PIN 码") },
                singleLine = true
            )
        },
        confirmButton = {
            Button(onClick = { if (pin.length == 4) onConfirm(pin) }) { Text("确定") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}

@Composable
fun HeaderCard(isServiceRunning: Boolean) {
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val scale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = if (isServiceRunning) 1.1f else 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1000),
            repeatMode = RepeatMode.Reverse
        ),
        label = "scale"
    )

    val localIp = remember { getLocalIpAddress() }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .height(160.dp),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primary)
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Icon(
                    imageVector = Icons.Default.Wifi,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier
                        .size(48.dp)
                        .scale(if (isServiceRunning) scale else 1f)
                )
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = if (isServiceRunning) "服务运行中" else "服务未启动",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onPrimary
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "IP: $localIp",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.8f)
                )
            }
        }
    }
}

@Composable
fun DeviceItem(device: DeviceInfo, isSelected: Boolean, onClick: () -> Unit) {
    val borderColor = if (isSelected) MaterialTheme.colorScheme.primary else Color.Transparent

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, borderColor)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.1f), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Computer,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
            }
            Spacer(modifier = Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = device.name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onBackground
                )
                Text(
                    text = "${device.ip}:${device.port}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f)
                )
            }
            if (isSelected) {
                Icon(
                    imageVector = Icons.Default.Check,
                    contentDescription = "Selected",
                    tint = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}

@Composable
fun ManualIpDialog(onDismiss: () -> Unit, onConfirm: (String, Int) -> Unit) {

    var ip by remember { mutableStateOf("") }
    var port by remember { mutableStateOf("3000") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("手动添加设备") },
        text = {
            Column {
                OutlinedTextField(
                    value = ip,
                    onValueChange = { ip = it },
                    label = { Text("IP 地址 (例如 192.168.1.5)") },
                    singleLine = true
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = port,
                    onValueChange = { port = it },
                    label = { Text("端口 (默认 3000)") },
                    singleLine = true
                )
            }
        },
        confirmButton = {
            Button(onClick = {
                if (ip.isNotBlank()) {
                    onConfirm(ip, port.toIntOrNull() ?: 3000)
                }
            }) {
                Text("确定")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消")
            }
        }
    )
}

fun getLocalIpAddress(): String {
    try {
        val interfaces = Collections.list(NetworkInterface.getNetworkInterfaces())
        for (intf in interfaces) {
            val addrs = Collections.list(intf.inetAddresses)
            for (addr in addrs) {
                if (!addr.isLoopbackAddress && addr is Inet4Address) {
                    return addr.hostAddress ?: "未知"
                }
            }
        }
    } catch (ex: Exception) {
        ex.printStackTrace()
    }
    return "无法获取 IP"
}
