package com.example.meshtest

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.mutableStateOf
import androidx.core.content.ContextCompat
import com.example.meshtest.ui.theme.MeshTestTheme

class MainActivity : ComponentActivity() {

    private val permissionsGranted = mutableStateOf(false)

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
            // Grant even if notifications were denied — mesh still works, just no notification shown
            val coreGranted = permissions.entries
                .filter { it.key != Manifest.permission.POST_NOTIFICATIONS }
                .all { it.value }
            permissionsGranted.value = coreGranted
            if (coreGranted) requestBatteryOptimizationExemption()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        checkAndRequestPermissions()
        enableEdgeToEdge()
        setContent {
            MeshTestTheme {
                NearbyScreen(context = this, permissionsGranted = permissionsGranted.value)
            }
        }
    }

    private fun checkAndRequestPermissions() {
        val needed = mutableListOf<String>()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            needed.add(Manifest.permission.BLUETOOTH_SCAN)
            needed.add(Manifest.permission.BLUETOOTH_ADVERTISE)
            needed.add(Manifest.permission.BLUETOOTH_CONNECT)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            needed.add(Manifest.permission.NEARBY_WIFI_DEVICES)
            needed.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        needed.add(Manifest.permission.ACCESS_FINE_LOCATION)
        needed.add(Manifest.permission.ACCESS_COARSE_LOCATION)

        val notGranted = needed.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (notGranted.isNotEmpty()) {
            permissionLauncher.launch(notGranted.toTypedArray())
        } else {
            permissionsGranted.value = true
            requestBatteryOptimizationExemption()
        }
    }

    /**
     * Pops a system dialog: "Allow ResQMesh to always run in background?"
     * CRITICAL for Samsung — without this, Samsung kills the foreground service
     * after ~5 minutes even with START_STICKY and PARTIAL_WAKE_LOCK.
     */
    private fun requestBatteryOptimizationExemption() {
        try {
            val pm = getSystemService(POWER_SERVICE) as PowerManager
            if (!pm.isIgnoringBatteryOptimizations(packageName)) {
                startActivity(
                    Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                        data = Uri.parse("package:$packageName")
                    }
                )
                Toast.makeText(
                    this,
                    "⚡ Tap 'Allow' so mesh stays alive when screen is off!",
                    Toast.LENGTH_LONG
                ).show()
            }
        } catch (_: Exception) {
            // Some OEM devices block this — fail silently
        }
    }
}