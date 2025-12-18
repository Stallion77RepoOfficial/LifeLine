package com.stallion77.lifeline

import android.util.Log
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * CryptoManager - Uygulama seviyesi AES-GCM şifreleme
 * 
 * Özellikler:
 * - AES-256-GCM şifreleme (AEAD - Authenticated Encryption)
 * - Her paket için benzersiz IV
 * - Düşük gecikme için optimize edilmiş
 * - Pairing gerektirmez (Insecure Bluetooth + App-level encryption)
 */
class CryptoManager {
    
    companion object {
        private const val TAG = "CryptoManager"
        
        // AES-GCM ayarları
        private const val ALGORITHM = "AES"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val KEY_SIZE = 256 // bits
        private const val IV_SIZE = 12   // bytes (GCM standard)
        private const val TAG_SIZE = 128 // bits (authentication tag)
        
        // Paylaşılan anahtar (hem client hem server aynı uygulamayı kullandığı için)
        // Gerçek üretimde bu anahtar güvenli bir şekilde değiştirilmeli
        private val SHARED_KEY_BYTES = byteArrayOf(
            0x4C, 0x69, 0x66, 0x65, 0x4C, 0x69, 0x6E, 0x65,  // "LifeLine"
            0x53, 0x65, 0x63, 0x75, 0x72, 0x65, 0x4B, 0x65,  // "SecureKe"
            0x79, 0x32, 0x30, 0x32, 0x34, 0x21, 0x40, 0x23,  // "y2024!@#"
            0x24, 0x25, 0x5E, 0x26, 0x2A, 0x28, 0x29, 0x5F   // "$%^&*()_"
        )
    }
    
    private val secretKey: SecretKey = SecretKeySpec(SHARED_KEY_BYTES, ALGORITHM)
    private val secureRandom = SecureRandom()
    
    // Cipher instance reuse for performance
    private val encryptCipher: Cipher by lazy { 
        Cipher.getInstance(TRANSFORMATION) 
    }
    private val decryptCipher: Cipher by lazy { 
        Cipher.getInstance(TRANSFORMATION) 
    }
    
    /**
     * Veriyi şifrele
     * Çıktı formatı: [IV (12 bytes)] + [Ciphertext + Auth Tag]
     */
    @Synchronized
    fun encrypt(plainData: ByteArray): ByteArray? {
        return try {
            // Her şifreleme için yeni IV oluştur
            val iv = ByteArray(IV_SIZE)
            secureRandom.nextBytes(iv)
            
            val gcmSpec = GCMParameterSpec(TAG_SIZE, iv)
            encryptCipher.init(Cipher.ENCRYPT_MODE, secretKey, gcmSpec)
            
            val cipherText = encryptCipher.doFinal(plainData)
            
            // IV + Ciphertext birleştir
            ByteArray(IV_SIZE + cipherText.size).apply {
                System.arraycopy(iv, 0, this, 0, IV_SIZE)
                System.arraycopy(cipherText, 0, this, IV_SIZE, cipherText.size)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Şifreleme hatası", e)
            null
        }
    }
    
    /**
     * Şifreli veriyi çöz
     * Giriş formatı: [IV (12 bytes)] + [Ciphertext + Auth Tag]
     */
    @Synchronized
    fun decrypt(encryptedData: ByteArray): ByteArray? {
        if (encryptedData.size < IV_SIZE + TAG_SIZE / 8) {
            Log.w(TAG, "Veri çok kısa: ${encryptedData.size} bytes")
            return null
        }
        
        return try {
            // IV'yi ayıkla
            val iv = encryptedData.copyOfRange(0, IV_SIZE)
            val cipherText = encryptedData.copyOfRange(IV_SIZE, encryptedData.size)
            
            val gcmSpec = GCMParameterSpec(TAG_SIZE, iv)
            decryptCipher.init(Cipher.DECRYPT_MODE, secretKey, gcmSpec)
            
            decryptCipher.doFinal(cipherText)
        } catch (e: Exception) {
            Log.e(TAG, "Şifre çözme hatası", e)
            null
        }
    }
    
    /**
     * Şifreleme overhead hesapla
     * IV (12) + Auth Tag (16) = 28 bytes
     */
    fun getOverhead(): Int = IV_SIZE + TAG_SIZE / 8
    
    /**
     * Test amaçlı - şifrelemenin çalıştığını doğrula
     */
    fun selfTest(): Boolean {
        return try {
            val testData = "LifeLine Test 123".toByteArray()
            val encrypted = encrypt(testData) ?: return false
            val decrypted = decrypt(encrypted) ?: return false
            testData.contentEquals(decrypted)
        } catch (e: Exception) {
            Log.e(TAG, "Self test hatası", e)
            false
        }
    }
}
