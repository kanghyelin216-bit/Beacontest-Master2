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
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import okhttp3.Call
import okhttp3.Callback
import org.altbeacon.beacon.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException

class MainActivity : AppCompatActivity() {

    private lateinit var beaconManager: BeaconManager
    private lateinit var adapter: BeaconAdapter

    // 비콘 캐시
    private val beaconCache = mutableMapOf<String, CachedBeacon>()

    private var lastSentPosition: LocationEstimator.Position? = null

    // 화면 갱신용 Handler
    private val handler = Handler(Looper.getMainLooper())
    private val refreshInterval = 500L  // 0.5초마다 화면 갱신

    // 비콘이 이 시간(ms) 동안 안 잡히면 목록에서 제거
    private val beaconTimeoutMs = 4000L

    private lateinit var btnScan: Button
    private lateinit var btnStop: Button
    private lateinit var tvStatus: TextView
    private lateinit var tvCount: TextView
    private lateinit var tvStrong: TextView
    private lateinit var tvEmpty: TextView
    private lateinit var tvLocation: TextView

    companion object {
        private const val PERMISSION_REQUEST_CODE = 1001
    }

    // 캐시 항목
    data class CachedBeacon(
        val beacon: Beacon,
        val lastSeenMs: Long,
        val rssiHistory: ArrayDeque<Int> = ArrayDeque(5) // 최근 5개 RSSI
    )

