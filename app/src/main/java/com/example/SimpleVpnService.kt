package com.example

import android.content.Intent
import android.net.VpnService
import android.os.ParcelFileDescriptor
import android.util.Log
import com.example.data.AppDatabase
import com.example.data.V2RayConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.channels.Channels
import java.nio.channels.ReadableByteChannel
import java.nio.channels.WritableByteChannel

class SimpleVpnService : VpnService() {

    private var vpnInterface: ParcelFileDescriptor? = null
    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(Dispatchers.IO + serviceJob)
    
    private var v2rayConfig: V2RayConfig? = null
    private var isRunning = false
    private var proxyThread: Thread? = null

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
        private const val TAG = "SimpleVpnService"
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent != null) {
            when (intent.action) {
                ACTION_CONNECT -> {
                    startVpn(intent)
                }
                ACTION_DISCONNECT -> {
                    stopVpn()
                    stopSelf()
                }
            }
        }
        return START_NOT_STICKY
    }

    private fun startVpn(intent: Intent) {
        Log.d(TAG, "Starting VPN...")
        stopVpn()

        serviceScope.launch {
            try {
                val db = AppDatabase.getDatabase(this@SimpleVpnService)
                val selectedApps = db.vpnDao().getSelectedPackageNames()

                val config = extractConfigFromIntent(intent)
                v2rayConfig = config
                isRunning = true
                Log.d(TAG, "Config: type=${config.type}, address=${config.address}, port=${config.port}")

                val builder = Builder()
                    .setSession("Win2ray Shield")
                    .setMtu(1500)
                    .addAddress("172.19.0.1", 30)
                    .addRoute("0.0.0.0", 0)
                    .addRoute("::", 0)
                    .addDnsServer("1.1.1.1")
                    .addDnsServer("8.8.8.8")

                if (selectedApps.isNotEmpty()) {
                    Log.d(TAG, "Applying Split Tunneling for ${selectedApps.size} apps")
                    for (appPackage in selectedApps) {
                        try {
                            builder.addAllowedApplication(appPackage)
                        } catch (e: Exception) {
                            Log.e(TAG, "Failed to add split-tunneling app: $appPackage", e)
                        }
                    }
                }

                vpnInterface = builder.establish()
                Log.d(TAG, "VPN Interface established successfully")

                if (vpnInterface != null) {
                    startV2RayTunnel()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error starting VPN", e)
                stopVpn()
            }
        }
    }

    private fun extractConfigFromIntent(intent: Intent): V2RayConfig {
        return V2RayConfig(
            id = intent.getStringExtra(EXTRA_CONFIG) ?: "",
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

    private fun startV2RayTunnel() {
        serviceScope.launch {
            try {
                Log.d(TAG, "Initializing V2Ray tunnel core...")
                
                createV2RayConfig()
                Log.d(TAG, "V2Ray config created")
                
                startProxyThread()
                
                Log.d(TAG, "V2Ray tunnel core started successfully")
            } catch (e: Exception) {
                Log.e(TAG, "Error starting V2Ray tunnel", e)
            }
        }
    }

    private fun createV2RayConfig() {
        val config = v2rayConfig ?: throw IllegalStateException("Config not set")
        val configJson = buildV2RayConfigJson(config)
        val configFile = File(filesDir, "v2ray_config.json")
        configFile.writeText(configJson)
        Log.d(TAG, "V2Ray config file: ${configFile.absolutePath}")
    }

    private fun buildV2RayConfigJson(config: V2RayConfig): String {
        val uuid = config.uuid ?: "00000000-0000-0000-0000-000000000000"
        val security = config.security ?: "auto"
        val network = config.network ?: "tcp"
        val sni = config.sni ?: config.address
        val tlsEnabled = config.tls
        
        return """{
  "log": {
    "loglevel": "info"
  },
  "inbounds": [
    {
      "port": 10808,
      "protocol": "socks",
      "settings": {
        "auth": "noauth",
        "listen": "127.0.0.1"
      },
      "streamSettings": {
        "network": "tcp"
      }
    }
  ],
  "outbounds": [
    {
      "protocol": "${config.type.lowercase()}",
      "settings": {
        "vnext": [
          {
            "address": "${config.address}",
            "port": ${config.port},
            "users": [
              {
                "id": "$uuid",
                "encryption": "$security",
                "flow": ""
              }
            ]
          }
        ]
      },
      "streamSettings": {
        "network": "$network",
        "security": "${if (tlsEnabled) "tls" else "none"}",
        "tlsSettings": {
          "serverName": "$sni",
          "insecure": true,
          "alpn": ["h2", "http/1.1"]
        }
      },
      "mux": {
        "enabled": true
      }
    },
    {
      "protocol": "freedom",
      "settings": {},
      "tag": "direct"
    }
  ],
  "routing": {
    "mode": "rules",
    "rules": [
      {
        "type": "field",
        "dst": ["127.0.0.1"],
        "outbound": "direct"
      }
    ]
  }
}"""
    }

    private fun startProxyThread() {
        proxyThread = Thread {
            try {
                val tunnelFd = vpnInterface?.fileDescriptor ?: return@Thread
                val inputChannel: ReadableByteChannel = Channels.newChannel(FileInputStream(tunnelFd))
                val outputChannel: WritableByteChannel = Channels.newChannel(FileOutputStream(tunnelFd))
                
                val buffer = ByteBuffer.allocateDirect(32768)
                
                while (isRunning && vpnInterface != null) {
                    try {
                        buffer.clear()
                        val read = inputChannel.read(buffer)
                        if (read > 0) {
                            buffer.flip()
                            forwardToV2Ray(buffer, outputChannel)
                        }
                    } catch (e: Exception) {
                        Log.d(TAG, "Forward iteration: ${e.message}")
                        Thread.sleep(10)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error in proxy thread", e)
            }
        }.apply {
            name = "V2RayProxyThread"
            start()
        }
    }

    private fun forwardToV2Ray(input: ByteBuffer, output: WritableByteChannel) {
        try {
            val data = ByteArray(input.remaining())
            input.get(data)
            
            val config = v2rayConfig ?: return
            
            val socket = Socket()
            socket.connect(InetSocketAddress(config.address, config.port), 5000)
            
            val outputToSocket = socket.getOutputStream()
            outputToSocket.write(data)
            outputToSocket.flush()
            
            val inputFromSocket = socket.getInputStream()
            val responseBuffer = ByteArray(32768)
            val bytesRead = inputFromSocket.read(responseBuffer)
            
            if (bytesRead > 0) {
                val responsePacket = ByteBuffer.wrap(responseBuffer, 0, bytesRead)
                output.write(responsePacket)
            }
            
            socket.close()
        } catch (e: Exception) {
            Log.e(TAG, "Forward error: ${e.message}")
        }
    }

    private fun stopVpn() {
        isRunning = false
        proxyThread?.interrupt()
        proxyThread = null
        try {
            vpnInterface?.close()
            vpnInterface = null
            v2rayConfig = null
            Log.d(TAG, "VPN stopped")
        } catch (e: Exception) {
            Log.e(TAG, "Error closing VPN", e)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceJob.cancel()
        stopVpn()
    }
}
