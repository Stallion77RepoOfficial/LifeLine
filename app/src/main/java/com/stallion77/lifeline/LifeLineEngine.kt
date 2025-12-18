package com.stallion77.lifeline

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * LifeLineEngine - Şifreli Bluetooth PTT iletişim motoru
 * 
 * Özelliker:
 * - Uygulama seviyesi AES-GCM şifreleme
 * - Geliştirilmiş thread yönetimi (ExecutorService)
 * - Güçlendirilmiş heartbeat mekanizması
 * - Race condition koruması
 * - Otomatik bağlantı yönetimi
 */
class LifeLineEngine(
    private val bluetoothAdapter: BluetoothAdapter,
    private val statusCallback: (String) -> Unit
) {
    
    private var socket: BluetoothSocket? = null
    private var serverSocket: BluetoothServerSocket? = null
    private var inputStream: InputStream? = null
    private var outputStream: OutputStream? = null
    
    private var audioCore: AudioCore? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    
    // Thread-safe state flags
    private val isConnected = AtomicBoolean(false)
    private val isScanning = AtomicBoolean(false)
    private val isTalking = AtomicBoolean(false)
    private val isBusy = AtomicBoolean(false)
    private val isShuttingDown = AtomicBoolean(false)
    
    // Heartbeat yönetimi
    private val lastHeartbeatReceived = AtomicLong(0L)
    private var heartbeatExecutor: ScheduledExecutorService? = null
    
    // Thread yönetimi
    private var listenThread: Thread? = null
    private var controlThread: Thread? = null
    private val connectionLock = Any()

    // Callbacks
    var onConnected: ((isRescueMode: Boolean) -> Unit)? = null
    var onDisconnected: (() -> Unit)? = null
    var onRemoteTalkingStateChanged: ((isTalking: Boolean) -> Unit)? = null

    private var isRescueMode = false
    
    // RSSI cihazları
    private val discoveredDevices = mutableListOf<Pair<BluetoothDevice, Int>>()
    private var currentDeviceIndex = 0

    fun setAudioManager(audioManager: AudioManager) {
        audioCore = AudioCore(audioManager)
    }

    // --- RSSI İLE CİHAZ EKLEME ---
    @SuppressLint("MissingPermission")
    fun addDiscoveredDevice(device: BluetoothDevice, rssi: Int) {
        if (!isScanning.get()) return
        
        synchronized(discoveredDevices) {
            if (discoveredDevices.any { it.first.address == device.address }) return
            discoveredDevices.add(Pair(device, rssi))
        }
        
        val deviceName = device.name ?: device.address
        statusCallback("Bulundu: $deviceName ($rssi dBm)")
    }

    @SuppressLint("MissingPermission")
    fun connectToNearestDevice() {
        synchronized(discoveredDevices) {
            if (discoveredDevices.isEmpty()) {
                statusCallback("Cihaz bulunamadı")
                onDisconnected?.invoke()
                return
            }
            
            discoveredDevices.sortByDescending { it.second }
            statusCallback("${discoveredDevices.size} cihaz deneniyor...")
            currentDeviceIndex = 0
        }
        tryConnectToNextDevice()
    }

    @SuppressLint("MissingPermission")
    private fun tryConnectToNextDevice() {
        val devicePair: Pair<BluetoothDevice, Int>?
        
        synchronized(discoveredDevices) {
            if (currentDeviceIndex >= discoveredDevices.size) {
                statusCallback("Hiçbir cihaza bağlanılamadı")
                discoveredDevices.clear()
                onDisconnected?.invoke()
                return
            }
            devicePair = discoveredDevices[currentDeviceIndex]
        }
        
        val (device, _) = devicePair ?: return
        val deviceName = device.name ?: device.address
        
        Thread {
            statusCallback("Bağlanılıyor: $deviceName")
            try {
                synchronized(connectionLock) {
                    socket = device.createInsecureRfcommSocketToServiceRecord(Constants.SERVICE_UUID)
                    socket?.connect()
                    
                    setupConnection()
                }
                
                statusCallback("Bağlandı: $deviceName ✓")
                
                isConnected.set(true)
                isBusy.set(true)
                isShuttingDown.set(false)
                
                synchronized(discoveredDevices) {
                    discoveredDevices.clear()
                }
                
                mainHandler.post { onConnected?.invoke(false) }
                startHeartbeat()
                startListening()
                startControlListener()
                
            } catch (e: IOException) {
                Log.e(Constants.LOG_TAG, "Bağlantı hatası: $deviceName", e)
                statusCallback("Başarısız: $deviceName")
                currentDeviceIndex++
                tryConnectToNextDevice()
            }
        }.apply { name = "LifeLine-Connect" }.start()
    }

    // --- KURTARMA MODU ---
    @SuppressLint("MissingPermission")
    fun startRescueMode() {
        isRescueMode = true
        isBusy.set(true)
        isShuttingDown.set(false)
        
        Thread {
            try {
                statusCallback("Bağlantı bekleniyor...")
                
                synchronized(connectionLock) {
                    serverSocket = bluetoothAdapter.listenUsingInsecureRfcommWithServiceRecord(
                        Constants.SERVICE_NAME, Constants.SERVICE_UUID
                    )
                    
                    socket = serverSocket?.accept()
                    serverSocket?.close()
                    serverSocket = null
                    
                    setupConnection()
                }
                
                val deviceName = socket?.remoteDevice?.name ?: "Bilinmiyor"
                statusCallback("Bağlandı: $deviceName ✓")
                
                isConnected.set(true)
                mainHandler.post { onConnected?.invoke(true) }
                startHeartbeat()
                startListening()
                startControlListener()
                
            } catch (e: IOException) {
                if (!isShuttingDown.get()) {
                    statusCallback("Sunucu hatası: ${e.message}")
                }
                isBusy.set(false)
            }
        }.apply { name = "LifeLine-Rescue" }.start()
    }

    // --- ACİL DURUM MODU ---
    fun startEmergencyMode() {
        isRescueMode = false
        isScanning.set(true)
        isBusy.set(true)
        isShuttingDown.set(false)
        
        synchronized(discoveredDevices) {
            discoveredDevices.clear()
        }
        currentDeviceIndex = 0
        statusCallback("Kurtarıcılar aranıyor...")
    }

    private fun setupConnection() {
        inputStream = socket?.inputStream
        outputStream = socket?.outputStream
    }

    // --- PTT KONTROL ---
    fun startTalking(): Boolean {
        // Bağlı değilse veya zaten konuşuyorsa çık
        if (!isConnected.get() || !isTalking.compareAndSet(false, true)) {
            return false
        }
        
        // Dinlemeyi durdur
        stopListeningInternal()
        
        // Sinyal gönder
        sendControlSignal(Constants.SIGNAL_START_TALKING)
        
        // Ses kaydetmeye başla
        outputStream?.let { out ->
            audioCore?.startRecording(out) { error ->
                statusCallback(error)
                forceStopTalking()
            }
        }
        
        return true
    }

    fun stopTalking() {
        if (!isTalking.compareAndSet(true, false)) {
            return
        }
        
        // Ses kaydını durdur
        audioCore?.stopRecording()
        
        // Sinyal gönder
        sendControlSignal(Constants.SIGNAL_STOP_TALKING)
        
        // Dinlemeye geri dön
        if (isConnected.get()) {
            startListening()
        }
    }
    
    private fun forceStopTalking() {
        isTalking.set(false)
        audioCore?.stopRecording()
        
        mainHandler.post {
            onRemoteTalkingStateChanged?.invoke(false)
        }
        
        if (isConnected.get()) {
            startListening()
        }
    }

    private fun sendControlSignal(signal: Byte) {
        try {
            synchronized(connectionLock) {
                outputStream?.write(byteArrayOf(Constants.SIGNAL_MARKER, signal))
                outputStream?.flush()
            }
        } catch (e: Exception) {
            Log.e(Constants.LOG_TAG, "Sinyal hatası", e)
        }
    }

    // --- HEARTBEAT (ScheduledExecutorService ile) ---
    private fun startHeartbeat() {
        lastHeartbeatReceived.set(System.currentTimeMillis())
        
        heartbeatExecutor = Executors.newSingleThreadScheduledExecutor { r ->
            Thread(r, "LifeLine-Heartbeat")
        }
        
        heartbeatExecutor?.scheduleAtFixedRate({
            if (!isConnected.get()) {
                heartbeatExecutor?.shutdown()
                return@scheduleAtFixedRate
            }
            
            // Heartbeat gönder
            sendControlSignal(Constants.SIGNAL_HEARTBEAT)
            
            // Timeout kontrolü
            val elapsed = System.currentTimeMillis() - lastHeartbeatReceived.get()
            if (elapsed > Constants.HEARTBEAT_TIMEOUT) {
                Log.w(Constants.LOG_TAG, "Heartbeat timeout ($elapsed ms)")
                mainHandler.post {
                    statusCallback("Bağlantı zaman aşımı")
                    handleDisconnect()
                }
            }
        }, Constants.HEARTBEAT_INTERVAL, Constants.HEARTBEAT_INTERVAL, TimeUnit.MILLISECONDS)
    }
    
    private fun stopHeartbeat() {
        heartbeatExecutor?.shutdownNow()
        heartbeatExecutor = null
    }

    // --- SES DİNLEME ---
    private fun startListening() {
        if (!isConnected.get() || isTalking.get()) return
        
        listenThread = Thread {
            inputStream?.let { input ->
                audioCore?.startPlaying(
                    input,
                    onError = { error ->
                        mainHandler.post { statusCallback(error) }
                    },
                    onDisconnect = {
                        mainHandler.post { handleDisconnect() }
                    }
                )
            }
        }
        listenThread?.name = "LifeLine-Listen"
        listenThread?.start()
    }
    
    private fun stopListeningInternal() {
        audioCore?.stopPlaying()
        listenThread?.interrupt()
        listenThread = null
    }
    
    // --- KONTROL SİNYALLERİ DİNLEME (Ayrı Thread) ---
    private fun startControlListener() {
        controlThread = Thread {
            val buffer = ByteArray(2)
            
            while (isConnected.get() && !Thread.currentThread().isInterrupted) {
                try {
                    // Sadece kontrol sinyallerini oku
                    val available = inputStream?.available() ?: 0
                    if (available >= 2) {
                        val read = inputStream?.read(buffer, 0, 2) ?: 0
                        if (read >= 2 && buffer[0] == Constants.SIGNAL_MARKER) {
                            handleControlSignal(buffer[1])
                        }
                    }
                    Thread.sleep(50)
                } catch (e: InterruptedException) {
                    break
                } catch (e: Exception) {
                    if (isConnected.get() && !isShuttingDown.get()) {
                        Log.e(Constants.LOG_TAG, "Control listener error", e)
                    }
                    break
                }
            }
        }
        controlThread?.name = "LifeLine-Control"
        controlThread?.start()
    }
    
    private fun handleControlSignal(signal: Byte) {
        when (signal) {
            Constants.SIGNAL_START_TALKING -> {
                mainHandler.post { onRemoteTalkingStateChanged?.invoke(true) }
            }
            Constants.SIGNAL_STOP_TALKING -> {
                mainHandler.post { onRemoteTalkingStateChanged?.invoke(false) }
            }
            Constants.SIGNAL_DISCONNECT -> {
                mainHandler.post { handleDisconnect() }
            }
            Constants.SIGNAL_HEARTBEAT -> {
                lastHeartbeatReceived.set(System.currentTimeMillis())
            }
        }
    }

    // --- BAĞLANTI KAPATMA ---
    fun disconnect() {
        if (!isRescueMode) return
        sendControlSignal(Constants.SIGNAL_DISCONNECT)
        handleDisconnect()
    }

    private fun handleDisconnect() {
        // Çoklu disconnect çağrılarını önle
        if (isShuttingDown.getAndSet(true)) return
        if (!isConnected.get() && !isScanning.get() && !isBusy.get()) {
            isShuttingDown.set(false)
            return
        }
        
        Log.d(Constants.LOG_TAG, "Bağlantı kapatılıyor")
        
        isConnected.set(false)
        isTalking.set(false)
        isScanning.set(false)
        isBusy.set(false)
        
        stopHeartbeat()
        stopListeningInternal()
        
        controlThread?.interrupt()
        controlThread = null
        
        audioCore?.release()
        
        synchronized(connectionLock) {
            try { inputStream?.close() } catch (e: Exception) {}
            try { outputStream?.close() } catch (e: Exception) {}
            try { socket?.close() } catch (e: Exception) {}
            try { serverSocket?.close() } catch (e: Exception) {}
            
            inputStream = null
            outputStream = null
            socket = null
            serverSocket = null
        }
        
        synchronized(discoveredDevices) {
            discoveredDevices.clear()
        }
        
        isShuttingDown.set(false)
        onDisconnected?.invoke()
    }

    // --- PUBLIC STATE GETTERS ---
    fun isCurrentlyScanning(): Boolean = isScanning.get()
    fun stopScanning() { isScanning.set(false) }
    fun isRescueModeActive(): Boolean = isRescueMode
    fun isTalkingNow(): Boolean = isTalking.get()
    fun isBusyNow(): Boolean = isBusy.get()

    fun stop() {
        isShuttingDown.set(true)
        handleDisconnect()
    }
}
