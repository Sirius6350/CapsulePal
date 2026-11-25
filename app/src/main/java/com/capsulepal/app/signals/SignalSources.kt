package com.capsulepal.app.signals

import android.Manifest
import android.app.AppOpsManager
import android.app.PendingIntent
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
import android.os.SystemClock
import android.provider.CalendarContract
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import androidx.core.content.ContextCompat
import com.capsulepal.app.domain.ReminderTrace
import com.capsulepal.app.domain.SignalType
import com.capsulepal.app.domain.SystemSignal
import com.capsulepal.app.scheduler.SystemSignalReceiver
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

    /** Android 13+ 的「附近的设备」：授权后不依赖定位权限/定位开关也能读到网络名。 */
    fun hasNearbyPermission(context: Context): Boolean =
        Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(
            context, Manifest.permission.NEARBY_WIFI_DEVICES
        ) == PackageManager.PERMISSION_GRANTED

    /**
     * 能否读到 WiFi 名称。
     *
     * 只有定位权限时，Android 10+ 会把「仅在使用中允许」的应用在后台读到的 SSID
     * 抹成 <unknown ssid>，表现就是「连上 WiFi 没提醒，打开 App 才提醒」。
     * Android 13+ 的「附近的设备」权限不受前后台限制，是后台读名称的主路径。
     */
    fun canReadSsid(context: Context): Boolean =
        hasLocationPermission(context) || hasNearbyPermission(context)

    /**
     * 后台也能读到名称的权限。Android 13+ 只给定位权限时，应用退到后台会被
     * 系统抹掉 SSID（只剩「仅在使用中允许」），所以后台要「附近的设备」；
     * 更低版本没有这个权限，只能靠定位。
     */
    fun hasSsidPermission(context: Context): Boolean =
        if (Build.VERSION.SDK_INT >= 33) hasNearbyPermission(context)
        else hasLocationPermission(context)

    /** 定位是否已设为「始终允许」（Android 10+ 需要后台定位才允许后台读 SSID）。 */
    fun hasBackgroundLocation(context: Context): Boolean =
        Build.VERSION.SDK_INT < 29 || ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_BACKGROUND_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

    fun locationEnabled(context: Context): Boolean {
        val manager = context.getSystemService(LocationManager::class.java) ?: return false
        return runCatching { manager.isLocationEnabled }.getOrDefault(false)
    }

    @Suppress("DEPRECATION")
    fun currentSsid(context: Context): String {
        if (!canReadSsid(context)) return ""
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
    private val scope: CoroutineScope,
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

    /** 上次读 SSID 的连接标识与已读次数：每条新连接最多读 3 次，之后不再读。 */
    private var readToken: Any? = null
    private var readCount = 0
    /** 上一次带名称发出连接信号的时间，用于识别「连回同一个 WiFi」。 */
    @Volatile
    private var lastEmitAt = 0L

    /** 名称暂时读不到时的补读任务。 */
    private var ssidRetryJob: Job? = null

    /**
     * 启动时已经连着、但名称当时读不出来的网络。
     * 它第一次成功读出名称只补基线、不补提醒，避免打开 App 就弹旧提醒。
     */
    private var baselineToken: Any? = null

    /** 后台唤醒时名称暂不可读：先按「任意 WiFi」提醒，等名称可读后只补基线。 */
    @Volatile
    private var pendingSsidAdopt = false

    /** 系统级网络唤醒：进程被回收后，连上 WiFi 也能把 App 拉起来判断规则。 */
    private var wakeRegistered = false
    private var wakePendingIntent: PendingIntent? = null

    /** 跨进程记录「最近一次提醒过的 WiFi」，避免冷启动时重复提醒同一次连接。 */
    private val prefs by lazy {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    private fun currentSsid(): String = WifiSsidReader.currentSsid(context)

    /** 当前活动的 WiFi 网络；VPN 等场景下活动网络不是 WiFi 时，取任意已连接的 WiFi。 */
    private fun connectedWifiNetwork(): Network? {
        val manager = connectivityManager ?: return null
        val active = manager.activeNetwork
        if (active != null) {
            val capabilities = runCatching {
                manager.getNetworkCapabilities(active)
            }.getOrNull()
            if (capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true) {
                return active
            }
        }
        return runCatching {
            manager.allNetworks.firstOrNull { network ->
                manager.getNetworkCapabilities(network)
                    ?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
            }
        }.getOrNull()
    }

    /**
     * 只要 SSID 变了就发一次连接信号。
     * 之前用 wasOnWifi 判断，WiFi 一直开着时换了路由器不会再触发，
     * 只有关掉再开才会回到「未连接 -> 连接」的状态。
     *
     * [token] 用来在网络名不可读（未授权定位/定位关闭）时兜底：
     * 换了网络标识也算一次连接，让「任意 WiFi」规则仍能触发。
     */
    private fun offer(ssidRaw: String?, token: Any?) {
        val ssid = WifiSsidReader.sanitize(ssidRaw)
        if (ssid.isNotEmpty()) recordSeen(ssid, true)
        offerNormalized(ssid, token)
    }

    @Synchronized
    private fun offerNormalized(ssid: String, token: Any?) {
        if (ssid.isNotEmpty()) {
            val now = SystemClock.elapsedRealtime()
            if (pendingSsidAdopt && (token == null || lastToken == null || token == lastToken)) {
                // 后台唤醒时先按「任意 WiFi」提醒过，这里只把名称补进基线。
                pendingSsidAdopt = false
                lastSsid = ssid
                if (token != null) lastToken = token
                baselineToken = null
                return
            }
            pendingSsidAdopt = false
            if (ssid == lastSsid) {
                // 名称没变但网络标识变了：多半是断开后又连回同一个 WiFi。
                // 一次连接会同时走回调和广播两条路，间隔很短的变化不当成重连。
                val switched = token != null && lastToken != null && token != lastToken
                if (switched && now - lastEmitAt >= SAME_SSID_REEMIT_GAP_MILLIS) {
                    lastToken = token
                    lastEmitAt = now
                    rememberReminderSsid(ssid)
                    emit(SystemSignal(SignalType.WIFI_CONNECTED, detail = ssid))
                } else if (token != null) {
                    lastToken = token
                }
                baselineToken = null
                return
            }
            if (token != null && token == baselineToken) {
                // 启动时就已连着这个网络，只是名称晚了一点才读出来：补基线，不补提醒。
                lastSsid = ssid
                lastToken = token
                baselineToken = null
                return
            }
            lastSsid = ssid
            lastToken = token
            baselineToken = null
            lastEmitAt = now
            rememberReminderSsid(ssid)
            emit(SystemSignal(SignalType.WIFI_CONNECTED, detail = ssid))
            return
        }
        if (token != null && token != lastToken) {
            lastToken = token
            lastSsid = ""
            lastEmitAt = SystemClock.elapsedRealtime()
            recordSeen("", true)
            emit(SystemSignal(SignalType.WIFI_CONNECTED))
        }
    }

    /**
     * 注册/注销系统级网络唤醒。
     *
     * WiFi 连接广播不在清单接收器可用的例外列表里，进程被回收后，
     * 只有 PendingIntent 版的网络回调能把 App 拉起来判断规则。
     */
    @Synchronized
    fun setWakeEnabled(enabled: Boolean) {
        val manager = connectivityManager ?: return
        if (enabled) {
            if (wakeRegistered) return
            val pending = PendingIntent.getBroadcast(
                context,
                WAKE_REQUEST_CODE,
                Intent(context, SystemSignalReceiver::class.java)
                    .setAction(SystemSignalReceiver.ACTION_WIFI_AVAILABLE),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val request = NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .build()
            val registered = runCatching {
                manager.registerNetworkCallback(request, pending)
            }.isSuccess
            if (registered) {
                wakePendingIntent = pending
                wakeRegistered = true
            }
        } else {
            if (!wakeRegistered) return
            wakePendingIntent?.let { pending ->
                runCatching { manager.unregisterNetworkCallback(pending) }
            }
            wakePendingIntent = null
            wakeRegistered = false
        }
    }

    /**
     * 后台唤醒路径：这次连接是否还需要补发提醒。
     * 进程内回调已经处理过的连接不再重复提醒，冷启动则按跨进程记录去重。
     *
     * @param adoptWhenUnreadable 名称读不到且规则是「任意 WiFi」时先提醒一次，
     * 之后名称可读只补基线，避免同一次连接弹两条。
     */
    @Synchronized
    fun claimWakeConnection(ssidRaw: String?, adoptWhenUnreadable: Boolean): Boolean {
        val ssid = WifiSsidReader.sanitize(ssidRaw)
        val now = SystemClock.elapsedRealtime()
        if (now - lastEmitAt < WAKE_DUPLICATE_WINDOW_MILLIS) {
            if (ssid.isNotEmpty()) recordSeen(ssid, true)
            return false
        }
        val token = connectedWifiNetwork()
        // 只有「刚刚提醒过这个名字」才算同一次连接。
        // 之前用永久记录判断，进程在连着的时候被回收就再也清不掉，
        // 之后每次连回同一个 WiFi 都被当成重复而静默。
        val sameConnection = ssid.isNotEmpty() && ssid == reminderSsid() &&
            System.currentTimeMillis() - lastRemindedAt() < SAME_CONNECTION_DEDUPE_MILLIS
        if (sameConnection) {
            lastSsid = ssid
            if (token != null) lastToken = token
            baselineToken = null
            pendingSsidAdopt = false
            recordSeen(ssid, true)
            return false
        }
        if (ssid.isEmpty() && !adoptWhenUnreadable) {
            // 规则指定了 WiFi 名称，读不到名称就无法判断，等名称可读后再提醒。
            return false
        }
        lastEmitAt = now
        baselineToken = null
        if (ssid.isNotEmpty()) {
            lastSsid = ssid
            if (token != null) lastToken = token
            pendingSsidAdopt = false
            rememberReminderSsid(ssid)
            recordSeen(ssid, true)
        } else {
            // 名称暂时读不到：先按「任意 WiFi」提醒，等名称可读后只补基线。
            lastSsid = ""
            if (token != null) lastToken = token
            pendingSsidAdopt = true
            recordSeen("", true)
        }
        return true
    }

    private fun reminderSsid(): String = prefs.getString(KEY_LAST_SSID, "").orEmpty()

    private fun lastRemindedAt(): Long = prefs.getLong(KEY_LAST_REMINDED_AT, 0L)

    private fun rememberReminderSsid(ssid: String) {
        prefs.edit()
            .putString(KEY_LAST_SSID, ssid)
            .putLong(KEY_LAST_REMINDED_AT, System.currentTimeMillis())
            .apply()
    }

    private fun forgetReminderSsid() {
        prefs.edit().remove(KEY_LAST_SSID).remove(KEY_LAST_REMINDED_AT).apply()
    }

    /**
     * 记录「最后一次看到的 WiFi 状态」。
     *
     * 进程随时可能被回收，只在内存里记基线的话，下次启动会把「进程不在时换过的
     * 网络」当成启动时就连着的网络而不再提醒。跨进程落盘后，启动时才能判断
     * 这中间到底有没有换过连接。
     */
    private fun recordSeen(ssid: String, connected: Boolean) {
        prefs.edit()
            .putString(KEY_SEEN_SSID, ssid)
            .putBoolean(KEY_SEEN_CONNECTED, connected)
            .apply()
    }

    /**
     * 连接事件到达时 SSID 不一定已经写进 WifiInfo（部分 ROM 会晚几百毫秒到几秒）。
     * 先补读几次，读到了按真实名称发送；一直读不到才退化成不带名称的连接信号，
     * 否则「连接指定 WiFi」的规则会被误判成条件不匹配。
     */
    private fun offerWithRetry(ssidRaw: String?, token: Any?) {
        val given = WifiSsidReader.sanitize(ssidRaw)
        if (given.isNotEmpty()) {
            ssidRetryJob?.cancel()
            readToken = token
            readCount = 0
            offer(given, token)
            return
        }
        // 没连 WiFi 就不读、也不发：读一次算一次定位访问。
        val key = token ?: connectedWifiNetwork() ?: return
        if (key != readToken) {
            readToken = key
            readCount = 0
        }
        if (readCount >= MAX_SSID_READS_PER_CONNECTION) return
        // 同一个连接会连续触发多条回调，已有补读任务时不要重复起任务。
        if (ssidRetryJob?.isActive == true) return
        ssidRetryJob = scope.launch {
            for (delayMillis in SSID_RETRY_DELAYS) {
                if (delayMillis > 0) delay(delayMillis)
                if (readCount >= MAX_SSID_READS_PER_CONNECTION) break
                readCount++
                val retried = currentSsid()
                if (retried.isNotEmpty()) {
                    offer(retried, token)
                    return@launch
                }
            }
            // 名称确实读不到时仍发一次连接信号，保证「任意 WiFi」规则还能触发。
            offer("", token)
        }
    }

    /** 回到前台时核对一次当前 WiFi（应用刚打开，属于用户主动动作，不算额外轮询）。 */
    fun checkNow() {
        if (!receiverRegistered) return
        scope.launch { pollOnce() }
    }

    private suspend fun pollOnce() {
        val network = connectedWifiNetwork() ?: return
        // 稳态（网络没变、名字已知）直接返回。读 SSID 会被系统记成一次定位访问，
        // 之前每 15 秒无条件读一次，隐私面板里就变成了几千次。
        if (network == lastToken && lastSsid.isNotEmpty()) return
        if (network != readToken) {
            readToken = network
            readCount = 0
        }
        if (readCount >= MAX_SSID_READS_PER_CONNECTION) return
        readCount++
        val ssid = currentSsid()
        if (ssid.isNotEmpty()) {
            offer(ssid, network)
        } else if (network != lastToken) {
            // 换了网络但名称读不到：只有「任意 WiFi」规则能吃这条信号，
            // 不能每轮都发，所以只在网络标识变化时补一次。
            offer("", network)
        }
    }

    private fun offerNetwork(network: Network) {
        offerWithRetry(null, network)
    }

    @Synchronized
    private fun handleDisconnect() {
        if (wifiNetworks.isNotEmpty()) return
        ssidRetryJob?.cancel()
        ssidRetryJob = null
        readToken = null
        readCount = 0
        baselineToken = null
        pendingSsidAdopt = false
        forgetReminderSsid()
        recordSeen("", false)
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
                    val ssid = WifiSsidReader.sanitize(info?.ssid)
                    val network = connectedWifiNetwork()
                    val id = info?.networkId ?: -1
                    val token = network ?: if (id >= 0) "id:$id" else null
                    if (ssid.isNotEmpty()) {
                        offer(ssid, token)
                    } else if (network != null) {
                        // 已确认连着 WiFi，只是名称还没可读：补读几次再决定发什么。
                        offerWithRetry("", token)
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
        // 先建立基线，再注册监听：注册瞬间可能立刻回调当前已连接的网络，
        // 基线还是空的话会把「本来就连着」误判成刚连接并弹提醒。
        lastSsid = currentSsid()
        val connected = connectedWifiNetwork()
        lastToken = connected
        baselineToken = connected.takeIf { lastSsid.isEmpty() }
        pendingSsidAdopt = false

        // 进程不在的这段时间里换过 WiFi：不能把现在的连接当基线，
        // 否则这次连接永远不会提醒，只能等用户自己打开提醒页才「补」出来。
        val observed = lastSsid
        val seenSsid = prefs.getString(KEY_SEEN_SSID, "").orEmpty()
        val seenConnected = prefs.getBoolean(KEY_SEEN_CONNECTED, false)
        val changedWhileAway = connected != null && observed.isNotEmpty() &&
            seenConnected && observed != seenSsid
        // 先把这次观察落盘，避免刚记录就再次崩溃时反复补发同一条提醒。
        recordSeen(observed, connected != null)
        if (changedWhileAway) {
            ReminderTrace.record(
                "WiFi",
                "离开期间由 ${seenSsid.ifBlank { "其它网络" }} 切到 $observed，补发连接信号",
                true
            )
            lastSsid = ""
            lastToken = null
            baselineToken = null
        }

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
        wifiNetworks.clear()
        runCatching {
            connectivityManager?.allNetworks?.forEach { network ->
                val capabilities = connectivityManager.getNetworkCapabilities(network)
                if (capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true) {
                    wifiNetworks.add(network)
                }
            }
        }
        // 不做定时轮询：只在网络回调、广播、唤醒和回到前台时读 SSID。
        // 每次读 SSID 都会被系统记成一次定位访问，轮询是之前几千次的来源。
    }

    override fun stop() {
        if (!receiverRegistered) return
        runCatching { connectivityManager?.unregisterNetworkCallback(callback) }
        runCatching { context.unregisterReceiver(wifiReceiver) }
        ssidRetryJob?.cancel()
        ssidRetryJob = null
        readToken = null
        readCount = 0
        receiverRegistered = false
        wifiNetworks.clear()
        lastSsid = ""
        lastToken = null
        baselineToken = null
        pendingSsidAdopt = false
    }

    private companion object {
        /** 连接后补读 SSID 的时间点：立即读 1 次，1.5 秒、4 秒各补读 1 次，之后彻底停止。 */
        val SSID_RETRY_DELAYS = longArrayOf(0L, 1_500L, 4_000L)

        /** 每条新连接最多读几次 SSID（读一次算一次定位访问）。 */
        const val MAX_SSID_READS_PER_CONNECTION = 3

        /** 同一个 WiFi 在这段时间内只提醒一次；超过它重新连回要再提醒。 */
        const val SAME_CONNECTION_DEDUPE_MILLIS = 120_000L

        /** 同一 SSID 短时间内的网络标识变化视为双通道重复，不重复提醒。 */
        const val SAME_SSID_REEMIT_GAP_MILLIS = 3_000L

        /** 系统级网络唤醒的 PendingIntent 请求码。 */
        const val WAKE_REQUEST_CODE = 1009

        /** 进程内回调与后台唤醒几乎同时到达时，只保留一次提醒。 */
        const val WAKE_DUPLICATE_WINDOW_MILLIS = 3_000L

        const val PREFS_NAME = "wifi_signal"
        const val KEY_LAST_SSID = "last_ssid"

        /** 上次提醒的时间（墙钟），用于把「同一次连接」的去重限制在短时间窗口内。 */
        const val KEY_LAST_REMINDED_AT = "last_reminded_at"

        /** 跨进程记录的「最后一次看到的 WiFi 状态」。 */
        const val KEY_SEEN_SSID = "seen_ssid"
        const val KEY_SEEN_CONNECTED = "seen_connected"
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
                // 进程被冻结或系统休眠后，lastPoll 会停在很久以前；
                // 直接回放整段旧事件会在用户回到 App 时补出过时提醒。
                val from = if (now - lastPoll > STALE_GAP_MILLIS) {
                    now - RECENT_WINDOW_MILLIS
                } else {
                    lastPoll
                }
                val usageEvents = runCatching {
                    usageStats?.queryEvents(from, now)
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

        /** 轮询间隔超过这个值，说明进程中途被冻结/系统休眠过，不能回放整段事件。 */
        const val STALE_GAP_MILLIS = 30_000L

        /** 暂停恢复后只回看最近这段事件，避免补出已经过时的应用启动提醒。 */
        const val RECENT_WINDOW_MILLIS = 10_000L
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
