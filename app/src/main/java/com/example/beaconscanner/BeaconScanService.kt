package com.example.beaconscanner

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.Observer
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.altbeacon.beacon.Beacon
import org.altbeacon.beacon.BeaconManager
import org.altbeacon.beacon.Region
import java.io.IOException
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.collections.ArrayDeque

/**
 * 🟢 백그라운드 BLE 비콘 스캔 & HTTP 서버 전송 포그라운드 서비스 (완벽 수정본)
 */
class BeaconScanService : LifecycleService() {

    private lateinit var beaconManager: BeaconManager
    private val beaconCache = mutableMapOf<String, CachedBeacon>()
    private val handler = Handler(Looper.getMainLooper())
    private val refreshInterval = 1000L
    private val beaconTimeoutMs = 5000L
    private val binder = LocalBinder()

    // OkHttpClient 싱글톤 적용
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(3, TimeUnit.SECONDS)
        .writeTimeout(3, TimeUnit.SECONDS)
        .build()

    private lateinit var scannerId: String
    private val mapId = "6a4e268e4b23f93d45141083" // 실제 지도의 MongoDB ObjectId
    private val region = Region("all-beacons", null, null, null)

    companion object {
        const val CHANNEL_ID = "beacon_scan_channel"
        const val NOTIFICATION_ID = 1001

        val beaconCacheLiveData = MutableLiveData<Map<String, CachedBeacon>>(emptyMap())
        val statusLiveData = MutableLiveData("대기중")
    }

    inner class LocalBinder : Binder() {
        fun getService(): BeaconScanService = this@BeaconScanService
    }

    override fun onBind(intent: Intent): IBinder {
        super.onBind(intent)
        return binder
    }

