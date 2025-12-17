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
 * LifeLineEngine - Bluetooth tabanlı full-duplex ses iletişim motoru
 * 
 * İki mod destekler:
 * - Rescue Mode (Server): Cihazı görünür yapar, bağlantı bekler ve ses aktarımı başlatır
 * - Emergency Mode (Client): Yakındaki LifeLine cihazlarını tarar ve ses iletir
 */
class LifeLineEngine(
    private val bluetoothAdapter: BluetoothAdapter,
    private val statusCallback: (String) -> Unit
) {

    private var socket: BluetoothSocket? = null
    private var serverSocket: BluetoothServerSocket? = null
    @Volatile
    private var isRunning = false
    @Volatile
    private var isScanning = false
    private var audioRecord: AudioRecord? = null
    private var audioTrack: AudioTrack? = null

    // Discovery callback - MainActivity tarafından set edilecek
    var onDeviceFound: ((BluetoothDevice) -> Unit)? = null

    // Minimum buffer boyutu hesaplama
    private val bufferSize = AudioRecord.getMinBufferSize(
        Constants.SAMPLE_RATE,
        AudioFormat.CHANNEL_IN_MONO,
        AudioFormat.ENCODING_PCM_16BIT
    ).coerceAtLeast(4096) // Minimum 4KB buffer

    // --- KURTARMA MODU (SERVER) ---
    // Rescue Mode: Cihazı görünür yapar ve bağlantı bekler
    @SuppressLint("MissingPermission")
    fun startRescueMode() {
        Thread {
            try {
                statusCallback("RESCUE: Making device discoverable...")
                statusCallback("RESCUE: Waiting for emergency connection...")
                
                // Insecure bağlantı - eşleştirme gerektirmez
                serverSocket = bluetoothAdapter.listenUsingInsecureRfcommWithServiceRecord(
                    Constants.SERVICE_NAME, Constants.SERVICE_UUID
                )
                
                socket = serverSocket?.accept() // Bağlantı gelene kadar bekler (Bloklar)
                serverSocket?.close()
                serverSocket = null
                
                val deviceName = socket?.remoteDevice?.name ?: "Unknown"
                statusCallback("CONNECTED to $deviceName! Starting Voice...")
                startVoiceStream()
            } catch (e: IOException) {
                if (isRunning || serverSocket != null) {
                    statusCallback("ERROR: Server failed - ${e.message}")
                }
            }
        }.start()
    }

    // --- ACİL DURUM MODU (CLIENT) ---
    // Emergency Mode: Yakındaki cihazları tarar ve bağlanır
    @SuppressLint("MissingPermission")
    fun startEmergencyMode() {
        isScanning = true
        statusCallback("EMERGENCY: Scanning for nearby rescuers...")
        statusCallback("Please wait, discovery takes 10-12 seconds...")
        
        // Discovery MainActivity tarafından yönetilecek
        // Cihaz bulunduğunda connectToDevice çağrılacak
    }

    // Bulunan cihaza bağlan
    @SuppressLint("MissingPermission")
    fun connectToDevice(device: BluetoothDevice) {
        isScanning = false
        Thread {
            statusCallback("Connecting to: ${device.name ?: device.address}...")
            try {
                // Insecure bağlantı - eşleştirme gerektirmez
                socket = device.createInsecureRfcommSocketToServiceRecord(Constants.SERVICE_UUID)
                socket?.connect()
                statusCallback("CONNECTED! Starting Voice Stream...")
                startVoiceStream()
            } catch (e: IOException) {
                statusCallback("ERROR: Connection failed - ${e.message}")
                // Tekrar tara
                statusCallback("Retrying scan...")
                startEmergencyMode()
            }
        }.start()
    }

    // Cihaz bulunduğunda çağrılır (MainActivity'den)
    fun onDiscoveredDevice(device: BluetoothDevice) {
        if (!isScanning) return
        onDeviceFound?.invoke(device)
    }

    // Tarama durumunu kontrol et
    fun isCurrentlyScanning(): Boolean = isScanning

    // Taramayı durdur
    fun stopScanning() {
        isScanning = false
    }

    // --- SES İLETİMİ (FULL DUPLEX) ---
    private fun startVoiceStream() {
        isRunning = true

        // 1. Konuşma Thread'i (Gönderici)
        Thread {
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
                statusCallback("🎤 Microphone Active - Speak now!")
                
                while (isRunning) {
                    val read = audioRecord?.read(buffer, 0, bufferSize) ?: 0
                    if (read > 0) {
                        outputStream?.write(buffer, 0, read)
                    }
                }
            } catch (e: Exception) {
                Log.e(Constants.LOG_TAG, "Send Error", e)
                if (isRunning) {
                    statusCallback("Microphone Error: ${e.message}")
                }
            } finally {
                releaseAudioRecord()
            }
        }.start()

        // 2. Dinleme Thread'i (Alıcı)
        Thread {
            try {
                audioTrack = createAudioTrack()
                
                val inputStream = socket?.inputStream
                val buffer = ByteArray(bufferSize)
                
                audioTrack?.play()
                statusCallback("🔊 Speaker Active - Listening...")
                
                while (isRunning) {
                    val bytesRead = inputStream?.read(buffer) ?: -1
                    if (bytesRead > 0) {
                        audioTrack?.write(buffer, 0, bytesRead)
                    } else if (bytesRead == -1) {
                        break // Bağlantı koptu
                    }
                }
            } catch (e: Exception) {
                Log.e(Constants.LOG_TAG, "Receive Error", e)
                if (isRunning) {
                    statusCallback("Speaker Error: ${e.message}")
                }
            } finally {
                releaseAudioTrack()
                statusCallback("⚠️ Connection Lost")
            }
        }.start()
    }

    /**
     * AudioTrack oluşturur - deprecated constructor yerine yeni API kullanır
     */
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
            // Eski API fallback (Android 5.0 öncesi)
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
        isRunning = false
        isScanning = false
        try {
            serverSocket?.close()
            socket?.close()
        } catch (e: Exception) {
            Log.e(Constants.LOG_TAG, "Socket close error", e)
        }
        serverSocket = null
        socket = null
    }
}
