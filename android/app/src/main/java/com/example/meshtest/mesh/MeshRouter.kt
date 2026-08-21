package com.example.meshtest.mesh

import android.util.Log
import com.example.meshtest.model.SosPacket

sealed class MeshDecision {
    data class DroppedDuplicate(val messageId: String) : MeshDecision()
    data class DroppedTtlExpired(val messageId: String) : MeshDecision()
    data class ProcessAndRelay(
        val originalPacket: SosPacket,
        val packetToForward: SosPacket?,
        val shouldUploadToGateway: Boolean
    ) : MeshDecision()
    object InvalidPayload : MeshDecision()
}

class MeshRouter(private val localDeviceName: String) {
    private val TAG = "MeshRouter"

    // Deduplication Cache to prevent broadcast storms / infinite loops
    private val seenMessageIds = mutableSetOf<String>()

    /**
     * Process an incoming raw byte payload from an endpoint.
     */
    fun onReceivePayload(
        bytes: ByteArray,
        fromEndpointId: String,
        isInternetAvailable: Boolean
    ): Pair<SosPacket?, MeshDecision> {
        val packet = SosPacket.fromByteArray(bytes) ?: return Pair(null, MeshDecision.InvalidPayload)

        Log.d(TAG, "Processing packet ${packet.messageId} from $fromEndpointId (TTL: ${packet.ttl})")

        // 1. Deduplication check
        synchronized(seenMessageIds) {
            if (seenMessageIds.contains(packet.messageId)) {
                Log.d(TAG, "Duplicate message ${packet.messageId} - Dropping to prevent loop.")
                return Pair(packet, MeshDecision.DroppedDuplicate(packet.messageId))
            }
            seenMessageIds.add(packet.messageId)
        }

        // 2. Check if this device should act as Gateway
        val shouldUpload = isInternetAvailable

        // 3. Check TTL for forwarding
        if (packet.ttl <= 1) {
            Log.d(TAG, "TTL expired for ${packet.messageId} (TTL was ${packet.ttl})")
            return Pair(packet, MeshDecision.ProcessAndRelay(
                originalPacket = packet,
                packetToForward = null,
                shouldUploadToGateway = shouldUpload
            ))
        }

        // 4. Construct Forwarding Packet with decremented TTL and updated Relay Path
        val forwardPacket = packet.copy(
            ttl = packet.ttl - 1,
            hopCount = packet.hopCount + 1,
            relayPath = packet.relayPath + localDeviceName,
            gatewayId = if (shouldUpload) localDeviceName else packet.gatewayId
        )

        return Pair(packet, MeshDecision.ProcessAndRelay(
            originalPacket = packet,
            packetToForward = forwardPacket,
            shouldUploadToGateway = shouldUpload
        ))
    }

    /**
     * Mark locally generated packet as seen so this device won't process it if it loops back.
     */
    fun registerLocalSos(packet: SosPacket) {
        synchronized(seenMessageIds) {
            seenMessageIds.add(packet.messageId)
        }
    }

    fun clearCache() {
        synchronized(seenMessageIds) {
            seenMessageIds.clear()
        }
    }
}