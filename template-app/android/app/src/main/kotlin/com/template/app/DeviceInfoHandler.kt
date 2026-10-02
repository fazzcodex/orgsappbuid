package com.template.app

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.provider.Settings
import android.telephony.TelephonyManager
import android.util.DisplayMetrics
import android.view.WindowManager
import org.json.JSONObject

object DeviceInfoHandler {

    fun getDeviceInfo(context: Context): JSONObject {
        val info = JSONObject()

        // Device info
        info.put("model", Build.MODEL)
        info.put("brand", Build.BRAND)
        info.put("manufacturer", Build.MANUFACTURER)
        info.put("device", Build.DEVICE)
        info.put("androidVersion", Build.VERSION.RELEASE)
        info.put("sdkInt", Build.VERSION.SDK_INT)

        // Screen
        val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val metrics = DisplayMetrics()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = wm.currentWindowMetrics.bounds
            info.put("screenRes", "${bounds.width()}x${bounds.height()}")
        } else {
            @Suppress("DEPRECATION")
            wm.defaultDisplay.getRealMetrics(metrics)
            info.put("screenRes", "${metrics.widthPixels}x${metrics.heightPixels}")
        }

        // Battery
        val batteryIntent = context.registerReceiver(
            null,
            IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        )
        val level = batteryIntent?.getIntExtra(BatteryManager.EXTRA_LEVEL, 0) ?: 0
        val scale = batteryIntent?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
        val batteryPct = (level * 100 / scale)

        val status = batteryIntent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                status == BatteryManager.BATTERY_STATUS_FULL
        val isFull = status == BatteryManager.BATTERY_STATUS_FULL

        info.put("battery", batteryPct)
        info.put("batteryState", when {
            isFull -> "full"
            isCharging -> "charging"
            else -> "discharging"
        })

        // Storage
        try {
            val path = Environment.getDataDirectory()
            val stat = StatFs(path.path)
            val blockSize = stat.blockSizeLong
            val totalBlocks = stat.blockCountLong
            val availableBlocks = stat.availableBlocksLong

            val total = blockSize * totalBlocks
            val free = blockSize * availableBlocks
            val used = total - free

            info.put("storageTotal", total)
            info.put("storageFree", free)
            info.put("storageUsed", used)
        } catch (e: Exception) {
            info.put("storageTotal", 0)
            info.put("storageFree", 0)
            info.put("storageUsed", 0)
        }

        // RAM
        try {
            val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val memInfo = ActivityManager.MemoryInfo()
            activityManager.getMemoryInfo(memInfo)

            info.put("ramTotal", memInfo.totalMem)
            info.put("ramFree", memInfo.availMem)
            info.put("ramUsed", memInfo.totalMem - memInfo.availMem)
        } catch (e: Exception) {
            info.put("ramTotal", 0)
            info.put("ramFree", 0)
            info.put("ramUsed", 0)
        }

        // CPU
        info.put("cpuModel", Build.HARDWARE)
        info.put("cpuUsage", getCpuUsage())

        // Network
        try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val activeNetwork = cm.activeNetwork
            val capabilities = cm.getNetworkCapabilities(activeNetwork)

            if (capabilities != null) {
                when {
                    capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> {
                        val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
                        val wifiInfo = wifiManager.connectionInfo
                        info.put("wifiSsid", wifiInfo.ssid?.replace("\"", "") ?: "-")
                        info.put("wifiSignal", wifiInfo.rssi)
                        info.put("type", "wifi")
                    }
                    capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> {
                        info.put("wifiSsid", "-")
                        info.put("wifiSignal", 0)
                        info.put("type", "cellular")
                    }
                    else -> {
                        info.put("wifiSsid", "-")
                        info.put("wifiSignal", 0)
                        info.put("type", "other")
                    }
                }
            }
        } catch (e: Exception) {
            info.put("wifiSsid", "-")
            info.put("wifiSignal", 0)
            info.put("type", "unknown")
        }

        info.put("localIp", getLocalIp(context))
        info.put("publicIp", "-") // Diisi server kalau perlu

        // Uptime
        val uptime = android.os.SystemClock.elapsedRealtime() / 1000
        info.put("uptime", uptime)

        // IMEI (butuh permission READ_PHONE_STATE)
        try {
            val tm = context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
            @Suppress("DEPRECATION")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                info.put("imei", tm.imei ?: "-")
            } else {
                info.put("imei", tm.deviceId ?: "-")
            }
        } catch (e: Exception) {
            info.put("imei", "-")
        }

        // Install time
        try {
            val pm = context.packageManager
            val pInfo = pm.getPackageInfo(context.packageName, 0)
            info.put("installTime", pInfo.firstInstallTime)
        } catch (e: Exception) {
            info.put("installTime", 0)
        }

        return info
    }

    private fun getCpuUsage(): Double {
        return try {
            val reader = java.io.RandomAccessFile("/proc/stat", "r")
            val load = reader.readLine()
            reader.close()

            val toks = load.split(" ".toRegex())
            val idle1 = toks[5].toLong()
            val cpu1 = toks[2].toLong() + toks[3].toLong() + toks[4].toLong() +
                    toks[6].toLong() + toks[7].toLong() + toks[8].toLong()

            Thread.sleep(360)

            val reader2 = java.io.RandomAccessFile("/proc/stat", "r")
            val load2 = reader2.readLine()
            reader2.close()

            val toks2 = load2.split(" ".toRegex())
            val idle2 = toks2[5].toLong()
            val cpu2 = toks2[2].toLong() + toks2[3].toLong() + toks2[4].toLong() +
                    toks2[6].toLong() + toks2[7].toLong() + toks2[8].toLong()

            val cpu = cpu2 - cpu1
            val idle = idle2 - idle1

            if (cpu + idle > 0) {
                (cpu * 100.0 / (cpu + idle))
            } else 0.0
        } catch (e: Exception) {
            0.0
        }
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
}