    // 화면 갱신 루프
    private val refreshRunnable = object : Runnable {
        override fun run() {
            val now = System.currentTimeMillis()

            // 오래된 비콘 제거
            val expired = beaconCache.entries.filter { now - it.value.lastSeenMs > beaconTimeoutMs }
            expired.forEach { beaconCache.remove(it.key) }

            // UI 갱신
            updateUI()
            val distances = beaconCache.values.mapNotNull { cached ->
                val key = beaconKey(cached.beacon)
                val configInfo = BeaconConfig.BEACONS.find { it.key == key } ?: return@mapNotNull null
                val avgRssi = cached.rssiHistory.average().toInt()
                val dist = LocationEstimator.rssiToDistance(avgRssi, configInfo.txPower)
                key to dist
            }
                .toMap()
            val position = LocationEstimator.estimatePosition(distances)
            if (position != null) {
                val last = lastSentPosition
                val moved = last == null ||
                        Math.hypot(position.x - last.x, position.y - last.y) > 0.3 // 30cm 이상 이동 시만 전송
                if (moved) {
                    sendToServer(position.x, position.y )
                    lastSentPosition = position
                }
                tvLocation.text = "추정 위치 : X=%.2f m, Y = %.2f m".format(position.x, position.y)
            } else {
                tvLocation.text = "위치 추정 중 ... (등록 비콘 감지 필요)"
            }
            handler.postDelayed(this, refreshInterval)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        btnScan  = findViewById(R.id.btnScan)
        btnStop  = findViewById(R.id.btnStop)
        tvStatus = findViewById(R.id.tvStatus)
        tvCount  = findViewById(R.id.tvCount)
        tvStrong = findViewById(R.id.tvStrong)
        tvEmpty  = findViewById(R.id.tvEmpty)
        tvLocation = findViewById(R.id.tvLocation)

        val recyclerView = findViewById<RecyclerView>(R.id.recyclerView)
        adapter = BeaconAdapter(mutableListOf())
        recyclerView.layoutManager = LinearLayoutManager(this)
        recyclerView.adapter = adapter

        beaconManager = BeaconManager.getInstanceForApplication(this)

        // 스캔 주기 설정
        beaconManager.foregroundScanPeriod = 2000L       // 2초 스캔
        beaconManager.foregroundBetweenScanPeriod = 0L  // 연속 스캔

        btnScan.setOnClickListener { checkPermissionsAndScan() }
        btnStop.setOnClickListener { stopScan() }

        val region = Region("all-beacons", null, null, null)
        val regionViewModel = beaconManager.getRegionViewModel(region)
        regionViewModel.rangedBeacons.observe(this) { beacons ->
            val now = System.currentTimeMillis()
            for (beacon in beacons) {
                val key = beaconKey(beacon)
                val existing = beaconCache[key]
                val history = existing?.rssiHistory ?: ArrayDeque(5)
                if (history.size >= 5) history.removeFirst()
                history.addLast(beacon.rssi)
                beaconCache[key] = CachedBeacon(
                    beacon = beacon,
                    lastSeenMs = now,
                    rssiHistory = history
                )
            }
        }

        checkBluetooth()
    }

    private fun checkBluetooth() {
        val btManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        if (btManager.adapter == null || !btManager.adapter.isEnabled) {
            Toast.makeText(this, "블루투스를 켜주세요", Toast.LENGTH_LONG).show()
        }
    }

    // ── 🛠️ [권한 충돌 전면 수정] 안전하게 필수 권한만 동시 요청하도록 변경 ──
    private fun checkPermissionsAndScan() {
        val permissions = mutableListOf<String>()

        // 안드로이드 12 (API 31) 이상일 때 블루투스 전용 권한 추가
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissions.add(Manifest.permission.BLUETOOTH_SCAN)
            permissions.add(Manifest.permission.BLUETOOTH_CONNECT)
        }

        // 일반 위치 권한 필수 추가 (비콘용)
        permissions.add(Manifest.permission.ACCESS_FINE_LOCATION)
        permissions.add(Manifest.permission.ACCESS_COARSE_LOCATION)

        // ⚠️ 충돌을 일으키는 ACCESS_BACKGROUND_LOCATION 권한 요청 코드는 완벽히 제거했습니다.

        // 허용되지 않은 권한이 있는지 체크
        val missing = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (missing.isEmpty()) {
            startScan()
        } else {
            // 깔끔하게 안드로이드 정식 승인 팝업을 띄웁니다.
            ActivityCompat.requestPermissions(this, missing.toTypedArray(), PERMISSION_REQUEST_CODE)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSION_REQUEST_CODE &&
            grantResults.isNotEmpty() &&
            grantResults.all { it == PackageManager.PERMISSION_GRANTED }
        ) {
            startScan()
        } else {
            // 여전히 거절이 난다면 사용자에게 안내 메시지를 띄웁니다.
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

        // 갱신 시작
        handler.post(refreshRunnable)
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
        tvEmpty.visibility = if (beaconCache.isEmpty())
            View.VISIBLE else View.GONE
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacks(refreshRunnable)
        beaconManager.stopRangingBeacons(Region("all-beacons", null, null, null))
    }

    private val httpClient = OkHttpClient()

    private fun sendToServer(x: Double, y: Double) {
        // 현재 잡힌 비콘 중 RSSI(신호 세기) 평균값이 가장 높은(가장 가까운) 비콘 1개를 찾습니다.
        val strongestCached = beaconCache.values.maxByOrNull { it.rssiHistory.average() } ?: return
        val targetBeacon = strongestCached.beacon

        // 비콘의 Minor 값을 기준으로 A1~A6 매핑
        val beaconId = when(targetBeacon.id3?.toInt()) {
            29433 -> "A1"
            29445 -> "A2"
            29430 -> "A3"
            29429 -> "A4"
            29427 -> "A5"
            else -> "A1"
        }

        // ✅ 미터 → 픽셀 변환 후 x, y 함께 전송 (서버가 요구하는 형식)
        val pixelX = (x * BeaconConfig.PIXEL_SCALE).toInt()
        val pixelY = (y * BeaconConfig.PIXEL_SCALE).toInt()

        val json = """{"beaconId":"$beaconId","x":$pixelX,"y":$pixelY}"""  // 기존 beaconId 유지 + x,y 추가
        val body = json.toRequestBody("application/json".toMediaType())
        val request = Request.Builder()
            .url("${BeaconConfig.SERVER_URL}/beacon")
            .post(body)
            .build()

        httpClient.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                android.util.Log.e("BeaconNetwork", "🌐 서버 전송 실패: ${e.message}")
            }
            override fun onResponse(call: Call, response: Response) {
                if (response.isSuccessful) {
                    android.util.Log.d("BeaconNetwork", "🌐 전송 성공 -> beaconId=$beaconId, x=$pixelX, y=$pixelY")
                }
                response.close()
            }
        })
    }

    private fun beaconKey(beacon: Beacon): String {
        // 1. 비콘에서 받아온 UUID에서 하이픈(-)을 전부 제거하고 대문자로 통일합니다.
        val rawUuid = beacon.id1?.toString()?.replace("-", "")?.uppercase() ?: "UNKNOWN"
        val major = beacon.id2?.toInt() ?: 0
        val minor = beacon.id3?.toInt() ?: 0

        // 2. 이 로그를 통해 실제 안드로이드가 비콘을 찾았을 때 어떤 Key 모양을 만들어내는지 로그캣(Logcat)에서 확인용
        android.util.Log.d("BeaconScanKey", "🔍 감지된 비콘 생성 Key: $rawUuid-$major-$minor")

        return "$rawUuid-$major-$minor"
    }
}