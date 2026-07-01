package com.example.beaconscanner

import android.Manifest
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Button
import android.widget.TextView
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Observer
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import org.altbeacon.beacon.*
import java.util.UUID
import java.util.concurrent.Executors
import kotlin.collections.ArrayDeque

class MainActivity : AppCompatActivity() {

    private lateinit var beaconManager: BeaconManager
    private lateinit var adapter: BeaconAdapter

    private val beaconCache = mutableMapOf<String, CachedBeacon>()
    private val handler = Handler(Looper.getMainLooper())
    private val refreshInterval = 1000L  // 1초 간격 수집 및 서버 전송
    private val beaconTimeoutMs = 5000L

    private val networkExecutor = Executors.newSingleThreadExecutor()

    private lateinit var btnScan: Button
    private lateinit var btnStop: Button
    private lateinit var tvStatus: TextView
    private lateinit var tvCount: TextView
    private lateinit var tvStrong: TextView
    private lateinit var tvEmpty: TextView
    private lateinit var tvLocation: TextView

    private lateinit var scannerId: String
    private val mapId = "6a4e268e4b23f93d45141083" // 리액트 몽고DB ObjectId 포맷 동기화
    private var hasOpenedWebApp = false

    companion object {
        private const val PERMISSION_REQUEST_CODES = 1001
    }

    data class CachedBeacon(
        val beacon: Beacon,
        val lastSeenMs: Long,
        val rssiHistory: ArrayDeque<Int> = ArrayDeque()
    )

    // 🟢 제공해주신 실제 비콘 스펙을 기반으로 데이터 모델 정의
    data class TargetBeaconInfo(
        val beaconId: String,  // 리액트가 인식할 ID (A1~A6)
        val major: Int,
        val minor: Int
    )

