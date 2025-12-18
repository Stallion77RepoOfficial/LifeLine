package com.stallion77.lifeline

import android.media.*
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import android.os.Build
import android.util.Log
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.atomic.AtomicBoolean

/**
 * AudioCore - Şifreli yüksek kaliteli ses işleme modülü
 * 
 * Özellikler:
 * - AES-GCM uygulama seviyesi şifreleme
 * - Optimize edilmiş buffer yönetimi
 * - Otomatik ses seviyesi kontrolü (AGC)
 * - Gürültü azaltma
 * - Ses amplifikasyonu
 */
class AudioCore(private val audioManager: AudioManager?) {
    
    companion object {
        private const val TAG = "AudioCore"
        
        // Ses ayarları - Optimize edilmiş değerler
        const val SAMPLE_RATE = 16000  // 16kHz daha iyi kalite
        const val CHANNEL_CONFIG_IN = AudioFormat.CHANNEL_IN_MONO
        const val CHANNEL_CONFIG_OUT = AudioFormat.CHANNEL_OUT_MONO
        const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
        
        // Amplifikasyon faktörü (1.0 = normal, 2.0 = 2x ses)
        const val AMPLIFICATION_FACTOR = 2.5f
    }
    
    private var audioRecord: AudioRecord? = null
    private var audioTrack: AudioTrack? = null
    private var agc: AutomaticGainControl? = null
    private var noiseSuppressor: NoiseSuppressor? = null
    
    // Şifreleme yöneticisi
    private val cryptoManager = CryptoManager()
    
