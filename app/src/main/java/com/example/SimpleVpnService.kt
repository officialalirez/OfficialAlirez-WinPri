package com.example

import android.app.*
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.core.app.NotificationCompat
import libv2ray.Libv2ray
import libv2ray.CoreController
import libv2ray.CoreCallbackHandler
import com.example.data.V2RayConfig

class SimpleVpnService : VpnService() {

    private var vpnInterface: ParcelFileDescriptor? = null
    private val TAG = "SimpleVpnService"
    
    private var coreController: CoreController? = null
    private var configAddress: String = ""
    private var configPort: Int = 0
    private var configUuid: String = ""
    private var configType: String = "VMess"
    private var configTls: Boolean = false
    private var configSni: String? = null
    private var configAlpn: String? = null
    private var configNetwork: String? = null


    companion object {
        const val ACTION_CONNECT = "com.example.vpn.CONNECT"
        const val ACTION_DISCONNECT = "com.example.vpn.DISCONNECT"
        const val EXTRA_CONFIG = "config"
        const val EXTRA_CONFIG_TYPE = "config_type"
        const val EXTRA_CONFIG_ADDRESS = "config_address"
        const val EXTRA_CONFIG_PORT = "config_port"
        const val EXTRA_CONFIG_UUID = "config_uuid"
        const val EXTRA_CONFIG_SECURITY = "config_security"
        const val EXTRA_CONFIG_NETWORK = "config_network"
        const val EXTRA_CONFIG_SNI = "config_sni"
        const val EXTRA_CONFIG_ALPN = "config_alpn"
        const val EXTRA_CONFIG_TLS = "config_tls"
        
        private const val VPN_MTU = 1500
        private const val VPN_ADDRESS = "172.19.0.1"
        private const val VPN_ROUTE = "0.0.0.0"
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "vpn_channel"
    }

