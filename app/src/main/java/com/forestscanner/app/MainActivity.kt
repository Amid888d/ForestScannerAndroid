package com.forestscanner.app

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
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.util.Locale

class MainActivity : Activity() {
    private val requestCode = 410
    private lateinit var statusText: TextView
    private lateinit var gpsText: TextView
    private lateinit var devicesText: TextView
    private lateinit var startButton: Button
    private lateinit var stopButton: Button
    private var scanner: BluetoothLeScanner? = null
    private var scanning = false
    private val devices = linkedMapOf<String, DeviceInfo>()
    private var tone: ToneGenerator? = null
    private var locationManager: LocationManager? = null
    private var gpsListener: LocationListener? = null
    private var lastBeepAt = 0L
    private var lastNewDeviceBeepAt = 0L

    data class DeviceInfo(val name: String, var rssi: Int, val address: String)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        tone = ToneGenerator(AudioManager.STREAM_MUSIC, 80)
        buildUi()
        requestNeededPermissions()
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(24, 28, 24, 24); setBackgroundColor(0xFFF3F7F3.toInt()) }
        val title = TextView(this).apply { text = "🌲 ForestScanner"; textSize = 27f; setTextColor(0xFF183A2A.toInt()); gravity = Gravity.CENTER }
        statusText = TextView(this).apply { text = "Статус: готов к сканированию"; textSize = 16f; setPadding(0, 18, 0, 12) }
        gpsText = TextView(this).apply { text = "GPS: ожидаю разрешение и координаты"; textSize = 14f; setPadding(0, 4, 0, 16) }
        startButton = Button(this).apply { text = "Начать сканирование"; setOnClickListener { startScan() } }
        stopButton = Button(this).apply { text = "Остановить"; isEnabled = false; setOnClickListener { stopScan() } }
        val hint = TextView(this).apply {
            text = "Сканируются BLE-устройства поблизости. Новый сигнал вызывает звуковой сигнал. Чем сильнее RSSI, тем чаще звук. Это не определяет точное расстояние и не гарантирует обнаружение фотоловушек без Bluetooth."
            textSize = 13f; setTextColor(0xFF52645A.toInt()); setPadding(0, 14, 0, 16)
        }
        devicesText = TextView(this).apply { text = "Найденные устройства появятся здесь."; textSize = 15f; setTextColor(0xFF1F2933.toInt()) }
        root.addView(title); root.addView(statusText); root.addView(gpsText); root.addView(startButton); root.addView(stopButton); root.addView(hint); root.addView(devicesText)
        setContentView(ScrollView(this).apply { addView(root) })
    }

    private fun neededPermissions(): Array<String> {
        val p = mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) { p.add(Manifest.permission.BLUETOOTH_SCAN); p.add(Manifest.permission.BLUETOOTH_CONNECT) }
        return p.toTypedArray()
    }

    private fun requestNeededPermissions() {
        val missing = neededPermissions().filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) requestPermissions(missing.toTypedArray(), requestCode) else startGps()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == this.requestCode) {
            if (neededPermissions().all { checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }) { startGps(); statusText.text = "Статус: разрешения получены" }
            else statusText.text = "Нужны разрешения Bluetooth и геолокации для работы приложения"
        }
    }

    private fun startGps() {
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) return
        try {
            locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
            val listener = object : LocationListener {
                override fun onLocationChanged(location: Location) {
                    gpsText.text = String.format(Locale.US, "GPS: %.6f, %.6f (точность около %.0f м)", location.latitude, location.longitude, location.accuracy)
                }
            }
            gpsListener = listener
            val providers = locationManager?.getProviders(true) ?: emptyList()
            val provider = when { providers.contains(LocationManager.GPS_PROVIDER) -> LocationManager.GPS_PROVIDER; providers.contains(LocationManager.NETWORK_PROVIDER) -> LocationManager.NETWORK_PROVIDER; else -> null }
            if (provider != null) {
                locationManager?.requestLocationUpdates(provider, 3000L, 2f, listener, Looper.getMainLooper())
                locationManager?.getLastKnownLocation(provider)?.let(listener::onLocationChanged)
            } else gpsText.text = "GPS: включи геолокацию телефона"
        } catch (_: SecurityException) { gpsText.text = "GPS: нет разрешения" }
    }

    private fun startScan() {
        if (scanning) return
        if (neededPermissions().any { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }) { requestNeededPermissions(); return }
        val manager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        val adapter: BluetoothAdapter? = manager.adapter
        if (adapter == null || !adapter.isEnabled) { statusText.text = "Статус: включи Bluetooth"; Toast.makeText(this, "Включи Bluetooth на телефоне", Toast.LENGTH_LONG).show(); return }
        scanner = adapter.bluetoothLeScanner
        if (scanner == null) { statusText.text = "BLE-сканер недоступен"; return }
        devices.clear(); renderDevices()
        try {
            scanner?.startScan(scanCallback); scanning = true; statusText.text = "Статус: сканирование идёт…"; startButton.isEnabled = false; stopButton.isEnabled = true
        } catch (e: SecurityException) { statusText.text = "Нет разрешения на Bluetooth: ${e.message}" }
    }

    private fun stopScan() {
        if (!scanning) return
        try { scanner?.stopScan(scanCallback) } catch (_: SecurityException) { }
        scanning = false; statusText.text = "Статус: сканирование остановлено"; startButton.isEnabled = true; stopButton.isEnabled = false
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val address = result.device.address ?: return
            val name = try { result.scanRecord?.deviceName ?: result.device.name ?: "Без имени" } catch (_: SecurityException) { "BLE-устройство" }
            val isNew = !devices.containsKey(address)
            val info = devices[address]
            if (info == null) devices[address] = DeviceInfo(name, result.rssi, address) else info.rssi = result.rssi
            if (isNew) { val now = System.currentTimeMillis(); if (now - lastNewDeviceBeepAt > 700) { beep(ToneGenerator.TONE_PROP_BEEP); lastNewDeviceBeepAt = now } }
            runOnUiThread { renderDevices(); updateSignalBeep() }
        }
        override fun onScanFailed(errorCode: Int) { runOnUiThread { statusText.text = "Ошибка сканирования BLE: $errorCode"; stopScan() } }
    }

    private fun updateSignalBeep() {
        if (!scanning || devices.isEmpty()) return
        val strongest = devices.values.maxOfOrNull { it.rssi } ?: return
        val interval = when { strongest >= -50 -> 350L; strongest >= -60 -> 600L; strongest >= -70 -> 950L; strongest >= -80 -> 1500L; else -> 2300L }
        val now = System.currentTimeMillis()
        if (now - lastBeepAt >= interval) { beep(ToneGenerator.TONE_PROP_ACK); lastBeepAt = now }
    }

    private fun beep(toneType: Int) { try { tone?.startTone(toneType, 110) } catch (_: Exception) { } }

    private fun renderDevices() {
        if (devices.isEmpty()) { devicesText.text = "Пока устройств не найдено."; return }
        val sorted = devices.values.sortedByDescending { it.rssi }
        devicesText.text = "Найдено устройств: ${sorted.size}\n\n" + sorted.joinToString("\n\n") { "${it.name}\nRSSI: ${it.rssi} dBm\n${it.address}" }
    }

    override fun onDestroy() {
        stopScan()
        try { gpsListener?.let { locationManager?.removeUpdates(it) } } catch (_: Exception) { }
        tone?.release(); tone = null
        super.onDestroy()
    }
}
