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
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.media.AudioManager
import android.os.*
import android.view.MotionEvent
import android.view.View
import android.widget.*
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.util.Locale

/**
 * MainActivity - LifeLine Uygulamasının Ana Arayüzü
 * 
 * Bu sınıf tüm UI etkileşimlerini, izin yönetimini, dil desteğini
 * ve haptik (titreşimli) geri bildirimleri yönetir.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var engine: LifeLineEngine
    private lateinit var audioManager: AudioManager
    private lateinit var prefs: SharedPreferences
    private var vibrator: Vibrator? = null
    
    // UI Bileşenleri - Ana Menü
    private lateinit var layoutMainMenu: LinearLayout
    private lateinit var txtStatus: TextView
    private lateinit var btnEmergency: Button
    private lateinit var btnRescue: Button
    private lateinit var btnCancel: Button
    private lateinit var btnMenu: ImageButton
    
    // UI Bileşenleri - Telsiz Ekranı
    private lateinit var layoutWalkieTalkie: LinearLayout
    private lateinit var txtWalkieStatus: TextView
    private lateinit var txtTalkingIndicator: TextView
    private lateinit var txtSignalStrength: TextView
    private lateinit var btnPushToTalk: Button
    private lateinit var btnDisconnect: Button
    
    private var bluetoothAdapter: BluetoothAdapter? = null
    private var isModeActive = false

    companion object {
        private const val REQUEST_PERMISSIONS = 101
        private const val PREFS_NAME = "LifeLinePrefs"
        private const val PREF_LANGUAGE = "language"
    }
    
    // ActivityResultLauncher for Bluetooth discoverable request
    private lateinit var discoverableLauncher: ActivityResultLauncher<Intent>

    /**
     * Bluetooth durum değişikliklerini dinleyen receiver.
     */
    private val bluetoothStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == BluetoothAdapter.ACTION_STATE_CHANGED) {
                when (intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)) {
                    BluetoothAdapter.STATE_ON -> runOnUiThread { txtStatus.append("\n> ${getString(R.string.bluetooth_on)}") }
                    BluetoothAdapter.STATE_OFF -> runOnUiThread { txtStatus.append("\n> ${getString(R.string.bluetooth_off)}") }
                }
            }
        }
    }

    /**
     * Cihaz keşfini ve RSSI (sinyal gücü) verilerini dinleyen receiver.
     */
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
                    val rssi = intent.getShortExtra(BluetoothDevice.EXTRA_RSSI, Short.MIN_VALUE).toInt()
                    device?.let { 
                        engine.addDiscoveredDevice(it, rssi)
                        // Bağlantı aktifse sinyal gücünü güncelle
                        updateSignalUI(rssi)
                    }
                }
                BluetoothAdapter.ACTION_DISCOVERY_STARTED -> runOnUiThread { txtStatus.append("\n> ${getString(R.string.scanning)}") }
                BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> {
                    runOnUiThread {
                        if (engine.isCurrentlyScanning()) {
                            engine.stopScanning()
                            engine.connectToNearestDevice()
                        }
                    }
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // Dil ve tema öncelikli yüklenir
        prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        applyLanguage()
        
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        
        // ActivityResultLauncher'ı kaydet
        discoverableLauncher = registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { result ->
            if (result.resultCode > 0) {
                txtStatus.append("\n> Görünür: ${result.resultCode} saniye")
                engine.startRescueMode()
            } else {
                txtStatus.append("\n> Görünürlük reddedildi")
                resetToMainMenu()
            }
        }

        initServices()
        initViews()
        setupEngine()
        setupListeners()
        
        registerReceivers()
        checkAndRequestPermissions()
    }

    /**
     * Sistem servislerini başlatır.
     */
    private fun initServices() {
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        @Suppress("DEPRECATION")
        vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
        } else {
            getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }
    }

    /**
     * View referanslarını bağlar.
     */
    private fun initViews() {
        layoutMainMenu = findViewById(R.id.layoutMainMenu)
        txtStatus = findViewById(R.id.txtStatus)
        btnEmergency = findViewById(R.id.btnEmergency)
        btnRescue = findViewById(R.id.btnRescue)
        btnCancel = findViewById(R.id.btnCancel)
        btnMenu = findViewById(R.id.btnMenu)
        
        layoutWalkieTalkie = findViewById(R.id.layoutWalkieTalkie)
        txtWalkieStatus = findViewById(R.id.txtWalkieStatus)
        txtTalkingIndicator = findViewById(R.id.txtTalkingIndicator)
        txtSignalStrength = findViewById(R.id.txtSignalStrength)
        btnPushToTalk = findViewById(R.id.btnPushToTalk)
        btnDisconnect = findViewById(R.id.btnDisconnect)
        
        val bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothAdapter = bluetoothManager.adapter
    }

    /**
     * LifeLine motorunu kurar.
     */
    private fun setupEngine() {
        if (bluetoothAdapter == null) {
            txtStatus.text = "HATA: Bluetooth desteklenmiyor!"
            return
        }

        engine = LifeLineEngine(bluetoothAdapter!!) { message ->
            runOnUiThread { txtStatus.append("\n> $message") }
        }
        engine.setAudioManager(audioManager)

        // Motor Geri Bildirimleri
        engine.onConnected = { isRescueMode ->
            runOnUiThread { 
                triggerVibration(100)
                showWalkieTalkieMode(isRescueMode) 
            }
        }

        engine.onDisconnected = {
            runOnUiThread {
                triggerVibration(200)
                resetToMainMenu()
                Toast.makeText(this, getString(R.string.connection_ended), Toast.LENGTH_SHORT).show()
            }
        }

        engine.onRemoteTalkingStateChanged = { isTalking ->
            runOnUiThread { updateRemoteTalkingState(isTalking) }
        }
    }

    /**
     * Buton dinleyicilerini kurar.
     */
    private fun setupListeners() {
        btnMenu.setOnClickListener { showPopupMenu(it) }
        btnRescue.setOnClickListener { if (!isModeActive && checkAllPermissions()) startRescueMode() }
        btnEmergency.setOnClickListener { if (!isModeActive && checkAllPermissions()) startEmergencyMode() }
        btnCancel.setOnClickListener { cancelCurrentMode() }
        btnDisconnect.setOnClickListener { engine.disconnect() }
        
        setupPTTButton()
    }

    /**
     * Telsiz (PTT) butonu için gelişmiş dokunma ve titreşim yönetimi.
     */
    @SuppressLint("ClickableViewAccessibility")
    private fun setupPTTButton() {
        btnPushToTalk.setOnTouchListener { _, event ->
            when (event.action and MotionEvent.ACTION_MASK) {
                MotionEvent.ACTION_DOWN -> {
                    if (engine.startTalking()) {
                        triggerVibration(50) // Kısa tık
                        updatePTTState(true)
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (engine.isTalkingNow()) {
                        engine.stopTalking()
                        triggerVibration(30) // Daha kısa tık
                        updatePTTState(false)
                    }
                    true
                }
                else -> false
            }
        }
    }

    /**
     * Haptik geri bildirim (titreşim) tetikler.
     */
    private fun triggerVibration(duration: Long) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator?.vibrate(VibrationEffect.createOneShot(duration, VibrationEffect.DEFAULT_AMPLITUDE))
        } else {
            @Suppress("DEPRECATION")
            vibrator?.vibrate(duration)
        }
    }

    /**
     * RSSI değerini görsel bir sinyal çubuğuna dönüştürür.
     */
    private fun updateSignalUI(rssi: Int) {
        runOnUiThread {
            val signal = when {
                rssi > -50 -> "📶 Mükemmel"
                rssi > -70 -> "📶 İyi"
                rssi > -90 -> "📶 Orta"
                else -> "📶 Zayıf"
            }
            txtSignalStrength.text = "$signal ($rssi dBm)"
        }
    }

    private fun updatePTTState(talking: Boolean) {
        if (talking) {
            btnPushToTalk.text = "🎤\n${getString(R.string.talking)}"
            btnPushToTalk.setBackgroundColor(getColor(R.color.emergency_red))
            txtTalkingIndicator.text = getString(R.string.release_to_stop)
        } else {
            btnPushToTalk.text = "🎤\nBASILI TUT"
            btnPushToTalk.setBackgroundColor(getColor(R.color.rescue_green))
            txtTalkingIndicator.text = getString(R.string.hold_to_talk)
        }
    }

    private fun updateRemoteTalkingState(isTalking: Boolean) {
        if (isTalking) {
            txtTalkingIndicator.text = getString(R.string.other_talking)
            txtTalkingIndicator.setTextColor(getColor(R.color.console_green))
            btnPushToTalk.isEnabled = false
            btnPushToTalk.alpha = 0.5f
            triggerVibration(80) // Karşı taraf başlayınca küçük bir uyarı
        } else {
            txtTalkingIndicator.text = getString(R.string.hold_to_talk)
            txtTalkingIndicator.setTextColor(getColor(R.color.text_light))
            btnPushToTalk.isEnabled = true
            btnPushToTalk.alpha = 1.0f
        }
    }

    // --- DİL VE MENÜ YÖNETİMİ ---

    private fun showPopupMenu(anchor: View) {
        val popup = PopupMenu(this, anchor)
        popup.menu.add(0, 1, 0, getString(R.string.about))
        popup.menu.add(0, 2, 1, getString(R.string.language))
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                1 -> showAboutDialog()
                2 -> showLanguageDialog()
            }
            true
        }
        popup.show()
    }

    private fun showLanguageDialog() {
        val languages = arrayOf(getString(R.string.turkish), getString(R.string.english))
        val currentLang = prefs.getString(PREF_LANGUAGE, "tr") ?: "tr"
        val currentIndex = if (currentLang == "tr") 0 else 1
        
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.language))
            .setSingleChoiceItems(languages, currentIndex) { dialog, which ->
                val newLang = if (which == 0) "tr" else "en"
                if (newLang != currentLang) {
                    prefs.edit().putString(PREF_LANGUAGE, newLang).apply()
                    recreate()
                }
                dialog.dismiss()
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }

    private fun applyLanguage() {
        val langCode = prefs.getString(PREF_LANGUAGE, "tr") ?: "tr"
        val locale = Locale.Builder().setLanguage(langCode).build()
        Locale.setDefault(locale)
        val config = Configuration(resources.configuration)
        config.setLocale(locale)
        @Suppress("DEPRECATION")
        resources.updateConfiguration(config, resources.displayMetrics)
    }

    private fun showAboutDialog() {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.about_title))
            .setMessage(getString(R.string.about_message))
            .setPositiveButton(getString(R.string.ok), null)
            .show()
    }

    // --- MOD VE BAĞLANTI YÖNETİMİ ---

    private fun showWalkieTalkieMode(isRescueMode: Boolean) {
        isModeActive = true
        layoutMainMenu.visibility = View.GONE
        layoutWalkieTalkie.visibility = View.VISIBLE
        txtWalkieStatus.text = getString(R.string.connected)
        btnDisconnect.visibility = if (isRescueMode) View.VISIBLE else View.GONE
    }

    private fun resetToMainMenu() {
        isModeActive = false
        layoutWalkieTalkie.visibility = View.GONE
        layoutMainMenu.visibility = View.VISIBLE
        updateButtonStates()
        txtStatus.text = getString(R.string.status_ready)
        txtSignalStrength.text = ""
    }

    @SuppressLint("MissingPermission")
    private fun startRescueMode() {
        if (bluetoothAdapter?.isEnabled != true) return
        isModeActive = true
        updateButtonStates()
        txtStatus.append("\n\n=== KURTARMA MODU ===")
        val discoverableIntent = Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE).apply {
            putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, 300)
        }
        discoverableLauncher.launch(discoverableIntent)
    }

    @SuppressLint("MissingPermission")
    private fun startEmergencyMode() {
        if (bluetoothAdapter?.isEnabled != true) return
        isModeActive = true
        updateButtonStates()
        txtStatus.append("\n\n=== ACİL DURUM MODU ===")
        if (bluetoothAdapter?.isDiscovering == true) bluetoothAdapter?.cancelDiscovery()
        engine.startEmergencyMode()
        bluetoothAdapter?.startDiscovery()
    }

    private fun cancelCurrentMode() {
        bluetoothAdapter?.cancelDiscovery()
        engine.stop()
        resetToMainMenu()
    }

    private fun updateButtonStates() {
        btnEmergency.isEnabled = !isModeActive
        btnRescue.isEnabled = !isModeActive
        btnCancel.visibility = if (isModeActive) View.VISIBLE else View.GONE
    }

    // --- İZİNLER VE YAŞAM DÖNGÜSÜ ---

    private fun checkAllPermissions(): Boolean {
        val missing = getRequiredPermissions().filter {
            ActivityCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        return if (missing.isEmpty()) true else {
            checkAndRequestPermissions()
            false
        }
    }

    private fun getRequiredPermissions(): List<String> {
        val permissions = mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissions.addAll(listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_ADVERTISE))
        }
        return permissions
    }

    private fun checkAndRequestPermissions() {
        ActivityCompat.requestPermissions(this, getRequiredPermissions().toTypedArray(), REQUEST_PERMISSIONS)
    }

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    private fun registerReceivers() {
        val btFilter = IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED)
        val discoveryFilter = IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_FOUND)
            addAction(BluetoothAdapter.ACTION_DISCOVERY_STARTED)
            addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
        }
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(bluetoothStateReceiver, btFilter, Context.RECEIVER_NOT_EXPORTED)
            registerReceiver(discoveryReceiver, discoveryFilter, Context.RECEIVER_EXPORTED)
        } else {
            registerReceiver(bluetoothStateReceiver, btFilter)
            registerReceiver(discoveryReceiver, discoveryFilter)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        safeUnregisterReceiver(bluetoothStateReceiver)
        safeUnregisterReceiver(discoveryReceiver)
        engine.stop()
    }
    
    private fun safeUnregisterReceiver(receiver: BroadcastReceiver) {
        try {
            unregisterReceiver(receiver)
        } catch (e: IllegalArgumentException) {
            // Receiver zaten kayıtlı değil
        }
    }
}
