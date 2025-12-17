package com.stallion77.lifeline

import java.util.UUID

object Constants {
    // Tüm LifeLine cihazlarının birbirini tanıması için ortak UUID
    val SERVICE_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB") // Standart SPP UUID
    const val SERVICE_NAME = "LifeLineSecureChannel"
    
    // Ses Ayarları (Telsiz Kalitesi)
    const val SAMPLE_RATE = 8000
    const val LOG_TAG = "LifeLineEngine"
    
    // Kontrol Sinyalleri (PTT Mode)
    const val SIGNAL_MARKER: Byte = 0x7F      // Sinyal başlangıcı
    const val SIGNAL_START_TALKING: Byte = 0x01
    const val SIGNAL_STOP_TALKING: Byte = 0x02
    const val SIGNAL_DISCONNECT: Byte = 0x03
}
