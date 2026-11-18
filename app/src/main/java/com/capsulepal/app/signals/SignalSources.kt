package com.capsulepal.app.signals

import android.Manifest
import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import android.os.Process
import android.provider.CalendarContract
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import androidx.core.content.ContextCompat
import com.capsulepal.app.domain.SignalType
import com.capsulepal.app.domain.SystemSignal
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.time.ZoneId

abstract class SignalSource(protected val context: Context) {
    protected var receiverRegistered = false
    abstract fun start()
    abstract fun stop()
}

class PowerSignalSource(
    context: Context,
    private val emit: (SystemSignal) -> Unit
) : SignalSource(context) {

    private var lastPlugged = -1

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val plugged = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0)
            if (lastPlugged == -1) {
                lastPlugged = plugged
                return
            }
            if (plugged != lastPlugged) {
                lastPlugged = plugged
                emit(
                    SystemSignal(
                        if (plugged != 0) SignalType.POWER_CONNECTED else SignalType.POWER_DISCONNECTED
                    )
                )
            }
        }
    }

    override fun start() {
        if (receiverRegistered) return
        ContextCompat.registerReceiver(
            context, receiver,
            IntentFilter(Intent.ACTION_BATTERY_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        receiverRegistered = true
    }

    override fun stop() {
        if (!receiverRegistered) return
        runCatching { context.unregisterReceiver(receiver) }
        receiverRegistered = false
    }
}

class HeadsetSignalSource(
    context: Context,
    private val emit: (SystemSignal) -> Unit
) : SignalSource(context) {

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.getIntExtra("state", 0) == 1) {
                emit(SystemSignal(SignalType.HEADSET_PLUGGED))
            } else {
                emit(SystemSignal(SignalType.HEADSET_UNPLUGGED))
            }
        }
    }

    override fun start() {
        if (receiverRegistered) return
        ContextCompat.registerReceiver(
            context, receiver,
            IntentFilter(Intent.ACTION_HEADSET_PLUG),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        receiverRegistered = true
    }

    override fun stop() {
        if (!receiverRegistered) return
        runCatching { context.unregisterReceiver(receiver) }
        receiverRegistered = false
    }
}

/**
 * 读取当前 WiFi 名称。
 *
 * Android 12+ 读取 SSID 同时需要 ACCESS_WIFI_STATE（普通权限）与精确定位运行时权限；
 * 部分系统还要求定位服务处于开启状态，否则只会返回 <unknown ssid>。
 */
object WifiSsidReader {

    fun hasLocationPermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    fun locationEnabled(context: Context): Boolean {
        val manager = context.getSystemService(LocationManager::class.java) ?: return false
        return runCatching { manager.isLocationEnabled }.getOrDefault(false)
    }

    @Suppress("DEPRECATION")
    fun currentSsid(context: Context): String {
        if (!hasLocationPermission(context)) return ""
        val appContext = context.applicationContext
        val wifiManager = appContext.getSystemService(WifiManager::class.java)
        val direct = runCatching { wifiManager?.connectionInfo?.ssid }.getOrNull()
        sanitize(direct).takeIf { it.isNotEmpty() }?.let { return it }

        // 某些 ROM 的 connectionInfo 会被隐藏，退回网络能力里的 WifiInfo。
        val connectivityManager = appContext.getSystemService(ConnectivityManager::class.java)
        val capabilities = runCatching {
            connectivityManager?.getNetworkCapabilities(connectivityManager.activeNetwork)
        }.getOrNull()
        val info = capabilities?.transportInfo as? WifiInfo
        return sanitize(info?.ssid)
    }

    fun sanitize(raw: String?): String {
        val value = raw?.trim()?.removeSurrounding("\"")?.trim().orEmpty()
        return if (value.isEmpty() || value.equals("<unknown ssid>", ignoreCase = true)) {
            ""
        } else {
            value
        }
    }
}

