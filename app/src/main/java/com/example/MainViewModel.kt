package com.example

import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.VpnService
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.AppDatabase
import com.example.data.AppSetting
import com.example.data.SelectedApp
import com.example.data.SecureConfig
import com.example.data.V2RayConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.TimeUnit

enum class LoginMode {
    NONE, FREE, SUBSCRIPTION
}

sealed interface UiState {
    object Idle : UiState
    object Loading : UiState
    data class Success(val configs: List<V2RayConfig>) : UiState
    data class Error(val message: String) : UiState
}

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val db = AppDatabase.getDatabase(application)
    private val dao = db.vpnDao()
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    // UI States
    private val _uiState = MutableStateFlow<UiState>(UiState.Idle)
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    private val _loginMode = MutableStateFlow(LoginMode.NONE)
    val loginMode: StateFlow<LoginMode> = _loginMode.asStateFlow()

    private val _username = MutableStateFlow("")
    val username: StateFlow<String> = _username.asStateFlow()

    private val _configs = MutableStateFlow<List<V2RayConfig>>(emptyList())
    val configs: StateFlow<List<V2RayConfig>> = _configs.asStateFlow()

    private val _selectedConfig = MutableStateFlow<V2RayConfig?>(null)
    val selectedConfig: StateFlow<V2RayConfig?> = _selectedConfig.asStateFlow()

    private val _isAutoSelect = MutableStateFlow(true)
    val isAutoSelect: StateFlow<Boolean> = _isAutoSelect.asStateFlow()

    private val _vpnConnected = MutableStateFlow(false)
    val vpnConnected: StateFlow<Boolean> = _vpnConnected.asStateFlow()

    private val _darkTheme = MutableStateFlow(true)
    val darkTheme: StateFlow<Boolean> = _darkTheme.asStateFlow()

    // App list for Split Tunneling
    private val _installedApps = MutableStateFlow<List<AppInfo>>(emptyList())
    val installedApps: StateFlow<List<AppInfo>> = _installedApps.asStateFlow()

    val selectedApps: StateFlow<List<SelectedApp>> = dao.getSelectedApps()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    data class AppInfo(
        val packageName: String,
        val appName: String,
        val isSystem: Boolean,
        val isSelected: Boolean
    )

    init {
        loadSettings()
        loadInstalledApps()
    }

    private fun loadSettings() {
        viewModelScope.launch {
            val savedMode = dao.getSettingValue("login_mode") ?: LoginMode.NONE.name
            val mode = try { LoginMode.valueOf(savedMode) } catch (e: Exception) { LoginMode.NONE }
            _loginMode.value = mode

            val savedUser = dao.getSettingValue("username") ?: ""
            _username.value = savedUser

            val themeVal = dao.getSettingValue("dark_theme") ?: "true"
            _darkTheme.value = themeVal.toBoolean()

            val autoSelectVal = dao.getSettingValue("auto_select") ?: "true"
            _isAutoSelect.value = autoSelectVal.toBoolean()

            // Auto-load configs if a mode is saved
            if (mode == LoginMode.FREE) {
                loadFreeConfigs()
            } else if (mode == LoginMode.SUBSCRIPTION && savedUser.isNotEmpty()) {
                loadSubscriptionConfigs(savedUser)
            }
        }
    }

    fun toggleTheme() {
        viewModelScope.launch {
            val newVal = !_darkTheme.value
            _darkTheme.value = newVal
            dao.saveSetting(AppSetting("dark_theme", newVal.toString()))
        }
    }

    fun setAutoSelect(auto: Boolean) {
        _isAutoSelect.value = auto
        viewModelScope.launch {
            dao.saveSetting(AppSetting("auto_select", auto.toString()))
        }
    }

    fun selectConfig(config: V2RayConfig?) {
        _selectedConfig.value = config
    }

    // Free Login Action
    fun loginFree() {
        viewModelScope.launch {
            _loginMode.value = LoginMode.FREE
            _username.value = ""
            _selectedConfig.value = null
            _configs.value = emptyList() // Clear configs first!
            dao.saveSetting(AppSetting("login_mode", LoginMode.FREE.name))
            dao.saveSetting(AppSetting("username", ""))
            loadFreeConfigs()
        }
    }

    // Subscription Login Action
    fun loginSubscription(user: String) {
        viewModelScope.launch {
            _loginMode.value = LoginMode.SUBSCRIPTION
            _username.value = user
            _selectedConfig.value = null
            _configs.value = emptyList() // Clear configs first!
            dao.saveSetting(AppSetting("login_mode", LoginMode.SUBSCRIPTION.name))
            dao.saveSetting(AppSetting("username", user))
            loadSubscriptionConfigs(user)
        }
    }

    fun logout() {
        viewModelScope.launch {
            _loginMode.value = LoginMode.NONE
            _username.value = ""
            _selectedConfig.value = null
            _configs.value = emptyList()
            _uiState.value = UiState.Idle
            dao.saveSetting(AppSetting("login_mode", LoginMode.NONE.name))
            dao.saveSetting(AppSetting("username", ""))
        }
    }

    private fun loadFreeConfigs() {
        _uiState.value = UiState.Loading
        viewModelScope.launch {
            try {
                val configsList = fetchAndParseConfigs(SecureConfig.FREE_URL, false)
                if (configsList.isEmpty()) {
                    _uiState.value = UiState.Error("هیچ کانفیگی یافت نشد.")
                } else {
                    _configs.value = configsList
                    _uiState.value = UiState.Success(configsList)
                    // Ping in background
                    measureAndSortPings()
                }
            } catch (e: Exception) {
                _uiState.value = UiState.Error("خطا در بارگذاری کانفیگ‌های رایگان: ${e.localizedMessage}")
            }
        }
    }

    private fun loadSubscriptionConfigs(user: String) {
        _uiState.value = UiState.Loading
        viewModelScope.launch {
            try {
                // Step 1: Fetch Full.txt subscription URLs
                val fullTxt = fetchString(SecureConfig.FULL_URL)
                val lines = fullTxt.split("\n", "\r").map { it.trim() }.filter { it.isNotEmpty() }

                // Check if any url contains sub=user
                val userSubUrl = lines.find { it.contains("sub=$user", ignoreCase = true) }

                if (userSubUrl == null) {
                    _uiState.value = UiState.Error("نام کاربری یافت نشد یا اشتراک شما منقضی شده است.")
                } else {
                    // Step 2: Fetch configs from the subscription link
                    val subConfigs = fetchAndParseConfigs(userSubUrl, true)
                    if (subConfigs.isEmpty()) {
                        _uiState.value = UiState.Error("هیچ کانفیگی در اشتراک شما وجود ندارد.")
                    } else {
                        _configs.value = subConfigs
                        _uiState.value = UiState.Success(subConfigs)
                        measureAndSortPings()
                    }
                }
            } catch (e: Exception) {
                _uiState.value = UiState.Error("خطا در بارگذاری اشتراک: ${e.localizedMessage}")
            }
        }
    }

    private suspend fun fetchString(url: String): String = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(url).build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw Exception("HTTP ${response.code}")
            response.body?.string() ?: ""
        }
    }

    private suspend fun fetchAndParseConfigs(url: String, isPremium: Boolean): List<V2RayConfig> {
        val rawContent = fetchString(url)
        return parseConfigs(rawContent, isPremium)
    }

    private fun parseConfigs(rawContent: String, isPremium: Boolean): List<V2RayConfig> {
        var content = rawContent.trim()

        // Check if it's base64 encoded
        if (!content.contains("vmess://") && !content.contains("vless://") && !content.contains("ss://") && !content.contains("trojan://")) {
            try {
                val decoded = String(android.util.Base64.decode(content, android.util.Base64.DEFAULT), Charsets.UTF_8)
                if (decoded.contains("://")) {
                    content = decoded
                }
            } catch (e: Exception) {
                // Not base64, continue
            }
        }

        val lines = content.split("\n", "\r").map { it.trim() }.filter { it.isNotEmpty() }
        val list = mutableListOf<V2RayConfig>()

        for ((index, line) in lines.withIndex()) {
            try {
                val type = when {
                    line.startsWith("vmess://") -> "VMess"
                    line.startsWith("vless://") -> "VLess"
                    line.startsWith("ss://") -> "Shadowsocks"
                    line.startsWith("trojan://") -> "Trojan"
                    else -> "Unknown"
                }
                if (type == "Unknown") continue

                var name = "سرور #${index + 1} ($type)"
                var address = ""
                var port = 443
                var uuid: String? = null
                var security: String? = null
                var network: String? = null
                var sni: String? = null
                var alpn: String? = null
                var tls = false

                if (type == "VMess") {
                    try {
                        val b64 = line.substringAfter("vmess://")
                        val json = String(android.util.Base64.decode(b64, android.util.Base64.DEFAULT), Charsets.UTF_8)
                        address = regexSearch(json, "\"add\"\\s*:\\s*\"([^\"]+)\"") ?: ""
                        val portStr = regexSearch(json, "\"port\"\\s*:\\s*(\\d+)") ?: "443"
                        port = portStr.toIntOrNull() ?: 443
                        name = regexSearch(json, "\"ps\"\\s*:\\s*\"([^\"]+)\"") ?: name
                        uuid = regexSearch(json, "\"id\"\\s*:\\s*\"([^\"]+)\"") ?: ""
                        security = regexSearch(json, "\"sc\"\\s*:\\s*\"([^\"]+)\"")
                        network = regexSearch(json, "\"net\"\\s*:\\s*\"([^\"]+)\"")
                        sni = regexSearch(json, "\"sni\"\\s*:\\s*\"([^\"]+)\"")
                        alpn = regexSearch(json, "\"alpn\"\\s*:\\s*\"([^\"]+)\"")
                        tls = regexSearch(json, "\"tls\"\\s*:\\s*\"([^\"]+)\"")?.toBoolean() ?: false
                    } catch (e: Exception) {
                        Log.e("MainViewModel", "VMess parsing error", e)
                    }
                } else {
                    val hashPart = if (line.contains("#")) line.substringAfter("#") else ""
                    if (hashPart.isNotEmpty()) {
                        name = try {
                            java.net.URLDecoder.decode(hashPart, "UTF-8")
                        } catch (e: Exception) {
                            hashPart
                        }
                    }
                    val cleanLine = line.substringBefore("#")
                    val hostPort = if (cleanLine.contains("@")) {
                        cleanLine.substringAfter("@").substringBefore("?")
                    } else {
                        cleanLine.substringAfter("://").substringBefore("?")
                    }
                    address = hostPort.substringBefore(":")
                    port = hostPort.substringAfter(":", "443").toIntOrNull() ?: 443
                    
                    // Parse VLESS and Trojan for additional fields
                    if (type == "VLess" || type == "Trojan") {
                        val spdxMatch = regexSearch(cleanLine, "spdx=([^&]*)")
                        uuid = if (spdxMatch != null && spdxMatch.contains("&")) {
                            spdxMatch.substringAfter("spdx=").substringBefore("&")
                        } else {
                            spdxMatch?.substringAfter("spdx=") ?: null
                        }
                        if (uuid == null) {
                            uuid = regexSearch(cleanLine.substringAfter("://").substringBefore("@"), "([^@]+)")
                        }
                        tls = regexSearch(cleanLine, "tls=(true|false)")?.let { it.toBoolean() } ?: false
                        sni = regexSearch(cleanLine, "sni=([^&]+)")
                        network = regexSearch(cleanLine, "network=([^&]+)")
                    }
                }

                if (address.isNotEmpty()) {
                    list.add(
                        V2RayConfig(
                            id = line,
                            name = name,
                            type = type,
                            rawConfig = line,
                            address = address,
                            port = port,
                            isPremium = isPremium,
                            uuid = uuid,
                            security = security,
                            network = network,
                            sni = sni,
                            alpn = alpn,
                            tls = tls
                        )
                    )
                }
            } catch (e: Exception) {
                Log.e("MainViewModel", "Failed to parse line", e)
            }
        }
        return list
    }

    private fun regexSearch(json: String, pattern: String): String? {
        val matcher = java.util.regex.Pattern.compile(pattern).matcher(json)
        return if (matcher.find()) matcher.group(1) else null
    }

    // Measures pings using custom thread execution and sorts configs
    private fun measureAndSortPings() {
        viewModelScope.launch {
            val currentList = _configs.value.toMutableList()
            if (currentList.isEmpty()) return@launch

            // Perform concurrent ping checks
            val pings = currentList.map { config ->
                withContext(Dispatchers.IO) {
                    val pingTime = performSocketPing(config.address, config.port)
                    config.copy(ping = pingTime)
                }
            }

            // Sort by ping ascending (lowest ping first)
            val sortedList = pings.sortedBy { if (it.ping < 0) 9999L else it.ping }
            _configs.value = sortedList

            // Update UI success state with sorted list
            _uiState.value = UiState.Success(sortedList)

            // Auto-select the first (best) config if selectedConfig is null or isAutoSelect is active
            if (_isAutoSelect.value && sortedList.isNotEmpty()) {
                _selectedConfig.value = sortedList.first()
            }
        }
    }

    private fun performSocketPing(address: String, port: Int): Long {
        val startTime = System.currentTimeMillis()
        var socket: Socket? = null
        return try {
            socket = Socket()
            socket.connect(InetSocketAddress(address, port), 2500) // 2.5s timeout
            System.currentTimeMillis() - startTime
        } catch (e: Exception) {
            -1L // Offline / Timeout
        } finally {
            try {
                socket?.close()
            } catch (e: Exception) {
                // ignore
            }
        }
    }

    fun toggleVpn(context: Context) {
        val isConnected = _vpnConnected.value
        if (isConnected) {
            disconnectVpn(context)
            _vpnConnected.value = false
        } else {
            val config = _selectedConfig.value
            if (config != null) {
                connectVpn(context, config)
            } else {
                _uiState.value = UiState.Error("لطفاً یک سرور انتخاب کنید.")
            }
        }
    }

    fun connectVpn(context: Context, config: V2RayConfig) {
        val intent = Intent(context, SimpleVpnService::class.java).apply {
            action = SimpleVpnService.ACTION_CONNECT
            putExtra(SimpleVpnService.EXTRA_CONFIG, config.rawConfig)
            putExtra(SimpleVpnService.EXTRA_CONFIG_TYPE, config.type)
            putExtra(SimpleVpnService.EXTRA_CONFIG_ADDRESS, config.address)
            putExtra(SimpleVpnService.EXTRA_CONFIG_PORT, config.port)
            putExtra(SimpleVpnService.EXTRA_CONFIG_UUID, config.uuid)
            putExtra(SimpleVpnService.EXTRA_CONFIG_SECURITY, config.security)
            putExtra(SimpleVpnService.EXTRA_CONFIG_NETWORK, config.network)
            putExtra(SimpleVpnService.EXTRA_CONFIG_SNI, config.sni)
            putExtra(SimpleVpnService.EXTRA_CONFIG_ALPN, config.alpn)
            putExtra(SimpleVpnService.EXTRA_CONFIG_TLS, config.tls)
        }
        context.startForegroundService(intent)
    }

    fun disconnectVpn(context: Context) {
        val intent = Intent(context, SimpleVpnService::class.java).apply {
            action = SimpleVpnService.ACTION_DISCONNECT
        }
        context.startForegroundService(intent)
    }

    // App Tunneling / Split Tunneling logic
    private fun loadInstalledApps() {
        viewModelScope.launch {
            val pm = getApplication<Application>().packageManager
            val rawApps = withContext(Dispatchers.IO) {
                pm.getInstalledApplications(PackageManager.GET_META_DATA)
            }

            // Combine with database selection
            selectedApps.map { dbApps ->
                val selectedPkgNames = dbApps.map { it.packageName }.toSet()
                rawApps.map { app ->
                    val appName = app.loadLabel(pm).toString()
                    val isSystem = (app.flags and ApplicationInfo.FLAG_SYSTEM) != 0
                    AppInfo(
                        packageName = app.packageName,
                        appName = appName,
                        isSystem = isSystem,
                        isSelected = selectedPkgNames.contains(app.packageName)
                    )
                }.sortedBy { it.appName }
            }.collect { combined ->
                _installedApps.value = combined
            }
        }
    }

    fun setError(message: String) {
        _uiState.value = UiState.Error(message)
    }

    fun toggleAppSelection(appInfo: AppInfo) {
        viewModelScope.launch {
            if (appInfo.isSelected) {
                dao.deleteSelectedApp(SelectedApp(appInfo.packageName, appInfo.appName))
            } else {
                dao.insertSelectedApp(SelectedApp(appInfo.packageName, appInfo.appName))
            }
        }
    }

    fun clearAllAppSelection() {
        viewModelScope.launch {
            dao.clearSelectedApps()
        }
    }

    // Telegram Proxy Support
    private val _telegramProxies = MutableStateFlow<List<TelegramProxy>>(emptyList())
    val telegramProxies: StateFlow<List<TelegramProxy>> = _telegramProxies.asStateFlow()

    private val _proxyLoading = MutableStateFlow(false)
    val proxyLoading: StateFlow<Boolean> = _proxyLoading.asStateFlow()

    private val _proxyError = MutableStateFlow<String?>(null)
    val proxyError: StateFlow<String?> = _proxyError.asStateFlow()

    fun loadTelegramProxies() {
        _proxyLoading.value = true
        _proxyError.value = null
        viewModelScope.launch {
            try {
                val rawContent = fetchString("https://alirez.n-cpanel.xyz/Sub/Plans/Proxy.txt")
                val parsed = rawContent.split("\n", "\r")
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }
                    .mapNotNull { parseTelegramProxy(it) }
                
                if (parsed.isEmpty()) {
                    _proxyError.value = "هیچ پروکسی یافت نشد."
                    _telegramProxies.value = emptyList()
                } else {
                    _telegramProxies.value = parsed
                    measureProxyPings(parsed)
                }
            } catch (e: Exception) {
                Log.e("MainViewModel", "Error loading proxies", e)
                _proxyError.value = "خطا در بارگذاری پروکسی‌ها: ${e.localizedMessage}"
            } finally {
                _proxyLoading.value = false
            }
        }
    }

    private fun parseTelegramProxy(line: String): TelegramProxy? {
        val trimmed = line.trim()
        if (trimmed.isEmpty()) return null
        
        val isMtProto = trimmed.contains("proxy?") || trimmed.startsWith("tg://proxy") || trimmed.contains("t.me/proxy")
        val isSocks = trimmed.contains("socks?") || trimmed.startsWith("tg://socks") || trimmed.contains("t.me/socks")
        if (!isMtProto && !isSocks) return null
        
        val type = if (isMtProto) "MTProto" else "Socks5"
        var address = ""
        var port = 1080
        var secret: String? = null
        
        try {
            val queryPart = trimmed.substringAfter("?")
            val params = queryPart.split("&")
            for (param in params) {
                val keyValue = param.split("=")
                if (keyValue.size == 2) {
                    val key = keyValue[0]
                    val value = java.net.URLDecoder.decode(keyValue[1], "UTF-8")
                    when (key.lowercase()) {
                        "server" -> address = value
                        "port" -> port = value.toIntOrNull() ?: port
                        "secret" -> secret = value
                    }
                }
            }
        } catch (e: Exception) {
            // ignore
        }
        
        if (address.isEmpty()) {
            val serverRegex = Regex("server=([^&]+)")
            val portRegex = Regex("port=(\\d+)")
            val secretRegex = Regex("secret=([^&]+)")
            
            address = serverRegex.find(trimmed)?.groupValues?.get(1) ?: ""
            port = portRegex.find(trimmed)?.groupValues?.get(1)?.toIntOrNull() ?: port
            secret = secretRegex.find(trimmed)?.groupValues?.get(1)
        }
        
        if (address.isEmpty()) return null
        
        return TelegramProxy(
            rawUrl = trimmed,
            address = address,
            port = port,
            secret = secret,
            type = type
        )
    }

    private fun measureProxyPings(list: List<TelegramProxy>) {
        viewModelScope.launch {
            val currentList = list.toMutableList()
            val pings = currentList.map { proxy ->
                withContext(Dispatchers.IO) {
                    val pingTime = performSocketPing(proxy.address, proxy.port)
                    proxy.copy(ping = pingTime)
                }
            }
            _telegramProxies.value = pings
        }
    }

    private var lastRefreshTime = 0L

    private val _connectionState = MutableStateFlow(VpnState.DISCONNECTED)
    val connectionState: StateFlow<VpnState> = _connectionState.asStateFlow()

    fun refreshOnAppOpen() {
        val currentTime = System.currentTimeMillis()
        if (currentTime - lastRefreshTime > 3000) {
            lastRefreshTime = currentTime
            val mode = _loginMode.value
            val user = _username.value
            if (mode == LoginMode.FREE) {
                loadFreeConfigs()
            } else if (mode == LoginMode.SUBSCRIPTION && user.isNotEmpty()) {
                loadSubscriptionConfigs(user)
            }
        }
    }

    companion object {
        const val ACTION_VPN_STATE_CHANGED = "com.example.vpn.STATE_CHANGED"
        const val EXTRA_STATE = "state"
    }

    fun updateConnectionState(state: VpnState) {
        _connectionState.value = state
        _vpnConnected.value = (state == VpnState.CONNECTED)
    }
}

enum class VpnState {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    DISCONNECTING
}

data class TelegramProxy(
    val rawUrl: String,
    val address: String,
    val port: Int,
    val secret: String? = null,
    val type: String,
    val ping: Long = -1L
)
