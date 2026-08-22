package com.example.meshtest.model

/**
 * Priority levels for SOS emergency messages.
 * Higher rank values indicate higher forwarding priority.
 */
enum class SosPriority(val rank: Int, val label: String) {
    URGENT(3, "URGENT"),
    HIGH(2, "HIGH"),
    MEDIUM(1, "MEDIUM");

    companion object {
        fun fromString(value: String): SosPriority {
            return when (value.uppercase()) {
                "URGENT" -> URGENT
                "HIGH" -> HIGH
                "MEDIUM" -> MEDIUM
                else -> HIGH
            }
        }
    }
}

/**
 * High-level situation categories presented to the user during SOS activation.
 */
enum class EmergencyType(val description: String) {
    LOST_ASSISTANCE("I'm lost / need assistance"),
    EMERGENCY("I'm in an emergency")
}

/**
 * Pure calculation logic for assigning SOS priority.
 *
 * Rules:
 * - LOST / NEED ASSISTANCE -> MEDIUM (regardless of battery)
 * - EMERGENCY + battery > 20% -> HIGH
 * - EMERGENCY + battery <= 20% -> URGENT
 */
object SosPriorityCalculator {
    fun calculate(type: EmergencyType, batteryLevel: Int): SosPriority {
        return when (type) {
            EmergencyType.LOST_ASSISTANCE -> SosPriority.MEDIUM
            EmergencyType.EMERGENCY -> {
                if (batteryLevel <= 20) {
                    SosPriority.URGENT
                } else {
                    SosPriority.HIGH
                }
            }
        }
    }
}
