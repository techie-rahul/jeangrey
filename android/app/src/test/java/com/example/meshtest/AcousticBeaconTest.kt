package com.example.meshtest

import com.example.meshtest.acoustic.AcousticBeacon
import com.example.meshtest.model.SosPacket
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.abs

class AcousticBeaconTest {

    @Test
    fun testGeohashEncodeDecodePrecision() {
        val testCoords = listOf(
            Pair(26.9124, 75.7873),   // Jaipur
            Pair(37.7749, -122.4194), // San Francisco
            Pair(51.5074, -0.1278),   // London
            Pair(0.0, 0.0),           // Equator / Prime Meridian
            Pair(-33.8688, 151.2093)  // Sydney
        )

        for ((lat, lng) in testCoords) {
            val hash = AcousticBeacon.encodeGeohash(lat, lng, precision = 6)
            assertEquals("Geohash length should be 6", 6, hash.length)

            val decoded = AcousticBeacon.decodeGeohash(hash)
            assertNotNull("Decoded geohash should not be null", decoded)

            // Precision for 6-char geohash is approx ±0.005 deg (~150-500m)
            assertTrue("Decoded latitude within tolerance", abs(decoded!!.first - lat) < 0.02)
            assertTrue("Decoded longitude within tolerance", abs(decoded.second - lng) < 0.02)
        }
    }

    @Test
    fun testBeaconFromSosPacketAndParseRoundTrip() {
        val originalPacket = SosPacket(
            messageId = "SOS-9A4B2C",
            senderId = "SAMSUNG-SM-S936B",
            latitude = 26.9124,
            longitude = 75.7873,
            severity = "CRITICAL",
            batteryLevel = 12,
            ttl = 5,
            hopCount = 0,
            relayPath = listOf("SAMSUNG-SM-S936B"),
            messageText = "EMERGENCY: Immediate Evacuation & Medical Support Requested!"
        )

        val beaconStr = AcousticBeacon.fromSosPacket(originalPacket)
        assertNotNull(beaconStr)
        assertTrue(beaconStr.startsWith("9A4B2C|2|"))

        val payload = AcousticBeacon.parseBeacon(beaconStr)
        assertNotNull(payload)
        assertEquals("9A4B2C", payload!!.id)
        assertEquals(2, payload.severityCode)
        assertTrue("Geohash should not be empty", payload.geohash.isNotEmpty())
        assertTrue("Latitude close to original", abs(payload.latitude - 26.9124) < 0.02)
        assertTrue("Longitude close to original", abs(payload.longitude - 75.7873) < 0.02)
    }

    @Test
    fun testToPartialSosPacketReconstruction() {
        val originalPacket = SosPacket(
            messageId = "SOS-ABC123",
            senderId = "XIAOMI-22041219PI",
            latitude = 26.9124,
            longitude = 75.7873,
            severity = "CRITICAL",
            batteryLevel = 10,
            ttl = 5,
            hopCount = 0,
            relayPath = listOf("XIAOMI-22041219PI"),
            messageText = "EMERGENCY"
        )

        val beaconStr = AcousticBeacon.fromSosPacket(originalPacket)
        val payload = AcousticBeacon.parseBeacon(beaconStr)!!
        val gatewayPacket = AcousticBeacon.toPartialSosPacket(payload, "GATEWAY-NODE-01")

        assertEquals("ACOUSTIC-ABC123", gatewayPacket.messageId)
        assertEquals("CRITICAL", gatewayPacket.severity)
        assertEquals(1, gatewayPacket.hopCount)
        assertEquals(1, gatewayPacket.ttl)
        assertEquals("GATEWAY-NODE-01", gatewayPacket.gatewayId)
        assertEquals(listOf("ACOUSTIC-BEACON", "GATEWAY-NODE-01"), gatewayPacket.relayPath)
        assertTrue(gatewayPacket.messageText.contains("Acoustic fallback beacon"))
    }

    @Test
    fun testInvalidBeaconHandling() {
        assertNull(AcousticBeacon.parseBeacon(""))
        assertNull(AcousticBeacon.parseBeacon("INVALID"))
        assertNull(AcousticBeacon.parseBeacon("A|B"))
        assertNull(AcousticBeacon.parseBeacon("||"))
        assertNull(AcousticBeacon.parseBeacon("123456|1|!@#$%^"))
    }
}