    private val bufferSize: Int by lazy {
        val minSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG_IN, AUDIO_FORMAT)
        // Şifreleme overhead'i için ek alan + stabilite için 2x
        (minSize * 2).coerceAtLeast(Constants.AUDIO_BUFFER_SIZE)
    }
    
    // Thread-safe flags
    private val isRecording = AtomicBoolean(false)
    private val isPlaying = AtomicBoolean(false)
    
    private var recordThread: Thread? = null
    private var playThread: Thread? = null
    
    init {
        // Şifreleme self-test
        if (!cryptoManager.selfTest()) {
            Log.e(TAG, "Şifreleme self-test BAŞARISIZ!")
        } else {
            Log.d(TAG, "Şifreleme self-test başarılı ✓")
        }
    }
    
    /**
     * Ses kaydetmeyi başlat ve şifreleyerek stream'e yaz
     */
    fun startRecording(outputStream: OutputStream, onError: (String) -> Unit): Boolean {
        if (!isRecording.compareAndSet(false, true)) return false
        
        try {
            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                SAMPLE_RATE,
                CHANNEL_CONFIG_IN,
                AUDIO_FORMAT,
                bufferSize
            )
            
            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                isRecording.set(false)
                onError("Mikrofon başlatılamadı")
                return false
            }
            
            // Ses efektlerini etkinleştir
            enableAudioEffects(audioRecord!!.audioSessionId)
            
            // Ses modunu ayarla
            audioManager?.mode = AudioManager.MODE_IN_COMMUNICATION
            
            audioRecord?.startRecording()
            
            recordThread = Thread {
                val buffer = ByteArray(bufferSize)
                
                while (isRecording.get()) {
                    try {
                        val bytesRead = audioRecord?.read(buffer, 0, bufferSize) ?: 0
                        if (bytesRead > 0) {
                            // 1. Ses amplifikasyonu
                            val amplifiedBuffer = amplifyAudio(buffer, bytesRead)
                            
                            // 2. Sadece okunan kısmı al
                            val audioData = amplifiedBuffer.copyOf(bytesRead)
                            
                            // 3. Şifrele
                            val encryptedData = cryptoManager.encrypt(audioData)
                            
                            if (encryptedData != null) {
                                // 4. Boyut bilgisi + şifreli veri gönder
                                synchronized(outputStream) {
                                    // Paket boyutu (4 bytes)
                                    val sizeBytes = intToBytes(encryptedData.size)
                                    outputStream.write(sizeBytes)
                                    outputStream.write(encryptedData)
                                    outputStream.flush()
                                }
                            }
                        }
                    } catch (e: Exception) {
                        if (isRecording.get()) {
                            Log.e(TAG, "Recording error", e)
                            onError("Kayıt hatası: ${e.message}")
                        }
                        break
                    }
                }
            }
            recordThread?.name = "AudioCore-Record"
            recordThread?.start()
            
            return true
            
        } catch (e: Exception) {
            isRecording.set(false)
            Log.e(TAG, "Failed to start recording", e)
            onError("Mikrofon hatası: ${e.message}")
            return false
        }
    }
    
    /**
     * Ses kaydetmeyi durdur
     */
    fun stopRecording() {
        if (!isRecording.compareAndSet(true, false)) return
        
        recordThread?.interrupt()
        recordThread = null
        
        try {
            audioRecord?.stop()
        } catch (e: Exception) {
            Log.w(TAG, "Error stopping recording", e)
        }
        
        try {
            audioRecord?.release()
        } catch (e: Exception) {
            Log.w(TAG, "Error releasing AudioRecord", e)
        }
        
        audioRecord = null
        releaseAudioEffects()
    }
    
    /**
     * Stream'den şifreli ses oku ve çözerek çal
     */
    fun startPlaying(inputStream: InputStream, onError: (String) -> Unit, onDisconnect: () -> Unit): Boolean {
        if (!isPlaying.compareAndSet(false, true)) return false
        
        try {
            audioTrack = createAudioTrack()
            
            if (audioTrack?.state != AudioTrack.STATE_INITIALIZED) {
                isPlaying.set(false)
                onError("Hoparlör başlatılamadı")
                return false
            }
            
            // Maksimum ses seviyesi
            setMaxVolume()
            
            audioTrack?.play()
            
            playThread = Thread {
                val sizeBuffer = ByteArray(4)
                
                while (isPlaying.get()) {
                    try {
                        // 1. Paket boyutunu oku (4 bytes)
                        var totalRead = 0
                        while (totalRead < 4 && isPlaying.get()) {
                            val read = inputStream.read(sizeBuffer, totalRead, 4 - totalRead)
                            if (read == -1) {
                                Log.d(TAG, "Connection closed by remote")
                                onDisconnect()
                                return@Thread
                            }
                            totalRead += read
                        }
                        
                        if (!isPlaying.get()) break
                        
                        val packetSize = bytesToInt(sizeBuffer)
                        
                        // Geçersiz boyut kontrolü
                        if (packetSize <= 0 || packetSize > bufferSize * 2) {
                            Log.w(TAG, "Geçersiz paket boyutu: $packetSize")
                            continue
                        }
                        
                        // 2. Şifreli veriyi oku
                        val encryptedData = ByteArray(packetSize)
                        totalRead = 0
                        while (totalRead < packetSize && isPlaying.get()) {
                            val read = inputStream.read(encryptedData, totalRead, packetSize - totalRead)
                            if (read == -1) {
                                Log.d(TAG, "Connection closed during data read")
                                onDisconnect()
                                return@Thread
                            }
                            totalRead += read
                        }
                        
                        if (!isPlaying.get()) break
                        
                        // 3. Şifre çöz
                        val decryptedData = cryptoManager.decrypt(encryptedData)
                        
                        if (decryptedData != null) {
                            // 4. Amplifikasyon uygula ve çal
                            val amplifiedData = amplifyAudio(decryptedData, decryptedData.size)
                            audioTrack?.write(amplifiedData, 0, decryptedData.size)
                        } else {
                            Log.w(TAG, "Şifre çözme başarısız")
                        }
                        
                    } catch (e: Exception) {
                        if (isPlaying.get()) {
                            Log.e(TAG, "Playback error", e)
                            onDisconnect()
                        }
                        break
                    }
                }
            }
            playThread?.name = "AudioCore-Play"
            playThread?.start()
            
            return true
            
        } catch (e: Exception) {
            isPlaying.set(false)
            Log.e(TAG, "Failed to start playing", e)
            onError("Hoparlör hatası: ${e.message}")
            return false
        }
    }
    
    /**
     * Ses çalmayı durdur
     */
    fun stopPlaying() {
        if (!isPlaying.compareAndSet(true, false)) return
        
        playThread?.interrupt()
        playThread = null
        
        try {
            audioTrack?.stop()
        } catch (e: Exception) {
            Log.w(TAG, "Error stopping playback", e)
        }
        
        try {
            audioTrack?.release()
        } catch (e: Exception) {
            Log.w(TAG, "Error releasing AudioTrack", e)
        }
        
        audioTrack = null
    }
    
    /**
     * Tüm kaynakları temizle
     */
    fun release() {
        stopRecording()
        stopPlaying()
        audioManager?.mode = AudioManager.MODE_NORMAL
    }
    
    private fun enableAudioEffects(sessionId: Int) {
        try {
            if (AutomaticGainControl.isAvailable()) {
                agc = AutomaticGainControl.create(sessionId)
                agc?.enabled = true
                Log.d(TAG, "AGC enabled")
            }
        } catch (e: Exception) {
            Log.w(TAG, "AGC not available", e)
        }
        
        try {
            if (NoiseSuppressor.isAvailable()) {
                noiseSuppressor = NoiseSuppressor.create(sessionId)
                noiseSuppressor?.enabled = true
                Log.d(TAG, "Noise Suppressor enabled")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Noise Suppressor not available", e)
        }
    }
    
    private fun releaseAudioEffects() {
        try {
            agc?.release()
        } catch (e: Exception) { }
        agc = null
        
        try {
            noiseSuppressor?.release()
        } catch (e: Exception) { }
        noiseSuppressor = null
    }
    
    /**
     * Ses amplifikasyonu - sesi yükseltir
     */
    private fun amplifyAudio(buffer: ByteArray, length: Int): ByteArray {
        val amplified = buffer.copyOf(length)
        
        for (i in 0 until length step 2) {
            if (i + 1 >= length) break
            
            // 16-bit PCM sample'ı oku (little endian)
            var sample = ((amplified[i + 1].toInt() shl 8) or (amplified[i].toInt() and 0xFF)).toShort()
            
            // Amplifikasyon uygula
            var amplifiedSample = (sample * AMPLIFICATION_FACTOR).toInt()
            
            // Clipping önleme
            amplifiedSample = amplifiedSample.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
            
            // Geri yaz
            amplified[i] = (amplifiedSample and 0xFF).toByte()
            amplified[i + 1] = ((amplifiedSample shr 8) and 0xFF).toByte()
        }
        
        return amplified
    }
    
    private fun setMaxVolume() {
        audioManager?.let { am ->
            val maxVol = am.getStreamMaxVolume(AudioManager.STREAM_VOICE_CALL)
            am.setStreamVolume(AudioManager.STREAM_VOICE_CALL, maxVol, 0)
            
            // Music stream'i de ayarla (bazı cihazlarda gerekli)
            val maxMusicVol = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            am.setStreamVolume(AudioManager.STREAM_MUSIC, maxMusicVol, 0)
        }
    }
    
    @Suppress("DEPRECATION")
    private fun createAudioTrack(): AudioTrack {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            val audioAttributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .setFlags(AudioAttributes.FLAG_AUDIBILITY_ENFORCED)
                .build()
            
            val audioFormat = AudioFormat.Builder()
                .setSampleRate(SAMPLE_RATE)
                .setChannelMask(CHANNEL_CONFIG_OUT)
                .setEncoding(AUDIO_FORMAT)
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
                SAMPLE_RATE,
                CHANNEL_CONFIG_OUT,
                AUDIO_FORMAT,
                bufferSize,
                AudioTrack.MODE_STREAM
            )
        }
    }
    
    // Utility: Int to 4 bytes (big endian)
    private fun intToBytes(value: Int): ByteArray {
        return byteArrayOf(
            ((value shr 24) and 0xFF).toByte(),
            ((value shr 16) and 0xFF).toByte(),
            ((value shr 8) and 0xFF).toByte(),
            (value and 0xFF).toByte()
        )
    }
    
    // Utility: 4 bytes to Int (big endian)
    private fun bytesToInt(bytes: ByteArray): Int {
        return ((bytes[0].toInt() and 0xFF) shl 24) or
               ((bytes[1].toInt() and 0xFF) shl 16) or
               ((bytes[2].toInt() and 0xFF) shl 8) or
               (bytes[3].toInt() and 0xFF)
    }
}
