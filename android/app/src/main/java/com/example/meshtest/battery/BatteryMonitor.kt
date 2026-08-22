package com.example.meshtest.battery

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.PowerManager
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * BatteryMonitor tracks device battery level and power saver mode using system broadcasts.
 * It activates acoustic sound fallback when battery <= 15% OR when Power Saver is enabled.
 */
class BatteryMonitor(private val context: Context) {
    private val TAG = "BatteryMonitor"

    private val _batteryPercentage = MutableStateFlow(getInitialBatteryPercentage())
    val batteryPercentage: StateFlow<Int> = _batteryPercentage.asStateFlow()

    private val _isPowerSaveMode = MutableStateFlow(getInitialPowerSaveMode())
    val isPowerSaveMode: StateFlow<Boolean> = _isPowerSaveMode.asStateFlow()

    private val _shouldTriggerAcoustic = MutableStateFlow(
        _batteryPercentage.value <= LOW_BATTERY_THRESHOLD || _isPowerSaveMode.value
    )
    val shouldTriggerAcoustic: StateFlow<Boolean> = _shouldTriggerAcoustic.asStateFlow()

    private var isReceiverRegistered = false

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_BATTERY_CHANGED -> {
                    val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                    val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
                    if (level >= 0 && scale > 0) {
                        val pct = ((level.toFloat() / scale.toFloat()) * 100).toInt()
                        _batteryPercentage.value = pct
                        evaluateTrigger()
                    }
                }
                PowerManager.ACTION_POWER_SAVE_MODE_CHANGED -> {
                    _isPowerSaveMode.value = getInitialPowerSaveMode()
                    evaluateTrigger()
                }
            }
        }
    }

    private fun evaluateTrigger() {
        val triggered = _batteryPercentage.value <= LOW_BATTERY_THRESHOLD || _isPowerSaveMode.value
        if (_shouldTriggerAcoustic.value != triggered) {
            _shouldTriggerAcoustic.value = triggered
            Log.d(TAG, "Acoustic fallback trigger state changed: $triggered (Battery: ${_batteryPercentage.value}%, PowerSave: ${_isPowerSaveMode.value})")
        }
    }

    fun startMonitoring() {
        if (!isReceiverRegistered) {
            val filter = IntentFilter().apply {
                addAction(Intent.ACTION_BATTERY_CHANGED)
                addAction(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED)
            }
            context.registerReceiver(receiver, filter)
            isReceiverRegistered = true
            evaluateTrigger()
            Log.d(TAG, "BatteryMonitor registered with initial battery: ${_batteryPercentage.value}%, powerSave: ${_isPowerSaveMode.value}")
        }
    }

    fun stopMonitoring() {
        if (isReceiverRegistered) {
            try {
                context.unregisterReceiver(receiver)
            } catch (e: Exception) {
                Log.w(TAG, "Error unregistering receiver", e)
            }
            isReceiverRegistered = false
        }
    }

    private fun getInitialBatteryPercentage(): Int {
        return try {
            val bm = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
            bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: 80
        } catch (e: Exception) {
            80
        }
    }

    private fun getInitialPowerSaveMode(): Boolean {
        return try {
            val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
            pm?.isPowerSaveMode ?: false
        } catch (e: Exception) {
            false
        }
    }

    companion object {
        const val LOW_BATTERY_THRESHOLD = 15
    }
}