class WifiSignalSource(
    context: Context,
    private val emit: (SystemSignal) -> Unit
) : SignalSource(context) {

    private val connectivityManager by lazy {
        context.getSystemService(ConnectivityManager::class.java)
    }
    private val wifiNetworks = Collections.newSetFromMap(ConcurrentHashMap<Network, Boolean>())
    /** 上一次识别到的 SSID；为空表示当前没有 WiFi 或名称不可读。 */
    @Volatile
    private var lastSsid = ""
    /** 上一次识别到的 WiFi 标识，名称不可读时用它区分是不是换了一个网络。 */
    @Volatile
    private var lastToken: Any? = null

    private fun currentSsid(): String = WifiSsidReader.currentSsid(context)

    private fun ssidOf(network: Network): String {
        val capabilities = runCatching {
            connectivityManager?.getNetworkCapabilities(network)
        }.getOrNull()
        val info = capabilities?.transportInfo as? WifiInfo
        return WifiSsidReader.sanitize(info?.ssid)
    }

    /**
     * 只要 SSID 变了就发一次连接信号。
     * 之前用 wasOnWifi 判断，WiFi 一直开着时换了路由器不会再触发，
     * 只有关掉再开才会回到「未连接 -> 连接」的状态。
     *
     * [token] 用来在网络名不可读（未授权定位/定位关闭）时兜底：
     * 换了网络标识也算一次连接，让「任意 WiFi」规则仍能触发。
     */
    @Synchronized
    private fun offer(ssidRaw: String?, token: Any?) {
        val ssid = WifiSsidReader.sanitize(ssidRaw)
        if (ssid.isNotEmpty()) {
            if (ssid == lastSsid) {
                if (token != null) lastToken = token
                return
            }
            lastSsid = ssid
            lastToken = token
            emit(SystemSignal(SignalType.WIFI_CONNECTED, detail = ssid))
            return
        }
        if (token != null && token != lastToken) {
            lastToken = token
            lastSsid = ""
            emit(SystemSignal(SignalType.WIFI_CONNECTED))
        }
    }

    private fun offerNetwork(network: Network) {
        val ssid = ssidOf(network).takeIf { it.isNotEmpty() } ?: currentSsid()
        offer(ssid, network)
    }

    @Synchronized
    private fun handleDisconnect() {
        if (wifiNetworks.isNotEmpty()) return
        val hadWifi = lastSsid.isNotEmpty() || lastToken != null
        lastSsid = ""
        lastToken = null
        if (hadWifi) emit(SystemSignal(SignalType.WIFI_DISCONNECTED))
    }

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            wifiNetworks.add(network)
            offerNetwork(network)
        }

        override fun onCapabilitiesChanged(
            network: Network,
            capabilities: NetworkCapabilities
        ) {
            if (!capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return
            wifiNetworks.add(network)
            offerNetwork(network)
        }

        override fun onLost(network: Network) {
            wifiNetworks.remove(network)
            // 切换到另一个 WiFi 时旧网络也会 onLost，等所有 WiFi 都断开才算真正断开。
            handleDisconnect()
        }
    }

    /**
     * 部分 ROM 在 WiFi 保持开启、只切换 SSID 时不会回调 NetworkCallback，
     * 这里再监听系统 WiFi 状态广播，两条路径靠 SSID 去重。
     */
    private val wifiReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                WifiManager.WIFI_STATE_CHANGED_ACTION -> {
                    val state = intent.getIntExtra(
                        WifiManager.EXTRA_WIFI_STATE,
                        WifiManager.WIFI_STATE_UNKNOWN
                    )
                    if (state == WifiManager.WIFI_STATE_DISABLED) {
                        wifiNetworks.clear()
                        handleDisconnect()
                    }
                }
                WifiManager.NETWORK_STATE_CHANGED_ACTION -> {
                    val info = wifiInfoExtra(intent)
                    val ssid = WifiSsidReader.sanitize(info?.ssid).ifEmpty { currentSsid() }
                    if (ssid.isNotEmpty()) {
                        val id = info?.networkId ?: -1
                        offer(ssid, if (id >= 0) "id:$id" else null)
                    }
                }
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun wifiInfoExtra(intent: Intent): WifiInfo? =
        if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra(WifiManager.EXTRA_WIFI_INFO, WifiInfo::class.java)
        } else {
            intent.getParcelableExtra(WifiManager.EXTRA_WIFI_INFO)
        }

    override fun start() {
        if (receiverRegistered) return
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .build()
        runCatching { connectivityManager?.registerNetworkCallback(request, callback) }
        val filter = IntentFilter().apply {
            addAction(WifiManager.NETWORK_STATE_CHANGED_ACTION)
            addAction(WifiManager.WIFI_STATE_CHANGED_ACTION)
        }
        runCatching {
            ContextCompat.registerReceiver(
                context,
                wifiReceiver,
                filter,
                ContextCompat.RECEIVER_NOT_EXPORTED
            )
        }
        receiverRegistered = true
        // 启动时只记录当前状态，不补发信号，避免打开 App 就弹旧提醒。
        lastSsid = currentSsid()
        lastToken = connectivityManager?.activeNetwork?.takeIf { network ->
            connectivityManager.getNetworkCapabilities(network)
                ?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
        }
        wifiNetworks.clear()
        runCatching {
            connectivityManager?.allNetworks?.forEach { network ->
                val capabilities = connectivityManager.getNetworkCapabilities(network)
                if (capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true) {
                    wifiNetworks.add(network)
                }
            }
        }
    }

    override fun stop() {
        if (!receiverRegistered) return
        runCatching { connectivityManager?.unregisterNetworkCallback(callback) }
        runCatching { context.unregisterReceiver(wifiReceiver) }
        receiverRegistered = false
        wifiNetworks.clear()
        lastSsid = ""
        lastToken = null
    }
}

