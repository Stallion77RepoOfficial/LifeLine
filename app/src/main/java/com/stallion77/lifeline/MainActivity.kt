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
import android.view.MotionEvent
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat

class MainActivity : AppCompatActivity() {

    private lateinit var engine: LifeLineEngine
    
    // Main Menu Views
    private lateinit var layoutMainMenu: LinearLayout
    private lateinit var txtStatus: TextView
    private lateinit var btnEmergency: Button
    private lateinit var btnRescue: Button
    
    // Walkie-Talkie Views
    private lateinit var layoutWalkieTalkie: LinearLayout
    private lateinit var txtWalkieStatus: TextView
    private lateinit var txtTalkingIndicator: TextView
    private lateinit var btnPushToTalk: Button
    private lateinit var btnDisconnect: Button
    
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

    @SuppressLint("ClickableViewAccessibility")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Main Menu Views
        layoutMainMenu = findViewById(R.id.layoutMainMenu)
        txtStatus = findViewById(R.id.txtStatus)
        btnEmergency = findViewById(R.id.btnEmergency)
        btnRescue = findViewById(R.id.btnRescue)
        
        // Walkie-Talkie Views
        layoutWalkieTalkie = findViewById(R.id.layoutWalkieTalkie)
        txtWalkieStatus = findViewById(R.id.txtWalkieStatus)
        txtTalkingIndicator = findViewById(R.id.txtTalkingIndicator)
        btnPushToTalk = findViewById(R.id.btnPushToTalk)
        btnDisconnect = findViewById(R.id.btnDisconnect)

        // Bluetooth Adapter
        val bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothAdapter = bluetoothManager.adapter

        if (bluetoothAdapter == null) {
            txtStatus.text = "ERROR: Bluetooth not supported!"
            btnEmergency.isEnabled = false
            btnRescue.isEnabled = false
            return
        }

        // Engine
        engine = LifeLineEngine(bluetoothAdapter!!) { message ->
            runOnUiThread {
                txtStatus.append("\n> $message")
            }
        }

        // Engine Callbacks
        engine.onConnected = { isRescueMode ->
            runOnUiThread {
                showWalkieTalkieMode(isRescueMode)
            }
        }

        engine.onDisconnected = {
            runOnUiThread {
                showMainMenu()
                Toast.makeText(this, "Connection ended", Toast.LENGTH_SHORT).show()
            }
        }

        engine.onRemoteTalkingStateChanged = { isTalking ->
            runOnUiThread {
                if (isTalking) {
                    txtTalkingIndicator.text = "🔊 Other person is talking..."
                    txtTalkingIndicator.setTextColor(getColor(R.color.console_green))
                    btnPushToTalk.isEnabled = false
                    btnPushToTalk.alpha = 0.5f
                } else {
                    txtTalkingIndicator.text = "Hold button to talk"
                    txtTalkingIndicator.setTextColor(getColor(R.color.text_light))
                    btnPushToTalk.isEnabled = true
                    btnPushToTalk.alpha = 1.0f
                }
            }
        }

        // Discovery Receiver
        registerDiscoveryReceiver()
        checkAndRequestPermissions()

        // RESCUE MODE Button
        btnRescue.setOnClickListener {
            if (checkAllPermissions()) {
                if (bluetoothAdapter?.isEnabled == true) {
                    makeDeviceDiscoverable()
                } else {
                    Toast.makeText(this, "Please enable Bluetooth!", Toast.LENGTH_SHORT).show()
                }
            }
        }

        // EMERGENCY MODE Button
        btnEmergency.setOnClickListener {
            if (checkAllPermissions()) {
                if (bluetoothAdapter?.isEnabled == true) {
                    startDeviceDiscovery()
                } else {
                    Toast.makeText(this, "Please enable Bluetooth!", Toast.LENGTH_SHORT).show()
                }
            }
        }

        // Push-to-Talk Button (Touch Listener)
        btnPushToTalk.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    // Basıldı - Konuşmaya başla
                    engine.startTalking()
                    btnPushToTalk.text = "🎤\nTALKING..."
                    btnPushToTalk.setBackgroundColor(getColor(R.color.emergency_red))
                    txtTalkingIndicator.text = "Release to send"
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    // Bırakıldı - Konuşmayı bitir
                    engine.stopTalking()
                    btnPushToTalk.text = "🎤\nHOLD TO TALK"
                    btnPushToTalk.setBackgroundColor(getColor(R.color.rescue_green))
                    txtTalkingIndicator.text = "Hold button to talk"
                    true
                }
                else -> false
            }
        }

        // Disconnect Button (Sadece Rescue Mode)
        btnDisconnect.setOnClickListener {
            engine.disconnect()
        }
    }

    private fun showWalkieTalkieMode(isRescueMode: Boolean) {
        layoutMainMenu.visibility = View.GONE
        layoutWalkieTalkie.visibility = View.VISIBLE
        
        txtWalkieStatus.text = "CONNECTED"
        txtTalkingIndicator.text = "Hold button to talk"
        
        // Disconnect butonu sadece Rescue Mode'da görünür
        btnDisconnect.visibility = if (isRescueMode) View.VISIBLE else View.GONE
    }

    private fun showMainMenu() {
        layoutWalkieTalkie.visibility = View.GONE
        layoutMainMenu.visibility = View.VISIBLE
        
        txtStatus.text = getString(R.string.status_ready)
    }

    private fun registerDiscoveryReceiver() {
        val filter = IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_FOUND)
            addAction(BluetoothAdapter.ACTION_DISCOVERY_STARTED)
            addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
        }
        registerReceiver(discoveryReceiver, filter)
    }

    @SuppressLint("MissingPermission")
    private fun makeDeviceDiscoverable() {
        txtStatus.append("\n> Making device discoverable...")
        
        val discoverableIntent = Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE).apply {
            putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, 300)
        }
        @Suppress("DEPRECATION")
        startActivityForResult(discoverableIntent, REQUEST_DISCOVERABLE)
    }

    @SuppressLint("MissingPermission")
    private fun startDeviceDiscovery() {
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
                txtStatus.append("\n> Device discoverable for $resultCode seconds")
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
        } catch (e: Exception) { }
        bluetoothAdapter?.cancelDiscovery()
        if (::engine.isInitialized) {
            engine.stop()
        }
    }
}
