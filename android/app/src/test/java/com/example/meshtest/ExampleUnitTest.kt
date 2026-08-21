package com.example.meshtest

import com.example.meshtest.model.EmergencyType
import com.example.meshtest.model.SosPacket
import com.example.meshtest.model.SosPriority
import com.example.meshtest.model.SosPriorityCalculator
import com.example.meshtest.model.SosPriorityQueue
import org.junit.Test
import org.junit.Assert.*

class ExampleUnitTest {

    @Test
    fun testPriorityCalculation_TestA_LostBattery90() {
        val priority = SosPriorityCalculator.calculate(EmergencyType.LOST_ASSISTANCE, 90)
        assertEquals(SosPriority.MEDIUM, priority)
    }

    @Test
    fun testPriorityCalculation_TestB_EmergencyBattery90() {
        val priority = SosPriorityCalculator.calculate(EmergencyType.EMERGENCY, 90)
        assertEquals(SosPriority.HIGH, priority)
    }

    @Test
    fun testPriorityCalculation_TestC_EmergencyBattery10() {
        val priority = SosPriorityCalculator.calculate(EmergencyType.EMERGENCY, 10)
        assertEquals(SosPriority.URGENT, priority)
    }

    @Test
    fun testPriorityCalculation_TestD_LostBattery10() {
        val priority = SosPriorityCalculator.calculate(EmergencyType.LOST_ASSISTANCE, 10)
        assertEquals(SosPriority.MEDIUM, priority)
    }

    @Test
    fun testPriorityQueueOrdering() {
        val queue = SosPriorityQueue()

        val mediumSos = SosPacket(
            messageId = "SOS-MEDIUM",
            senderId = "DeviceA",
            priority = SosPriority.MEDIUM.label,
            batteryLevel = 90,
            timestamp = 1000L
        )

        val highSos = SosPacket(
            messageId = "SOS-HIGH",
            senderId = "DeviceB",
            priority = SosPriority.HIGH.label,
            batteryLevel = 75,
            timestamp = 2000L
        )

        val urgentSos = SosPacket(
            messageId = "SOS-URGENT",
            senderId = "DeviceC",
            priority = SosPriority.URGENT.label,
            batteryLevel = 8,
            timestamp = 3000L
        )

        // Enqueue in mixed order
        queue.enqueue(mediumSos)
        queue.enqueue(highSos)
        queue.enqueue(urgentSos)

        // Verify sorted list has URGENT -> HIGH -> MEDIUM
        val sorted = queue.getAll()
        assertEquals(3, sorted.size)
        assertEquals("SOS-URGENT", sorted[0].messageId)
        assertEquals("SOS-HIGH", sorted[1].messageId)
        assertEquals("SOS-MEDIUM", sorted[2].messageId)

        // Dequeue verification
        assertEquals("SOS-URGENT", queue.dequeue()?.messageId)
        assertEquals("SOS-HIGH", queue.dequeue()?.messageId)
        assertEquals("SOS-MEDIUM", queue.dequeue()?.messageId)
        assertNull(queue.dequeue())
    }
}