/**
 * 安全读取蓝牙设备信息。
 *
 * Android 12+ 读取名称需要 BLUETOOTH_CONNECT；未授权时连 address 也可能抛
 * SecurityException，所以所有字段都必须在 runCatching 内部读取，不能作为
 * getOrDefault 的默认值提前求值（那会让异常直接冲出接收器）。
 */
object BluetoothDeviceReader {

    fun hasPermission(context: Context): Boolean =
        Build.VERSION.SDK_INT < 31 ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) ==
            PackageManager.PERMISSION_GRANTED

    /** 返回设备名（可能为空）与地址（可能为空），任何权限异常都退化为空串。 */
    fun describe(context: Context, device: BluetoothDevice?): Pair<String, String> {
        if (device == null) return "" to ""
        val address = runCatching { device.address }.getOrDefault("")
        if (!hasPermission(context)) return "" to address
        val name = runCatching { device.name }.getOrNull().orEmpty()
        return name to address
    }

    fun displayName(name: String, address: String): String =
        name.ifBlank { address.ifBlank { "未知蓝牙设备" } }
}

/**
 * 蓝牙连接状态。用 ACL_CONNECTED 覆盖车载、耳机、手环等所有设备；
 * 设备名需要 BLUETOOTH_CONNECT，未授权时退化成本机缓存的地址。
 */
class BluetoothSignalSource(
    context: Context,
    private val scope: CoroutineScope,
    private val emit: (SystemSignal) -> Unit
) : SignalSource(context) {

    private var pollJob: Job? = null

    /** 地址 -> 名称，仅用于轮询兜底时对比连接变化。 */
    private var knownDevices: Map<String, String>? = null

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val device = if (Build.VERSION.SDK_INT >= 33) {
                intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
            } ?: return
            val (name, address) = BluetoothDeviceReader.describe(context, device)
            val label = BluetoothDeviceReader.displayName(name, address)
            when (intent.action) {
                BluetoothDevice.ACTION_ACL_CONNECTED ->
                    emit(
                        SystemSignal(
                            SignalType.BLUETOOTH_CONNECTED,
                            detail = if (name.isNotBlank()) name else address.ifBlank { label },
                            extra = address
                        )
                    )
                BluetoothDevice.ACTION_ACL_DISCONNECTED ->
                    emit(
                        SystemSignal(
                            SignalType.BLUETOOTH_DISCONNECTED,
                            detail = if (name.isNotBlank()) name else address.ifBlank { label },
                            extra = address
                        )
                    )
            }
        }
    }

    override fun start() {
        if (!receiverRegistered) {
            // ACL_CONNECTED / ACL_DISCONNECTED 由蓝牙栈进程发送，不是 system_server。
            // Android 13+ 若用 RECEIVER_NOT_EXPORTED 会收不到，导致「连接特定蓝牙」永不触发。
            ContextCompat.registerReceiver(
                context, receiver,
                IntentFilter().apply {
                    addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
                    addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
                },
                ContextCompat.RECEIVER_EXPORTED
            )
            receiverRegistered = true
        }
        if (pollJob == null) {
            // 部分 ROM 会吞掉 ACL 广播，用 15 秒轮询已连接设备做兜底。
            pollJob = scope.launch {
                while (isActive) {
                    pollConnectedDevices()
                    delay(15_000L)
                }
            }
        }
    }

    private fun pollConnectedDevices() {
        // 没授权时读不到设备，也不能把空结果当成基线，否则授权后会把已连设备误判成“刚连上”。
        if (!BluetoothDeviceReader.hasPermission(context)) return
        val current = runCatching { connectedDevices(context) }.getOrNull() ?: return
        val previous = knownDevices
        knownDevices = current
        // 第一次只建立基线，避免 App 一启动就把当前已连设备当成新连接。
        if (previous == null) return
        current.forEach { (address, name) ->
            if (address !in previous) {
                emit(
                    SystemSignal(
                        SignalType.BLUETOOTH_CONNECTED,
                        detail = name.ifBlank { address },
                        extra = address
                    )
                )
            }
        }
        previous.forEach { (address, name) ->
            if (address !in current) {
                emit(
                    SystemSignal(
                        SignalType.BLUETOOTH_DISCONNECTED,
                        detail = name.ifBlank { address },
                        extra = address
                    )
                )
            }
        }
    }

    override fun stop() {
        pollJob?.cancel()
        pollJob = null
        knownDevices = null
        if (!receiverRegistered) return
        runCatching { context.unregisterReceiver(receiver) }
        receiverRegistered = false
    }
}

