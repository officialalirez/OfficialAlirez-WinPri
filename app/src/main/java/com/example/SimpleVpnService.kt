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
        setupNotificationChannel()
    }

    private fun setupNotificationChannel() {
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
            }
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startVpn(config: V2RayConfig) {
        Log.d(TAG, "Starting VPN with config: ${config.address}:${config.port}")
        stopVpn()

        try {
            setupNotificationChannel()
            
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
                stopSelf()
                return
            }

            startForeground(NOTIFICATION_ID, createNotification())
            
            configAddress = config.address
            configPort = config.port
            configUuid = config.uuid ?: "00000000-0000-0000-0000-000000000000"
            
            startV2RayCore()
        } catch (e: Exception) {
            Log.e(TAG, "Error starting VPN", e)
            stopVpn()
            stopSelf()
        }
    }

    private fun extractConfigFromIntent(intent: Intent): V2RayConfig {
        return V2RayConfig(
            id = intent.getStringExtra(EXTRA_CONFIG) ?: "",
            name = intent.getStringExtra(EXTRA_CONFIG_TYPE) ?: "VMess",
            type = intent.getStringExtra(EXTRA_CONFIG_TYPE) ?: "VMess",
            rawConfig = intent.getStringExtra(EXTRA_CONFIG) ?: "",
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
            val fd = vpnInterface?.fileDescriptor ?: throw IllegalStateException("VPN interface is null")
            Log.d(TAG, "Starting V2Ray core with FD: $fd")
            coreController?.startLoop(configFile.absolutePath, fd.int)
            Log.d(TAG, "V2Ray core started successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Error starting V2Ray core", e)
        }
    }

    private fun buildV2RayConfigJson(): String {
        return """{
  "log": {"loglevel": "info"},
  "inbounds": [{
    "port": 0,
    "protocol": "socks",
    "settings": {"auth": "noauth", "listen": "127.0.0.1"}
  }],
  "outbounds": [{
    "protocol": "vmess",
    "settings": {"vnext": [{"address": "$configAddress", "port": $configPort, "users": [{"id": "$configUuid", "encryption": "auto"}]}]},
    "streamSettings": {
      "network": "tcp",
      "security": "none"
    }
  }],
  "routing": {"mode": "rules"}
}"""
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
            stopSelf()
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