    private val refreshRunnable = object : Runnable {
        override fun run() {
            val now = System.currentTimeMillis()

            // 1. 유효시간 지난 비콘 제거
            val expired = beaconCache.entries.filter { now - it.value.lastSeenMs > beaconTimeoutMs }
            expired.forEach { beaconCache.remove(it.key) }

            updateUI()

            // 2. 제공해주신 6개 실제 비콘 스펙 하드코딩 매핑 (Major는 모두 동일하므로 확실한 식별을 위해 Minor 기준 매핑)
            val targetBeacons = listOf(
                TargetBeaconInfo("A1", 40011, 29433), // AEB9
                TargetBeaconInfo("A2", 40011, 29445), // AEC5
                TargetBeaconInfo("A3", 40011, 29430), // AEB6
                TargetBeaconInfo("A4", 40011, 29429), // AEB5
                TargetBeaconInfo("A5", 40011, 29427), // AEB3
                TargetBeaconInfo("A6", 40011, 29438)  // AEBE
            )

            val beaconJsonList = mutableListOf<String>()

            targetBeacons.forEach { target ->
                // 캐시에서 Major와 Minor가 동시에 일치하는 비콘을 찾습니다.
                val cached = beaconCache.values.find {
                    it.beacon.id2?.toInt() == target.major && it.beacon.id3?.toInt() == target.minor
                }

                if (cached != null && cached.rssiHistory.isNotEmpty()) {
                    val sortedRssi = cached.rssiHistory.sorted()

                    // 정밀 보정을 위해 앞뒤 튀는 값 다듬기 (트리밍)
                    val trimCount = (sortedRssi.size * 0.2).toInt()
                    val trimmedList = if (sortedRssi.size >= 5) {
                        sortedRssi.subList(trimCount, sortedRssi.size - trimCount)
                    } else {
                        sortedRssi
                    }

                    val avgRssi = if (trimmedList.isNotEmpty()) trimmedList.average().toInt() else -95

                    // TxPower가 없는 경우 기본값 -59 처리하여 무한대 거리 연산 방지
                    val txPower = if (cached.beacon.txPower != 0) cached.beacon.txPower else -59
                    val distance = Math.pow(10.0, (txPower - avgRssi) / (10.0 * 2.0))

                    // 🟢 리액트가 원하는 "A1"~"A6" 포맷으로 JSON을 최종 조립합니다.
                    val item = """{"beaconId":"${target.beaconId}","rssi":$avgRssi,"distance":${String.format("%.2f", distance)}}"""
                    beaconJsonList.add(item)
                } else {
                    // 주변에 감지 안 되는 비콘 데이터 유실 방지 기본값 패킹
                    val item = """{"beaconId":"${target.beaconId}","rssi":-100,"distance":99.0}"""
                    beaconJsonList.add(item)
                }
            }

            // 3. 백엔드 최종 스펙 바디 포맷 조립
            val beaconsArrayJson = beaconJsonList.joinToString(",", "[", "]")
            val finalJsonBody = """
                {
                  "scannerId": "$scannerId",
                  "mapId": "$mapId",
                  "beacons": $beaconsArrayJson
                }
            """.trimIndent()

            // 4. 백엔드로 데이터 다이렉트 전송 실행
            sendToServer(finalJsonBody)
            tvLocation.text = "서버로 실제 비콘 데이터 전송 중 (A1~A6 매핑 완벽)"

            handler.postDelayed(this, refreshInterval)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        getOrCreateScannerId()

        btnScan  = findViewById(R.id.btnScan)
        btnStop  = findViewById(R.id.btnStop)
        tvStatus = findViewById(R.id.tvStatus)
        tvCount  = findViewById(R.id.tvCount)
        tvStrong = findViewById(R.id.tvStrong)
        tvEmpty  = findViewById(R.id.tvEmpty)
        tvLocation = findViewById(R.id.tvLocation)

        tvLocation.setOnClickListener { openWebApp() }

        val recyclerView = findViewById<RecyclerView>(R.id.recyclerView)
        adapter = BeaconAdapter(mutableListOf())
        recyclerView.layoutManager = LinearLayoutManager(this)
        recyclerView.adapter = adapter

        beaconManager = BeaconManager.getInstanceForApplication(this)
        beaconManager.foregroundScanPeriod = 500L
        beaconManager.foregroundBetweenScanPeriod = 250L

        btnScan.setOnClickListener { checkPermissionsAndScan() }
        btnStop.setOnClickListener { stopScan() }

        val region = Region("all-beacons", null, null, null)
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

                beaconCache[key] = CachedBeacon(
                    beacon = beacon,
                    lastSeenMs = now,
                    rssiHistory = history
                )
            }
        })

        checkBluetooth()
    }

    private fun getOrCreateScannerId() {
        val sharedPref = getSharedPreferences("BeaconScannerPrefs", Context.MODE_PRIVATE)
        var savedId = sharedPref.getString("scanner_id", null)
        if (savedId == null) {
            savedId = "android_" + UUID.randomUUID().toString().replace("-", "").substring(0, 8).lowercase()
            sharedPref.edit().putString("scanner_id", savedId).apply()
        }
        scannerId = savedId
        android.util.Log.d("BeaconNetwork", "📱 내 기기 발급 스캐너 ID: $scannerId")
    }

    private fun checkBluetooth() {
        val btManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        if (btManager.adapter == null || !btManager.adapter.isEnabled) {
            Toast.makeText(this, "블루투스를 켜주세요", Toast.LENGTH_LONG).show()
        }
    }

    private fun checkPermissionsAndScan() {
        val permissions = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissions.add(Manifest.permission.BLUETOOTH_SCAN)
            permissions.add(Manifest.permission.BLUETOOTH_CONNECT)
        }
        permissions.add(Manifest.permission.ACCESS_FINE_LOCATION)
        permissions.add(Manifest.permission.ACCESS_COARSE_LOCATION)

        val missing = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (missing.isEmpty()) {
            startScan()
        } else {
            ActivityCompat.requestPermissions(this, missing.toTypedArray(), PERMISSION_REQUEST_CODES)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSION_REQUEST_CODES &&
            grantResults.isNotEmpty() &&
            grantResults.all { it == PackageManager.PERMISSION_GRANTED }
        ) {
            startScan()
        } else {
            Toast.makeText(this, "필수 블루투스/위치 권한 승인이 필요합니다.", Toast.LENGTH_LONG).show()
        }
    }

    private fun startScan() {
        beaconManager.startRangingBeacons(Region("all-beacons", null, null, null))
        btnScan.isEnabled = false
        btnStop.isEnabled = true
        tvStatus.text = "● 스캔 중"
        tvStatus.setTextColor(Color.parseColor("#22C55E"))
        tvEmpty.text = "스캔 중... 비콘을 탐색하고 있습니다"
        handler.post(refreshRunnable)

        if (!hasOpenedWebApp) {
            hasOpenedWebApp = true
            openWebApp()
        }
    }

    private fun openWebApp() {
        try {
            val url = "http://192.168.219.106:5173/?sid=$scannerId"
            val intent = android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url))
            startActivity(intent)
            android.util.Log.d("BeaconNetwork", "🌐 웹앱 오픈: $url")
        } catch (e: Exception) {
            Toast.makeText(this, "웹앱을 열 수 없습니다: ${e.localizedMessage}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun stopScan() {
        beaconManager.stopRangingBeacons(Region("all-beacons", null, null, null))
        handler.removeCallbacks(refreshRunnable)
        btnScan.isEnabled = true
        btnStop.isEnabled = false
        tvStatus.text = "● 대기중"
        tvStatus.setTextColor(Color.parseColor("#6B7280"))
    }

    private fun updateUI() {
        val sortedBeacons = beaconCache.values
            .sortedByDescending { it.rssiHistory.average() }
            .map { it.beacon }
        adapter.updateBeacons(sortedBeacons, beaconCache)

        val strong = beaconCache.values.count { it.rssiHistory.average() >= -70 }
        tvCount.text = "${beaconCache.size}"
        tvStrong.text = "$strong"
        tvEmpty.visibility = if (beaconCache.isEmpty()) View.VISIBLE else View.GONE
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacks(refreshRunnable)
        beaconManager.stopRangingBeacons(Region("all-beacons", null, null, null))
        networkExecutor.shutdown()
    }

    private fun sendToServer(jsonBody: String) {
        android.util.Log.d("BeaconNetwork", "🚀 [서버 전송 시도]\n$jsonBody")
        val targetUrl = "http://192.168.219.106:4000/api/location"

        networkExecutor.execute {
            var urlConnection: java.net.HttpURLConnection? = null
            try {
                val url = java.net.URL(targetUrl)
                urlConnection = url.openConnection() as java.net.HttpURLConnection

                urlConnection.requestMethod = "POST"
                urlConnection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                urlConnection.setRequestProperty("Connection", "keep-alive")
                urlConnection.doOutput = true

                urlConnection.connectTimeout = 3000
                urlConnection.readTimeout = 3000

                val outputStream = urlConnection.outputStream
                val writer = java.io.BufferedWriter(java.io.OutputStreamWriter(outputStream, "UTF-8"))
                writer.write(jsonBody)
                writer.flush()
                writer.close()
                outputStream.close()

                val responseCode = urlConnection.responseCode
                if (responseCode == 200 || responseCode == 201) {
                    android.util.Log.d("BeaconNetwork", "🔥 [서버 전송 완벽 성공] 백엔드 데이터 적재 완료")
                } else {
                    android.util.Log.e("BeaconNetwork", "❌ [서버 응답 에러] HTTP 코드: $responseCode")
                }

            } catch (e: Exception) {
                android.util.Log.e("BeaconNetwork", "❌ [통신 실패 상세 원인]: ${e.localizedMessage}")
            } finally {
                urlConnection?.disconnect()
            }
        }
    }

    private fun beaconKey(beacon: Beacon): String {
        val rawUuid = beacon.id1?.toString()?.replace("-", "")?.uppercase() ?: "UNKNOWN"
        val major = beacon.id2?.toInt() ?: 0
        val minor = beacon.id3?.toInt() ?: 0
        return "$rawUuid-$major-$minor"
    }
}