    override fun onCreate() {
        super.onCreate()
        getOrCreateScannerId()
        createNotificationChannel()

        beaconManager = BeaconManager.getInstanceForApplication(this)

        // 1. 🔥 [핵심] 30초 내 5회 제한 방지: 스캔을 중단하지 않고 continuous하게 유지
        beaconManager.foregroundScanPeriod = 1100L
        beaconManager.foregroundBetweenScanPeriod = 0L
        beaconManager.backgroundScanPeriod = 1100L
        beaconManager.backgroundBetweenScanPeriod = 0L

        // 2. JobScheduler에 의한 주기적 스캔 재시작 강제 중단
        beaconManager.setEnableScheduledScanJobs(false)

        // 3. Android 8.0+ Foreground Service Scanning 바인딩
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                beaconManager.enableForegroundServiceScanning(
                    buildNotification("비콘 신호를 스캐닝하고 있습니다."),
                    NOTIFICATION_ID
                )
            } catch (e: Exception) {
                Log.e("BeaconScanService", "Foreground scanning 설정 실패: ${e.message}")
            }
        }

        try {
            beaconManager.updateScanPeriods()
            Log.d("BeaconScanService", "✅ 비콘 스캔 주기 설정 완료 (연속 스캔 모드)")
        } catch (e: Exception) {
            Log.e("BeaconScanService", "ScanPeriod 반영 실패: ${e.message}")
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        startForeground(NOTIFICATION_ID, buildNotification("스캔 준비 중..."))
        startScanning()
        return START_STICKY
    }

    private fun startScanning() {
        try {
            beaconManager.startRangingBeacons(region)
            val regionViewModel = beaconManager.getRegionViewModel(region)

            regionViewModel.rangedBeacons.observe(this, Observer { beaconsCollection ->
                val now = System.currentTimeMillis()
                beaconsCollection?.forEach { beacon ->
                    val key = beaconKey(beacon)
                    val existing = beaconCache[key]

                    val history = existing?.rssiHistory ?: ArrayDeque()

                    if (history.size >= 15) {
                        history.removeFirst()
                    }
                    history.addLast(beacon.rssi)

                    beaconCache[key] = CachedBeacon(beacon, now, history)
                }
            })

            statusLiveData.postValue("스캔 중")
            handler.removeCallbacks(refreshRunnable)
            handler.post(refreshRunnable)
        } catch (e: Exception) {
            Log.e("BeaconScanService", "스캔 시작 중 오류 발생: ${e.message}")
        }
    }

    private val refreshRunnable = object : Runnable {
        override fun run() {
            val now = System.currentTimeMillis()
            val expiredKeys = beaconCache.entries.filter { now - it.value.lastSeenMs > beaconTimeoutMs }.map { it.key }
            expiredKeys.forEach { beaconCache.remove(it) }

            beaconCacheLiveData.postValue(beaconCache.toMap())
            updateNotification()

            val targetBeacons = listOf(
                TargetBeaconInfo("A1", 40011, 29433),
                TargetBeaconInfo("A2", 40011, 29445),
                TargetBeaconInfo("A3", 40011, 29430),
                TargetBeaconInfo("A4", 40011, 29429),
                TargetBeaconInfo("A5", 40011, 29427),
                TargetBeaconInfo("A6", 40011, 29438)
            )

            val beaconJsonList = mutableListOf<String>()
            targetBeacons.forEach { target ->
                val cached = beaconCache.values.find {
                    it.beacon.id2?.toInt() == target.major && it.beacon.id3?.toInt() == target.minor
                }
                if (cached != null && cached.rssiHistory.isNotEmpty()) {
                    val sortedRssi = cached.rssiHistory.sorted()
                    val trimCount = (sortedRssi.size * 0.2).toInt()
                    val trimmedList = if (sortedRssi.size >= 5)
                        sortedRssi.subList(trimCount, sortedRssi.size - trimCount)
                    else sortedRssi
                    val avgRssi = if (trimmedList.isNotEmpty()) trimmedList.average().toInt() else -95
                    val txPower = if (cached.beacon.txPower != 0) cached.beacon.txPower else -59
                    val distance = Math.pow(10.0, (txPower - avgRssi) / (10.0 * 2.0))
                    beaconJsonList.add("""{"beaconId":"${target.beaconId}","rssi":$avgRssi,"distance":${String.format("%.2f", distance)}}""")
                } else {
                    beaconJsonList.add("""{"beaconId":"${target.beaconId}","rssi":-100,"distance":99.0}""")
                }
            }

            val beaconsArrayJson = beaconJsonList.joinToString(",", "[", "]")
            val finalJsonBody = """
                {
                  "scannerId": "$scannerId",
                  "mapId": "$mapId",
                  "beacons": $beaconsArrayJson
                }
            """.trimIndent()

            sendToServer(finalJsonBody)
            handler.postDelayed(this, refreshInterval)
        }
    }

    private fun sendToServer(jsonBody: String) {
        val targetUrl = BeaconConfig.SERVER_URL
        val mediaType = "application/json; charset=utf-8".toMediaType()
        val body = jsonBody.toRequestBody(mediaType)

        val request = Request.Builder()
            .url(targetUrl)
            .post(body)
            .addHeader("Connection", "keep-alive")
            .build()

        httpClient.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Log.e("BeaconNetwork", "❌ [서버 전송 실패]: ${e.localizedMessage}")
            }

            override fun onResponse(call: Call, response: Response) {
                response.use {
                    if (it.isSuccessful) {
                        Log.d("BeaconNetwork", "🔥 [서버 전송 성공] (Status: ${it.code})")
                    } else {
                        Log.e("BeaconNetwork", "❌ [서버 응답 에러] HTTP 코드: ${it.code}")
                    }
                }
            }
        })
    }

    private fun getOrCreateScannerId() {
        val sharedPref = getSharedPreferences("BeaconScannerPrefs", Context.MODE_PRIVATE)
        var savedId = sharedPref.getString("scanner_id", null)
        if (savedId == null) {
            savedId = "android_" + UUID.randomUUID().toString().replace("-", "").substring(0, 8).lowercase()
            sharedPref.edit().putString("scanner_id", savedId).apply()
        }
        scannerId = savedId
    }

    private fun beaconKey(beacon: Beacon): String {
        val rawUuid = beacon.id1?.toString()?.replace("-", "")?.uppercase() ?: "UNKNOWN"
        val major = beacon.id2?.toInt() ?: 0
        val minor = beacon.id3?.toInt() ?: 0
        return "$rawUuid-$major-$minor"
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "비콘 위치 스캔",
                NotificationManager.IMPORTANCE_LOW
            ).apply { description = "백그라운드 실시간 실내 위치 측정이 구동 중입니다." }
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(text: String): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Guidant 비콘 스캐너")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification() {
        val manager = getSystemService(NotificationManager::class.java)
        manager?.notify(NOTIFICATION_ID, buildNotification("감지된 비콘: ${beaconCache.size}개 · 위치 서버 연동 중"))
    }

    fun stopScanning() {
        handler.removeCallbacks(refreshRunnable)
        try {
            beaconManager.stopRangingBeacons(region)
            beaconManager.disableForegroundServiceScanning()
        } catch (e: Exception) {
            Log.e("BeaconScanService", "스캔 중지 오류: ${e.message}")
        }
        statusLiveData.postValue("대기중")

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        stopSelf()
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacks(refreshRunnable)
    }
}