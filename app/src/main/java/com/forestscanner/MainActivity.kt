package com.forestscanner

import android.Manifest
import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.media.ToneGenerator
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.util.Locale

class MainActivity : Activity() {
    private lateinit var status: TextView
    private lateinit var gps: TextView
    private lateinit var devices: TextView
    private lateinit var scanButton: Button
    private var scanner: BluetoothLeScanner? = null
    private var scanning = false
    private val found = linkedMapOf<String, Pair<String, Int>>()
    private val handler = Handler(Looper.getMainLooper())
    private val tone by lazy { ToneGenerator(AudioManager.STREAM_NOTIFICATION, 75) }
    private var lastBeep = 0L
    private var locationManager: LocationManager? = null
    private val permissionRequest = 44

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(28, 36, 28, 24) }
        val title = TextView(this).apply { text = "FOREST SCANNER"; textSize = 25f; setTextColor(0xFF1B5E20.toInt()) }
        status = TextView(this).apply { text = "Готов к сканированию"; textSize = 16f; setPadding(0, 18, 0, 12) }
        gps = TextView(this).apply { text = "GPS: ожидаю координаты…"; textSize = 14f; setPadding(0, 0, 0, 16) }
        scanButton = Button(this).apply { text = "НАЧАТЬ ПОИСК"; setOnClickListener { if (scanning) stopScan() else checkPermissionsAndStart() } }
        val subtitle = TextView(this).apply { text = "Найденные Bluetooth LE устройства (RSSI):"; textSize = 15f; setPadding(0, 18, 0, 8) }
        devices = TextView(this).apply { textSize = 14f; text = "Пока ничего не найдено" }
        val scroll = ScrollView(this).apply { addView(devices) }
        root.addView(title); root.addView(status); root.addView(gps); root.addView(scanButton); root.addView(subtitle)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
        locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        requestLocation()
    }

    private fun requiredPermissions(): Array<String> = if (Build.VERSION.SDK_INT >= 31) arrayOf(
        Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT,
        Manifest.permission.ACCESS_FINE_LOCATION
    ) else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)

    private fun checkPermissionsAndStart() {
        val missing = requiredPermissions().filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) requestPermissions(missing.toTypedArray(), permissionRequest) else startScan()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == permissionRequest && grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
            requestLocation(); startScan()
        } else status.text = "Нужны разрешения Bluetooth и геолокации для работы приложения."
    }

    private fun startScan() {
        val manager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        val adapter: BluetoothAdapter? = manager.adapter
        if (adapter == null || !adapter.isEnabled) { status.text = "Включи Bluetooth на телефоне"; return }
        scanner = adapter.bluetoothLeScanner
        if (scanner == null) { status.text = "BLE-сканер недоступен"; return }
        found.clear(); devices.text = "Ищу устройства…"
        try {
            scanner?.startScan(callback)
            scanning = true; scanButton.text = "ОСТАНОВИТЬ ПОИСК"
            status.text = "Сканирование идёт. Сигнал учащается при усилении RSSI."
        } catch (e: SecurityException) { status.text = "Нет разрешения Bluetooth: ${e.message}" }
    }

    private fun stopScan() {
        try { scanner?.stopScan(callback) } catch (_: SecurityException) {}
        scanning = false; scanButton.text = "НАЧАТЬ ПОИСК"; status.text = "Поиск остановлен"
        handler.removeCallbacks(beepLoop)
    }

    private val callback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val address = result.device.address ?: return
            val name = try { result.scanRecord?.deviceName ?: result.device.name ?: "Без имени" } catch (_: SecurityException) { "BLE устройство" }
            val rssi = result.rssi
            runOnUiThread {
                found[address] = name to rssi
                devices.text = found.entries.sortedByDescending { it.value.second }.joinToString("\n\n") {
                    "${it.value.first}\n${it.key}\nRSSI: ${it.value.second} dBm"
                }
            }
        }
        override fun onScanFailed(errorCode: Int) { runOnUiThread { status.text = "Ошибка сканирования: $errorCode" } }
    }

    private val beepLoop = object : Runnable {
        override fun run() {
            if (!scanning) return
            val strongest = found.values.maxOfOrNull { it.second }
            if (strongest != null) {
                val interval = when {
                    strongest >= -55 -> 350L
                    strongest >= -65 -> 650L
                    strongest >= -75 -> 1100L
                    else -> 1800L
                }
                val now = System.currentTimeMillis()
                if (now - lastBeep >= interval) { tone.startTone(ToneGenerator.TONE_PROP_BEEP, 100); lastBeep = now }
            }
            handler.postDelayed(this, 150)
        }
    }

    private fun requestLocation() {
        val fine = checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (!fine) return
        try {
            val lm = locationManager ?: return
            val last: Location? = lm.getLastKnownLocation(LocationManager.GPS_PROVIDER) ?: lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
            if (last != null) showLocation(last)
            val listener = object : LocationListener { override fun onLocationChanged(location: Location) { showLocation(location) } }
            if (lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 3000L, 2f, listener, Looper.getMainLooper())
            else if (lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) lm.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 3000L, 2f, listener, Looper.getMainLooper())
        } catch (_: Exception) { gps.text = "GPS: включи геолокацию" }
    }

    private fun showLocation(location: Location) {
        runOnUiThread { gps.text = String.format(Locale.US, "GPS телефона: %.6f, %.6f", location.latitude, location.longitude) }
    }

    override fun onDestroy() { stopScan(); tone.release(); super.onDestroy() }
}