/** 读取所有已连接蓝牙（经典 + BLE），返回 地址 -> 名称。 */
internal fun connectedDevices(context: Context): Map<String, String> {
    if (!BluetoothDeviceReader.hasPermission(context)) return emptyMap()
    val manager = context.getSystemService(BluetoothManager::class.java) ?: return emptyMap()
    val adapter = manager.adapter ?: return emptyMap()
    if (!adapter.isEnabled) return emptyMap()
    val profiles = buildList {
        add(BluetoothProfile.GATT)
        if (Build.VERSION.SDK_INT >= 29) add(BluetoothProfile.GATT_SERVER)
        add(BluetoothProfile.A2DP)
        add(BluetoothProfile.HEADSET)
    }
    val result = linkedMapOf<String, String>()
    profiles.forEach { profile ->
        runCatching { manager.getConnectedDevices(profile) }
            .getOrDefault(emptyList())
            .forEach { device ->
                val (name, address) = BluetoothDeviceReader.describe(context, device)
                if (address.isNotBlank() && address !in result) {
                    result[address] = name
                }
            }
    }
    return result
}

object UsageAccess {
    fun granted(context: Context): Boolean {
        val appOps = context.getSystemService(AppOpsManager::class.java) ?: return false
        val mode = runCatching {
            appOps.unsafeCheckOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                Process.myUid(),
                context.packageName
            )
        }.getOrDefault(AppOpsManager.MODE_ERRORED)
        return mode == AppOpsManager.MODE_ALLOWED
    }
}

/**
 * 应用启动检测：每 3 秒用 queryEvents 增量拉取 ACTIVITY_RESUMED。
 * 聚合统计 queryUsageStats 在 Android 14 上存在延迟和漏事件问题，这里直接看原始事件流。
 * 熄屏或没有“使用情况访问”权限时自动跳过，不产生额外开销。
 */
