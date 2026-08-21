package com.example.meshtest.model

/**
 * Thread-safe Priority Queue for SOS Packets.
 *
 * Ordering:
 * 1. Priority Rank: URGENT (3) -> HIGH (2) -> MEDIUM (1)
 * 2. Timestamp: Earliest first for same priority rank
 */
class SosPriorityQueue {

    private val queueComparator = Comparator<SosPacket> { a, b ->
        val rankA = SosPriority.fromString(a.priority).rank
        val rankB = SosPriority.fromString(b.priority).rank
        if (rankA != rankB) {
            rankB.compareTo(rankA) // Higher rank first
        } else {
            a.timestamp.compareTo(b.timestamp) // Earlier timestamp first
        }
    }

    private val packets = mutableListOf<SosPacket>()
    private val lock = Any()

    fun enqueue(packet: SosPacket) {
        synchronized(lock) {
            val existingIndex = packets.indexOfFirst { it.messageId == packet.messageId }
            if (existingIndex != -1) {
                // Update existing packet if new hop/info received
                packets[existingIndex] = packet
            } else {
                packets.add(packet)
            }
            packets.sortWith(queueComparator)
        }
    }

    fun dequeue(): SosPacket? {
        synchronized(lock) {
            return if (packets.isNotEmpty()) {
                packets.removeAt(0)
            } else {
                null
            }
        }
    }

    fun peek(): SosPacket? {
        synchronized(lock) {
            return packets.firstOrNull()
        }
    }

    fun getAll(): List<SosPacket> {
        synchronized(lock) {
            return packets.toList()
        }
    }

    fun size(): Int {
        synchronized(lock) {
            return packets.size
        }
    }

    fun isEmpty(): Boolean {
        synchronized(lock) {
            return packets.isEmpty()
        }
    }

    fun clear() {
        synchronized(lock) {
            packets.clear()
        }
    }
}
