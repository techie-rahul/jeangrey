package com.example.meshtest.model

import org.json.JSONArray
import org.json.JSONObject
import java.nio.charset.StandardCharsets
import java.util.UUID

/**
 * The standard protocol payload transmitted over Google Nearby Connections
 * and uploaded by Gateway devices to the Cloud Backend.
 */
data class SosPacket(
    val messageId: String = "SOS-" + UUID.randomUUID().toString().take(6).uppercase(),
    val senderId: String,
    val timestamp: Long = System.currentTimeMillis(),
    val latitude: Double = 26.9124,
    val longitude: Double = 75.7873,
    val severity: String = "CRITICAL",
    val priority: String = "HIGH",
    val emergencyType: String? = null,
    val batteryLevel: Int = 100,
    val ttl: Int = 5,
    val hopCount: Int = 0,
    val relayPath: List<String> = listOf(senderId),
    val messageText: String = "EMERGENCY SOS: Immediate evacuation requested.",
    var gatewayId: String? = null,
    val packetType: String = "SOS"
) {
    fun toJsonString(): String {
        val json = JSONObject()
        json.put("messageId", messageId)
        json.put("senderId", senderId)
        json.put("timestamp", timestamp)
        json.put("latitude", latitude)
        json.put("longitude", longitude)
        json.put("severity", severity)
        json.put("priority", priority)
        if (emergencyType != null) {
            json.put("emergencyType", emergencyType)
        }
        json.put("batteryLevel", batteryLevel)
        json.put("ttl", ttl)
        json.put("hopCount", hopCount)
        json.put("messageText", messageText)
        if (gatewayId != null) {
            json.put("gatewayId", gatewayId)
        }
        json.put("packetType", packetType)

        val pathArray = JSONArray()
        relayPath.forEach { pathArray.put(it) }
        json.put("relayPath", pathArray)

        return json.toString()
    }

    fun toByteArray(): ByteArray {
        return toJsonString().toByteArray(StandardCharsets.UTF_8)
    }

    companion object {
        fun fromByteArray(bytes: ByteArray): SosPacket? {
            return try {
                val jsonString = String(bytes, StandardCharsets.UTF_8)
                val json = JSONObject(jsonString)

                val relayList = mutableListOf<String>()
                if (json.has("relayPath")) {
                    val pathArray = json.getJSONArray("relayPath")
                    for (i in 0 until pathArray.length()) {
                        relayList.add(pathArray.getString(i))
                    }
                }

                val priorityVal = if (json.has("priority")) {
                    json.getString("priority")
                } else if (json.has("severity") && json.getString("severity") == "CRITICAL") {
                    "HIGH"
                } else {
                    "MEDIUM"
                }

                SosPacket(
                    messageId = json.optString("messageId", "UNKNOWN_ID"),
                    senderId = json.optString("senderId", "UNKNOWN_SENDER"),
                    timestamp = json.optLong("timestamp", System.currentTimeMillis()),
                    latitude = json.optDouble("latitude", 26.9124),
                    longitude = json.optDouble("longitude", 75.7873),
                    severity = json.optString("severity", "CRITICAL"),
                    priority = priorityVal,
                    emergencyType = if (json.has("emergencyType")) json.getString("emergencyType") else null,
                    batteryLevel = json.optInt("batteryLevel", 100),
                    ttl = json.optInt("ttl", 0),
                    hopCount = json.optInt("hopCount", 0),
                    relayPath = if (relayList.isNotEmpty()) relayList else listOf(json.optString("senderId", "UNKNOWN")),
                    messageText = json.optString("messageText", "EMERGENCY SOS"),
                    gatewayId = if (json.has("gatewayId")) json.getString("gatewayId") else null,
                    packetType = json.optString("packetType", "SOS")
                )
            } catch (e: Exception) {
                null
            }
        }
    }
}