class AppUsageSignalSource(
    context: Context,
    private val scope: CoroutineScope,
    private val emit: (SystemSignal) -> Unit
) : SignalSource(context) {

    private val usageStats by lazy { context.getSystemService(UsageStatsManager::class.java) }
    private val powerManager by lazy { context.getSystemService(PowerManager::class.java) }
    private val selfPackage = context.packageName
    private val homePackages by lazy {
        runCatching {
            context.packageManager.queryIntentActivities(
                Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0
            ).map { it.activityInfo.packageName }.toSet()
        }.getOrDefault(emptySet())
    }
    private var job: Job? = null
    private var lastPoll = System.currentTimeMillis()
    private var lastPackage = ""

    override fun start() {
        if (job != null) return
        lastPoll = System.currentTimeMillis()
        job = scope.launch {
            while (isActive) {
                delay(POLL_INTERVAL_MILLIS)
                if (!UsageAccess.granted(context)) {
                    // 授权前不要把游标留在过去，否则刚授权就会回放旧事件。
                    lastPoll = System.currentTimeMillis()
                    continue
                }
                if (powerManager?.isInteractive != true) {
                    lastPoll = System.currentTimeMillis()
                    continue
                }
                val now = System.currentTimeMillis()
                val usageEvents = runCatching {
                    usageStats?.queryEvents(lastPoll, now)
                }.getOrNull()
                lastPoll = now
                if (usageEvents == null) continue
                val event = UsageEvents.Event()
                while (usageEvents.hasNextEvent()) {
                    usageEvents.getNextEvent(event)
                    if (event.eventType != UsageEvents.Event.ACTIVITY_RESUMED) continue
                    val pkg = event.packageName ?: continue
                    val ignored = pkg == selfPackage ||
                        pkg in homePackages ||
                        pkg == "android" ||
                        pkg == "com.android.systemui"
                    if (ignored) {
                        // 回到桌面 / 打开自己 / 锁屏界面都意味着“离开上一个应用”，
                        // 必须清空去重状态，否则再次打开同一个应用时会被误判为重复事件。
                        lastPackage = ""
                        continue
                    }
                    if (pkg == lastPackage) continue
                    lastPackage = pkg
                    emit(
                        SystemSignal(
                            SignalType.APP_FOREGROUND,
                            detail = pkg,
                            extra = appLabel(pkg)
                        )
                    )
                }
            }
        }
    }

    private fun appLabel(pkg: String): String = runCatching {
        val info = context.packageManager.getApplicationInfo(pkg, 0)
        context.packageManager.getApplicationLabel(info).toString()
    }.getOrDefault(pkg)

    override fun stop() {
        job?.cancel()
        job = null
    }

    private companion object {
        const val POLL_INTERVAL_MILLIS = 3_000L
    }
}

class CallSignalSource(
    context: Context,
    private val emit: (SystemSignal) -> Unit
) : SignalSource(context) {

    private val telephonyManager by lazy {
        context.getSystemService(TelephonyManager::class.java)
    }
    private var lastState = TelephonyManager.CALL_STATE_IDLE
    private var callback: TelephonyCallback? = null

    override fun start() {
        if (receiverRegistered) return
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_STATE)
            != PackageManager.PERMISSION_GRANTED
        ) return
        if (Build.VERSION.SDK_INT < 31) return
        val telephony = telephonyManager ?: return

        val telephonyCallback = object : TelephonyCallback(), TelephonyCallback.CallStateListener {
            override fun onCallStateChanged(state: Int) {
                when {
                    state == TelephonyManager.CALL_STATE_RINGING && lastState != state ->
                        emit(SystemSignal(SignalType.CALL_RINGING))
                    state == TelephonyManager.CALL_STATE_OFFHOOK && lastState != state ->
                        emit(SystemSignal(SignalType.CALL_OFFHOOK))
                    // 只有真正接通过的电话才叫“通话结束”；
                    // 响铃后未接听（未接 / 拒接）不应触发下一件事提醒。
                    state == TelephonyManager.CALL_STATE_IDLE &&
                        lastState == TelephonyManager.CALL_STATE_OFFHOOK ->
                        emit(SystemSignal(SignalType.CALL_ENDED))
                }
                lastState = state
            }
        }
        callback = telephonyCallback
        runCatching {
            telephony.registerTelephonyCallback(
                ContextCompat.getMainExecutor(context), telephonyCallback
            )
            receiverRegistered = true
        }
    }

    override fun stop() {
        val telephony = telephonyManager ?: return
        callback?.let { runCatching { telephony.unregisterTelephonyCallback(it) } }
        callback = null
        receiverRegistered = false
    }
}

data class CalendarEvent(
    val id: Long,
    val beginMillis: Long,
    val endMillis: Long,
    val title: String
)

