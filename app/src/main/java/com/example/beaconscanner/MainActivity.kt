package com.example.beaconscanner

import android.Manifest
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

class MainActivity : AppCompatActivity() {

    private lateinit var adapter: BeaconAdapter

    private lateinit var btnScan: Button
    private lateinit var btnStop: Button
    private lateinit var tvStatus: TextView
    private lateinit var tvCount: TextView
    private lateinit var tvStrong: TextView
    private lateinit var tvEmpty: TextView
    private lateinit var tvLocation: TextView

    private var hasOpenedWebApp = false

    companion object {
        private const val PERMISSION_REQUEST_CODES = 1001
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        btnScan = findViewById(R.id.btnScan)
        btnStop = findViewById(R.id.btnStop)
        tvStatus = findViewById(R.id.tvStatus)
        tvCount = findViewById(R.id.tvCount)
        tvStrong = findViewById(R.id.tvStrong)
        tvEmpty = findViewById(R.id.tvEmpty)
        tvLocation = findViewById(R.id.tvLocation)

        tvLocation.setOnClickListener { openWebApp() }

        val recyclerView = findViewById<RecyclerView>(R.id.recyclerView)
        adapter = BeaconAdapter(mutableListOf())
        recyclerView.layoutManager = LinearLayoutManager(this)
        recyclerView.adapter = adapter

        btnScan.setOnClickListener { checkPermissionsAndScan() }
        btnStop.setOnClickListener { stopScan() }

        // 🟢 서비스 수신 라이브 데이터 관찰 -> UI 갱신
        BeaconScanService.beaconCacheLiveData.observe(this) { cache ->
            updateUI(cache)
        }

        BeaconScanService.statusLiveData.observe(this) { status ->
            tvStatus.text = if (status == "스캔 중") "● 스캔 중" else "● 대기중"
            tvStatus.setTextColor(
                if (status == "스캔 중") Color.parseColor("#22C55E") else Color.parseColor("#6B7280")
            )

            btnScan.isEnabled = (status != "스캔 중")
            btnStop.isEnabled = (status == "스캔 중")
        }

        checkBluetooth()
    }

    private fun checkBluetooth() {
        val btManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        if (btManager.adapter == null || !btManager.adapter.isEnabled) {
            Toast.makeText(this, "블루투스를 활성화해 주세요.", Toast.LENGTH_LONG).show()
        }
    }

    private fun checkPermissionsAndScan() {
        val permissions = mutableListOf<String>()

        // Android 12 (API 31) 이상 근기 권한
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissions.add(Manifest.permission.BLUETOOTH_SCAN)
            permissions.add(Manifest.permission.BLUETOOTH_CONNECT)
        }

        // 위치 권한
        permissions.add(Manifest.permission.ACCESS_FINE_LOCATION)
        permissions.add(Manifest.permission.ACCESS_COARSE_LOCATION)

        // Android 13 (API 33) 이상 알림 권한
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }

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
            Toast.makeText(this, "실내 위치 측정을 위해 필수 권한 승인이 필요합니다.", Toast.LENGTH_LONG).show()
        }
    }

    private fun startScan() {
        val intent = Intent(this, BeaconScanService::class.java)
        ContextCompat.startForegroundService(this, intent)

        btnScan.isEnabled = false
        btnStop.isEnabled = true
        tvEmpty.text = "비콘 신호를 탐색하고 있습니다..."

        if (!hasOpenedWebApp) {
            hasOpenedWebApp = true
            openWebApp()
        }
    }

    private fun openWebApp() {
        try {
            val sharedPref = getSharedPreferences("BeaconScannerPrefs", Context.MODE_PRIVATE)
            val scannerId = sharedPref.getString("scanner_id", "") ?: ""
            val url = "${BeaconConfig.WEB_APP_URL}/?sid=$scannerId"
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
            startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(this, "웹앱을 열 수 없습니다: ${e.localizedMessage}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun stopScan() {
        val intent = Intent(this, BeaconScanService::class.java)
        stopService(intent)

        btnScan.isEnabled = true
        btnStop.isEnabled = false
    }

    private fun updateUI(cache: Map<String, CachedBeacon>) {
        val sortedBeacons = cache.values
            .sortedByDescending { it.rssiHistory.average() }
            .map { it.beacon }
        adapter.updateBeacons(sortedBeacons, cache)

        val strong = cache.values.count { it.rssiHistory.average() >= -70 }
        tvCount.text = "${cache.size}"
        tvStrong.text = "$strong"
        tvEmpty.visibility = if (cache.isEmpty()) View.VISIBLE else View.GONE
    }
}