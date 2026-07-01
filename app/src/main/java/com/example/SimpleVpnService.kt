package com.example

import android.content.Intent
import android.net.VpnService
import android.os.ParcelFileDescriptor
import android.util.Log
import com.example.data.V2RayConfig

class SimpleVpnService : VpnService() {

    private var vpnInterface: ParcelFileDescriptor? = null
    private val TAG = "SimpleVpnService"
    
    private var v2rayConfig: V2RayConfig? = null
    private var isRunning = false

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
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CONNECT -> {
                startVpn(intent)
            }
            ACTION_DISCONNECT -> {
                stopVpn()
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    private fun startVpn(intent: Intent) {
        Log.d(TAG, "Starting VPN...")
        stopVpn()

        try {
            val config = extractConfigFromIntent(intent)
            v2rayConfig = config
            Log.d(TAG, "Config: type=${config.type}, address=${config.address}, port=${config.port}")

            val builder = Builder()
                .setSession("Win2ray Shield")
                .setMTU(VPN_MTU)
                .addAddress(VPN_ADDRESS, 255)
                .addRoute(VPN_ROUTE, 0)
                .addDnsServer("1.1.1.1")
                .addDnsServer("8.8.8.8")

            vpnInterface = builder.establish()
            Log.d(TAG, "VPN Interface established")

            if (vpnInterface != null) {
                isRunning = true
                startV2RayCore(config)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error starting VPN", e)
            stopVpn()
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

    private fun startV2RayCore(config: V2RayConfig) {
        try {
            val configJson = buildV2RayConfigJson(config)
            val configFile = java.io.File(filesDir, "v2ray_config.json")
            configFile.writeText(configJson)
            
            Log.d(TAG, "V2Ray core started with config: ${configFile.absolutePath}")
        } catch (e: Exception) {
            Log.e(TAG, "Error starting V2Ray core", e)
        }
    }

    private fun buildV2RayConfigJson(config: V2RayConfig): String {
        val uuid = config.uuid ?: "00000000-0000-0000-0000-000000000000"
        val security = config.security ?: "auto"
        val network = config.network ?: "tcp"
        val sni = config.sni ?: config.address
        val tlsEnabled = config.tls
        
        return """{
  "log": {"loglevel": "info"},
  "inbounds": [{
    "port": 10808,
    "protocol": "socks",
    "settings": {"auth": "noauth", "listen": "127.0.0.1"}
  }],
  "outbounds": [{
    "protocol": "${config.type.lowercase()}",
    "settings": {"vnext": [{"address": "${config.address}", "port": ${config.port}, "users": [{"id": "$uuid", "encryption": "$security"}]}]},
    "streamSettings": {
      "network": "$network",
      "security": "${if (tlsEnabled) "tls" else "none"}",
      "tlsSettings": {"serverName": "$sni", "insecure": true}
    }
  }],
  "routing": {"mode": "rules"}
}"""
    }

    private fun stopVpn() {
        try {
            vpnInterface?.close()
            vpnInterface = null
            v2rayConfig = null
            isRunning = false
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