    override fun onCreate() {
        super.onCreate()
        Libv2ray.touch()
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "VPN Service",
                NotificationManager.IMPORTANCE_LOW
            )
            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "onStartCommand: ${intent?.action}")
        when (intent?.action) {
            ACTION_CONNECT -> {
                val config = extractConfigFromIntent(intent)
                startVpn(config)
            }
            ACTION_DISCONNECT -> {
                stopVpn()
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startVpn(config: V2RayConfig) {
        Log.d(TAG, "Starting VPN with config: ${config.address}:${config.port}")

        try {
            vpnInterface = Builder()
                .setSession("Win2ray Shield")
                .setMtu(VPN_MTU)
                .addAddress(VPN_ADDRESS, 255)
                .addRoute(VPN_ROUTE, 0)
                .addRoute("::", 0)
                .addDnsServer("1.1.1.1")
                .addDnsServer("8.8.8.8")
                .establish()

            Log.d(TAG, "VPN Interface established: $vpnInterface")

            if (vpnInterface == null) {
                Log.e(TAG, "Failed to establish VPN interface")
                return
            }

            startForeground(NOTIFICATION_ID, createNotification())
            
            configAddress = config.address
            configPort = config.port
            configUuid = config.uuid ?: "00000000-0000-0000-0000-000000000000"
            configType = config.type
            configTls = config.tls
            configSni = config.sni
            configAlpn = config.alpn
            configNetwork = config.network

            startV2RayCore()

        } catch (e: Exception) {
            Log.e(TAG, "Error starting VPN", e)
            stopVpn()
        }
    }

    private fun extractConfigFromIntent(intent: Intent): V2RayConfig {
        val raw = intent.getStringExtra(EXTRA_CONFIG) ?: ""
        val type = intent.getStringExtra(EXTRA_CONFIG_TYPE) ?: "VMess"

        return V2RayConfig(
            id = raw,
            name = type,
            type = type,
            rawConfig = raw,
            address = intent.getStringExtra(EXTRA_CONFIG_ADDRESS) ?: "",
            port = intent.getIntExtra(EXTRA_CONFIG_PORT, 443),
            uuid = intent.getStringExtra(EXTRA_CONFIG_UUID),
            security = intent.getStringExtra(EXTRA_CONFIG_SECURITY),
            network = intent.getStringExtra(EXTRA_CONFIG_NETWORK),
            sni = intent.getStringExtra(EXTRA_CONFIG_SNI),
            alpn = intent.getStringExtra(EXTRA_CONFIG_ALPN),
            tls = intent.getBooleanExtra(EXTRA_CONFIG_TLS, false),
            isPremium = true
        )
    }


    private fun startV2RayCore() {
        try {
            val configJson = buildV2RayConfigJson()
            val configFile = java.io.File(filesDir, "v2ray_config.json")
            configFile.writeText(configJson)

            Log.d(TAG, "Starting V2Ray core with config: ${configFile.absolutePath}")
            Log.d(TAG, "Config JSON: $configJson")

            val callbackHandler = object : CoreCallbackHandler {
                override fun onEmitStatus(code: Long, msg: String): Long {
                    Log.d(TAG, "V2Ray status: $msg (code: $code)")
                    return 0L
                }

                override fun shutdown(): Long {
                    Log.d(TAG, "V2Ray shutting down")
                    return 0L
                }

                override fun startup(): Long {
                    Log.d(TAG, "V2Ray starting up")
                    return 0L
                }
            }

            coreController = Libv2ray.newCoreController(callbackHandler)
            val pfd = vpnInterface ?: throw IllegalStateException("VPN interface is null")
            val fd = pfd.detachFd()
            Log.d(TAG, "Starting V2Ray core with FD: $fd")

            coreController?.startLoop(configFile.absolutePath, fd)

            Log.d(TAG, "V2Ray core started successfully")
        } catch (t: Throwable) {
            Log.e(TAG, "Fatal error starting V2Ray core. Will rollback VPN.", t)
            // rollback: close VPN interface + stop foreground to avoid crash loops
            try {
                coreController?.stopLoop()
            } catch (_: Throwable) {
            }
            try {
                vpnInterface?.close()
            } catch (_: Throwable) {
            }
            coreController = null
            vpnInterface = null
            try {
                stopForeground(true)
            } catch (_: Throwable) {
            }
        }
    }


    private fun buildV2RayConfigJson(): String {
        val protocol = when (configType) {
            "VLess" -> "vless"
            "Trojan" -> "trojan"
            else -> "vmess"
        }

        val tlsBlock = if (configTls) {
            val serverName = configSni?.takeIf { it.isNotBlank() } ?: configAddress
            // ALPN optional
            val alpnArr = configAlpn?.split(",")
                ?.map { it.trim() }
                ?.filter { it.isNotEmpty() }

            if (alpnArr.isNullOrEmpty()) {
                """"security": "tls",
      "tlsSettings": {"serverName": ${jsonString(serverName)}}"""
            } else {
                val alpnJson = alpnArr.joinToString(prefix = "[", postfix = "]") { jsonString(it) }
                """"security": "tls",
      "tlsSettings": {"serverName": ${jsonString(serverName)}, "alpn": $alpnJson}"""
            }
        } else {
            """"security": "none""""
        }


        // NOTE: libv2ray core controller API varies by builds; we keep config minimal but valid.
        val outboundUsersBlock = when (protocol) {
            "vmess" -> {
                // vmess typically uses uuid as id
                """"vnext": [{
      "address": ${jsonString(configAddress)},
      "port": $configPort,
      "users": [{
        "id": ${jsonString(configUuid)},
        "encryption": "auto"
      }]
    }]"""
            }
            "vless" -> {
                val uuid = configUuid
                // vless settings format is different; we keep common shape
                """"vnext": [{
      "address": ${jsonString(configAddress)},
      "port": $configPort,
      "users": [{
        "id": ${jsonString(uuid)},
        "flow": "" 
      }]
    }]"""
            }
            "trojan" -> {
                // trojan uses password/secret; we mapped uuid as uuid variable (may actually be pass)
                val pwd = configUuid
                """"servers": [{
      "address": ${jsonString(configAddress)},
      "port": $configPort,
      "password": ${jsonString(pwd)},
      "level": 0
    }]"""
            }
            else -> {
                """"vnext": []"""
            }
        }

        val streamNetwork = (configNetwork ?: "tcp")
        val streamSettingsBlock = when (protocol) {
            "vmess", "vless", "trojan" -> {
                """"network": ${jsonString(streamNetwork)},
    $tlsBlock"""
            }
            else -> """"network": "tcp", "security": "none""""
        }

        return """{
  "log": {"loglevel": "info"},
  "inbounds": [{
    "port": 0,
    "protocol": "socks",
    "settings": {"auth": "noauth", "listen": "127.0.0.1"}
  }],
  "outbounds": [{
    "protocol": ${jsonString(protocol)},
    "settings": { $outboundUsersBlock },
    "streamSettings": {
      $streamSettingsBlock
    }
  }],
  "routing": {"mode": "field"}
}"""
    }

    private fun jsonString(v: String): String {
        val escaped = v
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
        return "\"$escaped\""
    }


    private fun createNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Win2ray Shield")
            .setContentText("VPN is running...")
            .setSmallIcon(R.drawable.ic_vpn_notification)
            .setOngoing(true)
            .build()
    }

    private fun stopVpn() {
        try {
            Log.d(TAG, "Stopping VPN...")
            coreController?.stopLoop()
            vpnInterface?.close()
            vpnInterface = null
            coreController = null
            stopForeground(true)
            Log.d(TAG, "VPN stopped")
        } catch (e: Exception) {
            Log.e(TAG, "Error closing VPN", e)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        stopVpn()
    }
}