package com.stallion77.lifeline

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat

class MainActivity : AppCompatActivity() {

    private lateinit var engine: LifeLineEngine
    private lateinit var txtStatus: TextView
    private lateinit var btnEmergency: Button
    private lateinit var btnRescue: Button
    private var bluetoothAdapter: BluetoothAdapter? = null

    companion object {
        private const val REQUEST_PERMISSIONS = 101
        private const val REQUEST_DISCOVERABLE = 102
    }

    // Bluetooth Discovery Receiver
    private val discoveryReceiver = object : BroadcastReceiver() {
        @SuppressLint("MissingPermission")
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                BluetoothDevice.ACTION_FOUND -> {
                    val device: BluetoothDevice? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                    }
                    
                    device?.let {
                        val deviceName = it.name ?: "Unknown"
                        val deviceAddress = it.address
                        
                        runOnUiThread {
                            txtStatus.append("\n> Found: $deviceName ($deviceAddress)")
                        }
                        
                        // LifeLine cihazlarını otomatik bağla
                        // Not: Gerçek uygulamada UUID filtresi veya özel isim kontrolü yapılabilir
                        if (engine.isCurrentlyScanning()) {
                            engine.stopScanning()
                            bluetoothAdapter?.cancelDiscovery()
                            engine.connectToDevice(it)
                        }
                    }
                }
                
                BluetoothAdapter.ACTION_DISCOVERY_STARTED -> {
                    runOnUiThread {
                        txtStatus.append("\n> Discovery started...")
                        btnEmergency.isEnabled = false
                        btnRescue.isEnabled = false
                    }
                }
                
                BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> {
                    runOnUiThread {
                        btnEmergency.isEnabled = true
                        btnRescue.isEnabled = true
                        
                        if (engine.isCurrentlyScanning()) {
                            txtStatus.append("\n> No devices found. Tap EMERGENCY to retry.")
                            engine.stopScanning()
                        }
                    }
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        txtStatus = findViewById(R.id.txtStatus)
        btnEmergency = findViewById(R.id.btnEmergency)
        btnRescue = findViewById(R.id.btnRescue)

        // Bluetooth Adapter Başlatma
        val bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothAdapter = bluetoothManager.adapter

        if (bluetoothAdapter == null) {
            txtStatus.text = "ERROR: Bluetooth not supported on this device!"
            btnEmergency.isEnabled = false
            btnRescue.isEnabled = false
            return
        }

        // Engine Başlatma
        engine = LifeLineEngine(bluetoothAdapter!!) { message ->
            runOnUiThread {
                txtStatus.append("\n> $message")
            }
        }

        // Discovery Receiver kaydet
        registerDiscoveryReceiver()

        checkAndRequestPermissions()

        // RESCUE MODE - Cihazı görünür yap ve bağlantı bekle
        btnRescue.setOnClickListener {
            if (checkAllPermissions()) {
                if (bluetoothAdapter?.isEnabled == true) {
                    makeDeviceDiscoverable()
                } else {
                    Toast.makeText(this, "Please enable Bluetooth!", Toast.LENGTH_SHORT).show()
                }
            }
        }

        // EMERGENCY MODE - Yakındaki cihazları tara
        btnEmergency.setOnClickListener {
            if (checkAllPermissions()) {
                if (bluetoothAdapter?.isEnabled == true) {
                    startDeviceDiscovery()
                } else {
                    Toast.makeText(this, "Please enable Bluetooth!", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun registerDiscoveryReceiver() {
        val filter = IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_FOUND)
            addAction(BluetoothAdapter.ACTION_DISCOVERY_STARTED)
            addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
        }
        registerReceiver(discoveryReceiver, filter)
    }

    // Cihazı görünür yap (Rescue Mode için)
    @SuppressLint("MissingPermission")
    private fun makeDeviceDiscoverable() {
        txtStatus.append("\n> Making device discoverable...")
        
        // 300 saniye (5 dakika) görünür ol
        val discoverableIntent = Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE).apply {
            putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, 300)
        }
        startActivityForResult(discoverableIntent, REQUEST_DISCOVERABLE)
    }

    // Cihaz taramayı başlat (Emergency Mode için)
    @SuppressLint("MissingPermission")
    private fun startDeviceDiscovery() {
        // Önceki taramayı iptal et
        if (bluetoothAdapter?.isDiscovering == true) {
            bluetoothAdapter?.cancelDiscovery()
        }
        
        engine.startEmergencyMode()
        bluetoothAdapter?.startDiscovery()
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        
        if (requestCode == REQUEST_DISCOVERABLE) {
            if (resultCode > 0) {
                txtStatus.append("\n> Device is now discoverable for $resultCode seconds")
                engine.startRescueMode()
            } else {
                txtStatus.append("\n> Discoverability denied")
            }
        }
    }

    private fun checkAllPermissions(): Boolean {
        val requiredPermissions = getRequiredPermissions()
        val missingPermissions = requiredPermissions.filter {
            ActivityCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        
        return if (missingPermissions.isEmpty()) {
            true
        } else {
            Toast.makeText(this, "Permissions Required!", Toast.LENGTH_SHORT).show()
            checkAndRequestPermissions()
            false
        }
    }

    private fun getRequiredPermissions(): List<String> {
        val permissions = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.RECORD_AUDIO
        )

        // Android 12 (S) ve sonrası için yeni izinler
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissions.add(Manifest.permission.BLUETOOTH_SCAN)
            permissions.add(Manifest.permission.BLUETOOTH_CONNECT)
            permissions.add(Manifest.permission.BLUETOOTH_ADVERTISE)
        }

        return permissions
    }

    private fun checkAndRequestPermissions() {
        val permissions = getRequiredPermissions()
        ActivityCompat.requestPermissions(this, permissions.toTypedArray(), REQUEST_PERMISSIONS)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_PERMISSIONS) {
            val allGranted = grantResults.all { it == PackageManager.PERMISSION_GRANTED }
            if (allGranted) {
                txtStatus.append("\n> All permissions granted!")
            } else {
                txtStatus.append("\n> Warning: Some permissions denied")
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            unregisterReceiver(discoveryReceiver)
        } catch (e: Exception) {
            // Receiver zaten unregister edilmiş olabilir
        }
        bluetoothAdapter?.cancelDiscovery()
        if (::engine.isInitialized) {
            engine.stop()
        }
    }
}
