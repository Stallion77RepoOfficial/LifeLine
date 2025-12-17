package com.stallion77.lifeline

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.media.*
import android.os.Build
import android.util.Log
import java.io.IOException

/**
 * LifeLineEngine - Bluetooth tabanlı half-duplex (walkie-talkie) ses iletişim motoru
 * 
 * Push-to-Talk (PTT) modu:
 * - Basılı tutunca konuş, bırakınca dinle
 * - Aynı anda sadece bir kişi konuşabilir
 */
class LifeLineEngine(
    private val bluetoothAdapter: BluetoothAdapter,
    private val statusCallback: (String) -> Unit
) {

    private var socket: BluetoothSocket? = null
    private var serverSocket: BluetoothServerSocket? = null
    
    @Volatile
    private var isConnected = false
    @Volatile
    private var isScanning = false
    @Volatile
    private var isTalking = false
    
    private var audioRecord: AudioRecord? = null
    private var audioTrack: AudioTrack? = null
    private var sendThread: Thread? = null
    private var receiveThread: Thread? = null

    // Callbacks
    var onConnected: ((isRescueMode: Boolean) -> Unit)? = null
    var onDisconnected: (() -> Unit)? = null
    var onRemoteTalkingStateChanged: ((isTalking: Boolean) -> Unit)? = null

    // Rescue mode mu?
    private var isRescueMode = false

    // Minimum buffer boyutu
    private val bufferSize = AudioRecord.getMinBufferSize(
        Constants.SAMPLE_RATE,
        AudioFormat.CHANNEL_IN_MONO,
        AudioFormat.ENCODING_PCM_16BIT
    ).coerceAtLeast(4096)

    // --- KURTARMA MODU (SERVER) ---
    @SuppressLint("MissingPermission")
    fun startRescueMode() {
        isRescueMode = true
        Thread {
            try {
                statusCallback("RESCUE: Making device discoverable...")
                statusCallback("RESCUE: Waiting for emergency connection...")
                
                serverSocket = bluetoothAdapter.listenUsingInsecureRfcommWithServiceRecord(
                    Constants.SERVICE_NAME, Constants.SERVICE_UUID
                )
                
                socket = serverSocket?.accept()
                serverSocket?.close()
                serverSocket = null
                
                val deviceName = socket?.remoteDevice?.name ?: "Unknown"
                statusCallback("CONNECTED to $deviceName!")
                
                isConnected = true
                onConnected?.invoke(true)
                startListening()
                
            } catch (e: IOException) {
                if (serverSocket != null) {
                    statusCallback("ERROR: Server failed - ${e.message}")
                }
            }
        }.start()
    }

    // --- ACİL DURUM MODU (CLIENT) ---
    @SuppressLint("MissingPermission")
    fun startEmergencyMode() {
        isRescueMode = false
        isScanning = true
        statusCallback("EMERGENCY: Scanning for nearby rescuers...")
        statusCallback("Please wait, discovery takes 10-12 seconds...")
    }

    @SuppressLint("MissingPermission")
    fun connectToDevice(device: BluetoothDevice) {
        isScanning = false
        Thread {
            statusCallback("Connecting to: ${device.name ?: device.address}...")
            try {
                socket = device.createInsecureRfcommSocketToServiceRecord(Constants.SERVICE_UUID)
                socket?.connect()
                statusCallback("CONNECTED!")
                
                isConnected = true
                onConnected?.invoke(false)
                startListening()
                
            } catch (e: IOException) {
                statusCallback("ERROR: Connection failed - ${e.message}")
                statusCallback("Retrying scan...")
                startEmergencyMode()
            }
        }.start()
    }

    // --- PUSH-TO-TALK ---
    fun startTalking() {
        if (!isConnected || isTalking) return
        isTalking = true
        
        // Önce dinlemeyi durdur
        stopListening()
        
        // Karşı tarafa "konuşuyorum" sinyali gönder
        sendControlSignal(Constants.SIGNAL_START_TALKING)
        
        // Ses göndermeye başla
        sendThread = Thread {
            try {
                audioRecord = AudioRecord(
                    MediaRecorder.AudioSource.MIC,
                    Constants.SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    bufferSize
                )
                
                val outputStream = socket?.outputStream
                val buffer = ByteArray(bufferSize)
                
                audioRecord?.startRecording()
                
                while (isTalking && isConnected) {
                    val read = audioRecord?.read(buffer, 0, bufferSize) ?: 0
                    if (read > 0) {
                        outputStream?.write(buffer, 0, read)
                    }
                }
            } catch (e: Exception) {
                Log.e(Constants.LOG_TAG, "Send Error", e)
            } finally {
                releaseAudioRecord()
            }
        }
        sendThread?.start()
    }

    fun stopTalking() {
        if (!isTalking) return
        isTalking = false
        
        // Karşı tarafa "konuşmayı bitirdim" sinyali gönder
        sendControlSignal(Constants.SIGNAL_STOP_TALKING)
        
        // Ses göndermeyi durdur
        sendThread?.interrupt()
        sendThread = null
        
        // Tekrar dinlemeye başla
        startListening()
    }

    private fun sendControlSignal(signal: Byte) {
        try {
            socket?.outputStream?.write(byteArrayOf(Constants.SIGNAL_MARKER, signal))
            socket?.outputStream?.flush()
        } catch (e: Exception) {
            Log.e(Constants.LOG_TAG, "Control signal error", e)
        }
    }

    // --- DİNLEME ---
    private fun startListening() {
        receiveThread = Thread {
            try {
                audioTrack = createAudioTrack()
                
                val inputStream = socket?.inputStream
                val buffer = ByteArray(bufferSize)
                
                audioTrack?.play()
                
                while (isConnected && !isTalking) {
                    val bytesRead = inputStream?.read(buffer) ?: -1
                    
                    if (bytesRead == -1) {
                        // Bağlantı koptu
                        handleDisconnect()
                        break
                    }
                    
                    if (bytesRead >= 2) {
                        // Kontrol sinyali mi?
                        if (buffer[0] == Constants.SIGNAL_MARKER) {
                            when (buffer[1]) {
                                Constants.SIGNAL_START_TALKING -> {
                                    onRemoteTalkingStateChanged?.invoke(true)
                                }
                                Constants.SIGNAL_STOP_TALKING -> {
                                    onRemoteTalkingStateChanged?.invoke(false)
                                }
                                Constants.SIGNAL_DISCONNECT -> {
                                    handleDisconnect()
                                    break
                                }
                            }
                            // Kalan veriyi (varsa) işle
                            if (bytesRead > 2) {
                                audioTrack?.write(buffer, 2, bytesRead - 2)
                            }
                        } else {
                            audioTrack?.write(buffer, 0, bytesRead)
                        }
                    } else if (bytesRead > 0) {
                        audioTrack?.write(buffer, 0, bytesRead)
                    }
                }
            } catch (e: Exception) {
                Log.e(Constants.LOG_TAG, "Receive Error", e)
                if (isConnected) {
                    handleDisconnect()
                }
            } finally {
                releaseAudioTrack()
            }
        }
        receiveThread?.start()
    }

    private fun stopListening() {
        receiveThread?.interrupt()
        receiveThread = null
        releaseAudioTrack()
    }

    // --- BAĞLANTI KAPATMA (Sadece Rescue Mode) ---
    fun disconnect() {
        if (!isRescueMode) return // Sadece Rescue mode kapatabilir
        
        sendControlSignal(Constants.SIGNAL_DISCONNECT)
        handleDisconnect()
    }

    private fun handleDisconnect() {
        isConnected = false
        isTalking = false
        isScanning = false
        
        stopListening()
        sendThread?.interrupt()
        sendThread = null
        
        try {
            socket?.close()
            serverSocket?.close()
        } catch (e: Exception) {
            Log.e(Constants.LOG_TAG, "Socket close error", e)
        }
        socket = null
        serverSocket = null
        
        onDisconnected?.invoke()
    }

    fun isCurrentlyScanning(): Boolean = isScanning
    fun stopScanning() { isScanning = false }
    fun isRescueModeActive(): Boolean = isRescueMode

    @Suppress("DEPRECATION")
    private fun createAudioTrack(): AudioTrack {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            val audioAttributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
            
            val audioFormat = AudioFormat.Builder()
                .setSampleRate(Constants.SAMPLE_RATE)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .build()
            
            AudioTrack(
                audioAttributes,
                audioFormat,
                bufferSize,
                AudioTrack.MODE_STREAM,
                AudioManager.AUDIO_SESSION_ID_GENERATE
            )
        } else {
            AudioTrack(
                AudioManager.STREAM_VOICE_CALL,
                Constants.SAMPLE_RATE,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferSize,
                AudioTrack.MODE_STREAM
            )
        }
    }

    private fun releaseAudioRecord() {
        try {
            audioRecord?.stop()
            audioRecord?.release()
        } catch (e: Exception) {
            Log.e(Constants.LOG_TAG, "AudioRecord release error", e)
        }
        audioRecord = null
    }

    private fun releaseAudioTrack() {
        try {
            audioTrack?.stop()
            audioTrack?.release()
        } catch (e: Exception) {
            Log.e(Constants.LOG_TAG, "AudioTrack release error", e)
        }
        audioTrack = null
    }

    fun stop() {
        handleDisconnect()
    }
}
