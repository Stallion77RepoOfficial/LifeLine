package com.stallion77.lifeline

import java.util.UUID

object Constants {
    // Bluetooth Ayarları
    val SERVICE_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB") // Standart SPP UUID
    const val SERVICE_NAME = "LifeLineSecureChannel"
    const val LOG_TAG = "LifeLineEngine"
    
    // Socket Ayarları
    const val SOCKET_CONNECT_TIMEOUT = 15000  // 15 saniye bağlantı zaman aşımı
    const val SOCKET_READ_TIMEOUT = 5000      // 5 saniye okuma zaman aşımı
    
    // Kontrol Sinyalleri (0x7F prefix ile)
    const val SIGNAL_MARKER: Byte = 0x7F
    const val SIGNAL_START_TALKING: Byte = 0x01
    const val SIGNAL_STOP_TALKING: Byte = 0x02
    const val SIGNAL_DISCONNECT: Byte = 0x03
    const val SIGNAL_HEARTBEAT: Byte = 0x04
    const val SIGNAL_ENCRYPTED: Byte = 0x10   // Şifreli veri paketi
    
    // Heartbeat Ayarları
    const val HEARTBEAT_INTERVAL = 3000L      // 3 saniye
    const val HEARTBEAT_TIMEOUT = 10000L      // 10 saniye
    
    // Buffer Ayarları
    const val AUDIO_BUFFER_SIZE = 8192        // 8KB ses buffer
    const val CONTROL_BUFFER_SIZE = 64        // Kontrol sinyalleri için
}