class CalendarSignalSource(
    context: Context,
    private val emit: (SystemSignal) -> Unit
) : SignalSource(context) {

    private var lastEmittedBegin = 0L
    private var lastEmittedAt = 0L

    fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALENDAR) ==
            PackageManager.PERMISSION_GRANTED

    /** 查询 [fromMillis, toMillis) 内开始的非全天日程，按开始时间升序。 */
    private fun query(fromMillis: Long, toMillis: Long): List<CalendarEvent> {
        if (!hasPermission()) return emptyList()
        val uri = CalendarContract.Instances.CONTENT_URI.buildUpon()
            .appendPath(fromMillis.toString())
            .appendPath(toMillis.toString())
            .build()
        val projection = arrayOf(
            CalendarContract.Instances.EVENT_ID,
            CalendarContract.Instances.BEGIN,
            CalendarContract.Instances.END,
            CalendarContract.Instances.TITLE,
            CalendarContract.Instances.ALL_DAY
        )
        return runCatching {
            context.contentResolver.query(uri, projection, null, null, "begin ASC")?.use { cursor ->
                buildList {
                    while (cursor.moveToNext()) {
                        // 全天事件没有明确的“开始前 N 分钟”，直接忽略。
                        if (cursor.getInt(4) == 1) continue
                        add(
                            CalendarEvent(
                                id = cursor.getLong(0),
                                beginMillis = cursor.getLong(1),
                                endMillis = cursor.getLong(2),
                                title = cursor.getString(3) ?: "日历事件"
                            )
                        )
                    }
                }
            }
        }.getOrNull() ?: emptyList()
    }

    /** 日历闹钟用：未来 [horizonMillis] 内最近的一场日程。 */
    fun firstUpcoming(horizonMillis: Long = 24 * 60 * 60_000L): CalendarEvent? {
        val now = System.currentTimeMillis()
        return query(now, now + horizonMillis).firstOrNull { it.beginMillis > now }
    }

    fun checkSoon(leadMinutes: Int) {
        val now = System.currentTimeMillis()
        val lead = leadMinutes.coerceIn(1, 180) * 60_000L
        // 精确闹钟正是在“开始时间 - 提前量”这一刻触发的，此时日程开始时间
        // 恰好等于查询上界；Instances 区间是左闭右开，必须留少量余量，
        // 否则会刚好漏掉这场日程，表现为“日历临近提醒永远不响”。
        val event = query(now, now + lead + CALENDAR_QUERY_SLACK_MILLIS).firstOrNull {
            it.beginMillis > now
        } ?: return
        // 同一场日程 1 分钟内只发一次，避免启动/设置变化时重复入队；
        // 但超过 1 分钟后允许重试，这样被免打扰、冷却或去重拦下的提醒
        // 还能在下一轮 15 分钟检查里补上，而不是“发过一次就永远不响”。
        if (event.beginMillis == lastEmittedBegin &&
            now - lastEmittedAt < CALENDAR_REEMIT_WINDOW_MILLIS
        ) {
            return
        }
        lastEmittedBegin = event.beginMillis
        lastEmittedAt = now
        emit(
            SystemSignal(
                type = SignalType.CALENDAR_SOON,
                atMillis = now,
                detail = event.title,
                eventStartMillis = event.beginMillis,
                eventEndMillis = event.endMillis
            )
        )
    }

    override fun start() = Unit

    override fun stop() = Unit

    private companion object {
        const val CALENDAR_QUERY_SLACK_MILLIS = 2_000L
        const val CALENDAR_REEMIT_WINDOW_MILLIS = 60_000L
    }
}

object MediaSignalStore {
    private val _playing = kotlinx.coroutines.flow.MutableStateFlow(false)
    val playing: kotlinx.coroutines.flow.StateFlow<Boolean> = _playing

    fun update(value: Boolean) {
        _playing.value = value
    }
}

class MediaSignalSource(
    context: Context,
    private val emit: (SystemSignal) -> Unit
) : SignalSource(context) {

    override fun start() = Unit
    override fun stop() = Unit

    fun onPlayingChanged(playing: Boolean) {
        emit(SystemSignal(if (playing) SignalType.MEDIA_STARTED else SignalType.MEDIA_STOPPED))
    }
}

object CalendarMatcher {
    fun isToday(epochMillis: Long): Boolean =
        java.time.Instant.ofEpochMilli(epochMillis)
            .atZone(ZoneId.systemDefault())
            .toLocalDate() == LocalDate.now()
}
