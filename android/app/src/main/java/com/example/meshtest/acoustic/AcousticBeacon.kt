package com.example.meshtest.acoustic

import com.example.meshtest.model.SosPacket

/**
 * AcousticPayload represents a minimal emergency beacon transmitted over sound.
 * Format: "{6-char messageId}|{severity 0/1/2}|{6-char geohash}"
 * Example: "SOS4F2|1|9q8yyk"
 */
data class AcousticPayload(
    val id: String,
    val severityCode: Int,
    val geohash: String,
    val latitude: Double,
    val longitude: Double
)

object AcousticBeacon {
    private const val BASE32 = "0123456789bcdefghjkmnpqrstuvwxyz"
    private const val GEOHASH_PRECISION = 6 // ~150m precision (approx ±0.001 deg)

    /**
     * Converts a full SosPacket into a compact acoustic beacon string.
     */
    fun fromSosPacket(packet: SosPacket): String {
        val cleanId = packet.messageId.replace("SOS-", "").take(6).padEnd(6, '0').uppercase()
        val sevCode = when (packet.severity.uppercase()) {
            "CRITICAL" -> 2
            "HIGH", "MEDIUM", "MODERATE" -> 1
            else -> 0
        }
        val geohash = encodeGeohash(packet.latitude, packet.longitude, GEOHASH_PRECISION)
        return "$cleanId|$sevCode|$geohash"
    }

    /**
     * Parses an incoming raw beacon string into an AcousticPayload.
     * Returns null if formatting or checksum/geohash validation fails.
     */
    fun parseBeacon(raw: String): AcousticPayload? {
        val trimmed = raw.trim()
        val parts = trimmed.split("|")
        if (parts.size != 3) return null

        val id = parts[0].trim().take(6).uppercase()
        val sevCode = parts[1].trim().toIntOrNull() ?: 0
        val geohash = parts[2].trim().lowercase()

        if (id.isEmpty() || geohash.length < 4) return null

        val (lat, lng) = decodeGeohash(geohash) ?: return null

        return AcousticPayload(
            id = id,
            severityCode = sevCode.coerceIn(0, 2),
            geohash = geohash,
            latitude = lat,
            longitude = lng
        )
    }

    /**
     * Converts an AcousticPayload into a partial SosPacket suitable for gateway cloud upload.
     */
    fun toPartialSosPacket(payload: AcousticPayload, localDeviceName: String): SosPacket {
        val severityStr = when (payload.severityCode) {
            2 -> "CRITICAL"
            1 -> "MEDIUM"
            else -> "LOW"
        }

        return SosPacket(
            messageId = "ACOUSTIC-${payload.id}",
            senderId = "ACOUSTIC-${payload.id}",
            timestamp = System.currentTimeMillis(),
            latitude = payload.latitude,
            longitude = payload.longitude,
            severity = severityStr,
            batteryLevel = 10, // Indication of low-battery device
            ttl = 1,
            hopCount = 1,
            relayPath = listOf("ACOUSTIC-BEACON", localDeviceName),
            gatewayId = localDeviceName,
            messageText = "Acoustic fallback beacon — limited data, device may be low battery / BLE unavailable"
        )
    }

    /**
     * Encodes latitude and longitude into a standard Base32 Geohash.
     */
    fun encodeGeohash(latitude: Double, longitude: Double, precision: Int = GEOHASH_PRECISION): String {
        var latMin = -90.0
        var latMax = 90.0
        var lonMin = -180.0
        var lonMax = 180.0

        val hash = StringBuilder()
        var isEven = true
        var bit = 0
        var ch = 0

        while (hash.length < precision) {
            if (isEven) {
                val mid = (lonMin + lonMax) / 2
                if (longitude >= mid) {
                    ch = ch or (1 shl (4 - bit))
                    lonMin = mid
                } else {
                    lonMax = mid
                }
            } else {
                val mid = (latMin + latMax) / 2
                if (latitude >= mid) {
                    ch = ch or (1 shl (4 - bit))
                    latMin = mid
                } else {
                    latMax = mid
                }
            }

            isEven = !isEven
            if (bit < 4) {
                bit++
            } else {
                hash.append(BASE32[ch])
                bit = 0
                ch = 0
            }
        }

        return hash.toString()
    }

    /**
     * Decodes a Base32 Geohash string into [latitude, longitude] center coordinates.
     */
    fun decodeGeohash(geohash: String): Pair<Double, Double>? {
        if (geohash.isEmpty()) return null

        var latMin = -90.0
        var latMax = 90.0
        var lonMin = -180.0
        var lonMax = 180.0

        var isEven = true

        for (c in geohash.lowercase()) {
            val cd = BASE32.indexOf(c)
            if (cd == -1) return null

            for (j in 4 downTo 0) {
                val mask = 1 shl j
                if (isEven) {
                    val mid = (lonMin + lonMax) / 2
                    if ((cd and mask) != 0) {
                        lonMin = mid
                    } else {
                        lonMax = mid
                    }
                } else {
                    val mid = (latMin + latMax) / 2
                    if ((cd and mask) != 0) {
                        latMin = mid
                    } else {
                        latMax = mid
                    }
                }
                isEven = !isEven
            }
        }

        val lat = (latMin + latMax) / 2
        val lon = (lonMin + lonMax) / 2
        return Pair(lat, lon)
    }
}