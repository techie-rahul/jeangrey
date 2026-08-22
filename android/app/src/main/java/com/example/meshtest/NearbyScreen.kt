package com.example.meshtest

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.Looper
import android.util.Log
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.example.meshtest.mesh.MeshDecision
import com.example.meshtest.mesh.MeshRouter
import com.example.meshtest.model.EmergencyType
import com.example.meshtest.model.SosPacket
import com.example.meshtest.model.SosPriority
import com.example.meshtest.model.SosPriorityCalculator
import com.example.meshtest.model.SosPriorityQueue
import com.example.meshtest.network.GatewayUploader
import com.example.meshtest.ui.theme.*
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.*
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

private const val TAG = "ResQMesh"
private const val SERVICE_ID = "com.example.meshtest.sos"

// Verified Active Fresh Public Cloud Gateway Endpoint
private const val DEFAULT_SERVER_URL = "https://jeangrey.onrender.com"

enum class NavigationTab {
    DASHBOARD,
    ALERTS,
    MAP,
    SETTINGS
}

@Composable
fun NearbyScreen(context: Context, permissionsGranted: Boolean) {
    val localDeviceName = remember { "${Build.MANUFACTURER.uppercase()}-${Build.MODEL}" }
    val listState = rememberLazyListState()

    // ── All state comes from MeshRepository (background service) ─────────
    val isMeshActiveState by com.example.meshtest.service.MeshRepository.isMeshActive.collectAsState()
    val connectedEndpointsState by com.example.meshtest.service.MeshRepository.connectedEndpoints.collectAsState()
    val terminalLogsState by com.example.meshtest.service.MeshRepository.terminalLogs.collectAsState()
    val relayQueueItemsState by com.example.meshtest.service.MeshRepository.relayQueueItems.collectAsState()
    val serverUrlState by com.example.meshtest.service.MeshRepository.serverUrl.collectAsState()
    val isGatewayEnabledState by com.example.meshtest.service.MeshRepository.isGatewayModeEnabled.collectAsState()

    // Auto-scroll terminal log on new entries
    LaunchedEffect(terminalLogsState.size) {
        if (terminalLogsState.isNotEmpty()) listState.animateScrollToItem(terminalLogsState.size - 1)
    }

    // ── Location state ──────────────────────────────────────────────────
    var latitude by remember { mutableStateOf<Double?>(null) }
    var longitude by remember { mutableStateOf<Double?>(null) }
    var locTimestamp by remember { mutableStateOf<Long?>(null) }

    // Navigation and Modal UI State
    var selectedTab by remember { mutableStateOf(NavigationTab.DASHBOARD) }
    var showSettingsDialog by remember { mutableStateOf(false) }
    var showAllQueueItems by remember { mutableStateOf(false) }

    // Acquire real device GPS location
    LaunchedEffect(permissionsGranted) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION)
            == PackageManager.PERMISSION_GRANTED
        ) {
            try {
                val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
                val lastLoc = lm.getLastKnownLocation(LocationManager.GPS_PROVIDER)
                    ?: lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
                    ?: lm.getLastKnownLocation(LocationManager.PASSIVE_PROVIDER)
                if (lastLoc != null) {
                    latitude = lastLoc.latitude; longitude = lastLoc.longitude; locTimestamp = lastLoc.time
                }
                val locationListener = android.location.LocationListener { l ->
                    latitude = l.latitude; longitude = l.longitude; locTimestamp = l.time
                }
                @Suppress("DEPRECATION")
                try { lm.requestSingleUpdate(LocationManager.GPS_PROVIDER, locationListener, Looper.getMainLooper()) } catch (_: Exception) {}
                @Suppress("DEPRECATION")
                try { lm.requestSingleUpdate(LocationManager.NETWORK_PROVIDER, locationListener, Looper.getMainLooper()) } catch (_: Exception) {}
            } catch (e: Exception) { Log.e(TAG, "Location failed", e) }
        }
    }

    // Priority Relay Queue & UI State
    var showSituationDialog by remember { mutableStateOf(false) }
    var createdSosInfo by remember { mutableStateOf<Triple<String, SosPriority, Int>?>(null) }

    fun getBatteryPercentage(): Int = try {
        (context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager)
            ?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: 85
    } catch (_: Exception) { 85 }

    fun addLog(msg: String) { com.example.meshtest.service.MeshRepository.addLog(msg) }
    fun clearLogs() { com.example.meshtest.service.MeshRepository.clearLogs() }
    fun startMesh() { com.example.meshtest.service.MeshForegroundService.startService(context) }
    fun stopMesh() { com.example.meshtest.service.MeshForegroundService.stopService(context) }

    fun triggerPrioritySos(type: EmergencyType) {
        val sosLat = latitude ?: 0.0
        val sosLng = longitude ?: 0.0
        val battery = getBatteryPercentage()
        val priority = SosPriorityCalculator.calculate(type, battery)
        addLog("📍 SOS Location: lat=$sosLat, lng=$sosLng")
        createdSosInfo = Triple("SOS-" + (100000..999999).random().toString(16).uppercase(), priority, battery)
        com.example.meshtest.service.MeshForegroundService.triggerSos(context, type, sosLat, sosLng)
    }

    fun openInGoogleMaps() {
        val lat = latitude; val lng = longitude
        if (lat != null && lng != null && lat != 0.0 && lng != 0.0) {
            try {
                val intent = Intent(Intent.ACTION_VIEW,
                    Uri.parse("geo:$lat,$lng?q=$lat,$lng(ResQMesh+Node+$localDeviceName)"))
                    .apply { flags = Intent.FLAG_ACTIVITY_NEW_TASK }
                context.startActivity(intent)
            } catch (_: Exception) {
                Toast.makeText(context, "No maps application installed", Toast.LENGTH_SHORT).show()
            }
        } else {
            Toast.makeText(context, "Location not yet acquired", Toast.LENGTH_SHORT).show()
        }
    }

    // ── REDESIGNED RESQMESH INTERFACE ──────────────────────────────────
    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = ResQDarkBackground,
        bottomBar = {
            ResQBottomNavigationBar(
                selectedTab = selectedTab,
                onTabSelected = { tab ->
                    selectedTab = tab
                    when (tab) {
                        NavigationTab.MAP -> openInGoogleMaps()
                        NavigationTab.SETTINGS -> showSettingsDialog = true
                        else -> {}
                    }
                }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp, vertical = 8.dp)
        ) {
            // 1. HEADER
            ResQHeader(
                isMeshActive = isMeshActiveState,
                onSettingsClick = { showSettingsDialog = true }
            )

            Spacer(modifier = Modifier.height(14.dp))

            // Main Scrollable Area
            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                // When on ALERTS tab, prioritize showing the Queue & SOS controls
                if (selectedTab == NavigationTab.DASHBOARD || selectedTab == NavigationTab.ALERTS) {
                    // 2. SEND SOS
                    item {
                        ResQSosEmergencyButton(
                            permissionsGranted = permissionsGranted,
                            onSosClick = { showSituationDialog = true }
                        )
                    }

                    // 3. START/STOP MESH CONTROLS
                    item {
                        ResQMeshControls(
                            isMeshActive = isMeshActiveState,
                            permissionsGranted = permissionsGranted,
                            onStartMesh = { startMesh() },
                            onStopMesh = { stopMesh() }
                        )
                    }
                }

                if (selectedTab == NavigationTab.DASHBOARD) {
                    // 4 & 5. MESH STATUS & TOPOLOGY
                    item {
                        val hasInternet = remember(isMeshActiveState, isGatewayEnabledState) {
                            isGatewayEnabledState && GatewayUploader.hasInternetConnection(context)
                        }
                        ResQMeshStatusCard(
                            isMeshActive = isMeshActiveState,
                            nodeId = localDeviceName,
                            connectedPeersCount = connectedEndpointsState.size,
                            connectedEndpoints = connectedEndpointsState,
                            hasInternet = hasInternet,
                            serverUrl = serverUrlState,
                            recentRelayPath = relayQueueItemsState.firstOrNull()?.relayPath ?: emptyList()
                        )
                    }
                }

                // 6. RELAY QUEUE (PRIORITY ORDER)
                if (selectedTab == NavigationTab.DASHBOARD || selectedTab == NavigationTab.ALERTS) {
                    item {
                        ResQRelayQueueCard(
                            queueItems = relayQueueItemsState,
                            showAll = showAllQueueItems,
                            onToggleShowAll = { showAllQueueItems = !showAllQueueItems }
                        )
                    }
                }

                // 7. CURRENT LOCATION
                if (selectedTab == NavigationTab.DASHBOARD || selectedTab == NavigationTab.MAP) {
                    item {
                        ResQLocationCard(
                            latitude = latitude,
                            longitude = longitude,
                            timestamp = locTimestamp,
                            onOpenMaps = { openInGoogleMaps() }
                        )
                    }
                }

                // 8. LIVE MESH ACTIVITY
                if (selectedTab == NavigationTab.DASHBOARD) {
                    item {
                        ResQLiveActivityCard(
                            logs = terminalLogsState,
                            listState = listState,
                            onClearLogs = { clearLogs() }
                        )
                    }
                }
            }
        }
    }

    // ── Situation Selection Modal ──────────────────────────────────────
    if (showSituationDialog) {
        AlertDialog(
            onDismissRequest = { showSituationDialog = false },
            containerColor = ResQDarkCard,
            shape = RoundedCornerShape(16.dp),
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("🚨", fontSize = 20.sp)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "SELECT EMERGENCY TYPE",
                        style = Typography.titleMedium,
                        color = ResQTextPrimary
                    )
                }
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        text = "Choose your situation to broadcast with appropriate priority:",
                        style = Typography.bodyMedium,
                        color = ResQTextSecondary
                    )

                    // Option 1: Lost / Need Assistance
                    Surface(
                        onClick = {
                            showSituationDialog = false
                            triggerPrioritySos(EmergencyType.LOST_ASSISTANCE)
                        },
                        shape = RoundedCornerShape(12.dp),
                        color = ResQDarkCardSubtle,
                        border = BorderStroke(1.dp, ResQYellow.copy(alpha = 0.5f)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(14.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("🧭", fontSize = 24.sp)
                            Spacer(modifier = Modifier.width(12.dp))
                            Column {
                                Text(
                                    text = "I'm lost / need assistance",
                                    style = Typography.titleMedium,
                                    color = ResQTextPrimary
                                )
                                Text(
                                    text = "Non-life-threatening assistance (Medium Priority)",
                                    style = Typography.bodyMedium,
                                    color = ResQTextSecondary
                                )
                            }
                        }
                    }

                    // Option 2: In an Emergency
                    Surface(
                        onClick = {
                            showSituationDialog = false
                            triggerPrioritySos(EmergencyType.EMERGENCY)
                        },
                        shape = RoundedCornerShape(12.dp),
                        color = ResQDarkCardSubtle,
                        border = BorderStroke(1.dp, ResQRedBright.copy(alpha = 0.6f)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(14.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("🚨", fontSize = 24.sp)
                            Spacer(modifier = Modifier.width(12.dp))
                            Column {
                                Text(
                                    text = "Critical Emergency",
                                    style = Typography.titleMedium,
                                    color = ResQRedBright
                                )
                                Text(
                                    text = "Immediate medical / evacuation (High/Urgent)",
                                    style = Typography.bodyMedium,
                                    color = ResQTextSecondary
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { showSituationDialog = false }) {
                    Text("Cancel", color = ResQTextSecondary)
                }
            }
        )
    }

    // ── SOS Confirmation Dialog ────────────────────────────────────────
    createdSosInfo?.let { (msgId, priority, battery) ->
        val badgeColor = when (priority) {
            SosPriority.URGENT -> ResQRedBright
            SosPriority.HIGH -> ResQOrange
            SosPriority.MEDIUM -> ResQYellow
        }

        AlertDialog(
            onDismissRequest = { createdSosInfo = null },
            containerColor = ResQDarkCard,
            shape = RoundedCornerShape(16.dp),
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("✅", fontSize = 20.sp)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "SOS BROADCAST INITIATED",
                        style = Typography.titleMedium,
                        color = ResQTextPrimary
                    )
                }
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("SOS ID:", style = Typography.bodyMedium)
                        Text(msgId, style = Typography.labelMedium, color = ResQTextPrimary)
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Priority Rank:", style = Typography.bodyMedium)
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = badgeColor.copy(alpha = 0.2f),
                            border = BorderStroke(1.dp, badgeColor)
                        ) {
                            Text(
                                text = priority.label,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                                style = Typography.labelMedium,
                                color = badgeColor
                            )
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Battery Level:", style = Typography.bodyMedium)
                        Text("$battery%", style = Typography.labelMedium, color = ResQTextPrimary)
                    }

                    Spacer(modifier = Modifier.height(4.dp))

                    Text(
                        text = "Your packet is now queued for prioritized multi-hop mesh dissemination and cloud gateway relay.",
                        style = Typography.bodyMedium,
                        color = ResQBlueLight
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = { createdSosInfo = null },
                    colors = ButtonDefaults.buttonColors(containerColor = ResQBlue),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text("Acknowledge", color = Color.White)
                }
            }
        )
    }

    // ── Settings Dialog ────────────────────────────────────────────────
    if (showSettingsDialog) {
        var tempUrl by remember { mutableStateOf(serverUrlState) }
        AlertDialog(
            onDismissRequest = { showSettingsDialog = false },
            containerColor = ResQDarkCard,
            shape = RoundedCornerShape(16.dp),
            title = {
                Text(
                    text = "SETTINGS & GATEWAY",
                    style = Typography.titleMedium,
                    color = ResQTextPrimary
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(text = "Local Node ID:", style = Typography.bodyMedium)
                    Text(text = localDeviceName, style = Typography.labelMedium, color = ResQBlueLight)

                    HorizontalDivider(color = ResQBorderSubtle)

                    Text(text = "Cloud Gateway Backend URL:", style = Typography.bodyMedium)
                    OutlinedTextField(
                        value = tempUrl,
                        onValueChange = { tempUrl = it },
                        singleLine = true,
                        textStyle = Typography.labelMedium.copy(color = ResQTextPrimary),
                        modifier = Modifier.fillMaxWidth(),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = ResQBlueLight,
                            unfocusedBorderColor = ResQBorder,
                            focusedContainerColor = ResQDarkCardSubtle,
                            unfocusedContainerColor = ResQDarkCardSubtle
                        )
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Gateway Upload Enabled", style = Typography.bodyMedium)
                        Switch(
                            checked = isGatewayEnabledState,
                            onCheckedChange = { com.example.meshtest.service.MeshRepository.setGatewayMode(it) },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = Color.White,
                                checkedTrackColor = ResQBlue
                            )
                        )
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        com.example.meshtest.service.MeshRepository.setServerUrl(tempUrl.trim())
                        showSettingsDialog = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = ResQBlue),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text("Save")
                }
            },
            dismissButton = {
                TextButton(onClick = { showSettingsDialog = false }) {
                    Text("Close", color = ResQTextSecondary)
                }
            }
        )
    }
}

// ───────────────────────────────────────────────────────────────────────
// COMPONENT 1: HEADER
// ───────────────────────────────────────────────────────────────────────
@Composable
fun ResQHeader(
    isMeshActive: Boolean,
    onSettingsClick: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column {
            Text(
                text = "ResQMesh",
                style = Typography.headlineLarge,
                color = ResQTextPrimary
            )
            Text(
                text = "Offline SOS Relay Network",
                style = Typography.bodyMedium,
                color = ResQTextSecondary
            )
        }

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Dynamic Mesh Status Badge
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = if (isMeshActive) ResQGreenBg else ResQDarkCardSubtle,
                border = BorderStroke(1.dp, if (isMeshActive) ResQGreen else ResQBorder)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(if (isMeshActive) ResQGreenBright else ResQTextMuted)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = if (isMeshActive) "MESH ACTIVE" else "OFFLINE",
                        style = Typography.labelMedium,
                        color = if (isMeshActive) ResQGreenBright else ResQTextSecondary
                    )
                }
            }

            // Settings Icon
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = ResQDarkCardSubtle,
                border = BorderStroke(1.dp, ResQBorderSubtle),
                modifier = Modifier
                    .size(36.dp)
                    .clickable { onSettingsClick() }
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text("⚙️", fontSize = 16.sp)
                }
            }
        }
    }
}

// ───────────────────────────────────────────────────────────────────────
// COMPONENT 2: SEND SOS BUTTON
// ───────────────────────────────────────────────────────────────────────
@Composable
fun ResQSosEmergencyButton(
    permissionsGranted: Boolean,
    onSosClick: () -> Unit
) {
    Surface(
        onClick = onSosClick,
        enabled = permissionsGranted,
        shape = RoundedCornerShape(16.dp),
        color = ResQRed,
        border = BorderStroke(1.dp, ResQRedBright),
        modifier = Modifier
            .fillMaxWidth()
            .height(84.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 20.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.Center
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("🚨", fontSize = 20.sp)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "SEND SOS",
                        style = Typography.headlineMedium.copy(fontWeight = FontWeight.Black),
                        color = Color.White
                    )
                }
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = "Tap to send • Hold for emergency",
                    style = Typography.bodyMedium.copy(fontSize = 13.sp),
                    color = Color.White.copy(alpha = 0.9f)
                )
            }

            Surface(
                shape = CircleShape,
                color = Color.White.copy(alpha = 0.2f),
                modifier = Modifier.size(40.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text("➔", color = Color.White, fontWeight = FontWeight.Black, fontSize = 18.sp)
                }
            }
        }
    }
}

// ───────────────────────────────────────────────────────────────────────
// COMPONENT 3: START / STOP MESH CONTROLS
// ───────────────────────────────────────────────────────────────────────
@Composable
fun ResQMeshControls(
    isMeshActive: Boolean,
    permissionsGranted: Boolean,
    onStartMesh: () -> Unit,
    onStopMesh: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // Start Mesh Button
        Button(
            onClick = onStartMesh,
            enabled = permissionsGranted && !isMeshActive,
            modifier = Modifier
                .weight(1f)
                .height(48.dp),
            shape = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = ResQBlue,
                disabledContainerColor = ResQDarkCardSubtle,
                disabledContentColor = ResQTextMuted
            ),
            border = if (!isMeshActive) BorderStroke(1.dp, ResQBlueLight) else BorderStroke(1.dp, ResQBorderSubtle)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("⚡", fontSize = 14.sp)
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "START MESH",
                    style = Typography.labelLarge,
                    color = if (!isMeshActive && permissionsGranted) Color.White else ResQTextMuted
                )
            }
        }

        // Stop Mesh Button
        OutlinedButton(
            onClick = onStopMesh,
            enabled = isMeshActive,
            modifier = Modifier
                .weight(1f)
                .height(48.dp),
            shape = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.outlinedButtonColors(
                containerColor = if (isMeshActive) ResQRedBg else ResQDarkCardSubtle,
                contentColor = if (isMeshActive) ResQRedBright else ResQTextMuted
            ),
            border = BorderStroke(1.dp, if (isMeshActive) ResQRedBright.copy(alpha = 0.8f) else ResQBorderSubtle)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("🛑", fontSize = 14.sp)
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "STOP MESH",
                    style = Typography.labelLarge,
                    color = if (isMeshActive) ResQRedBright else ResQTextMuted
                )
            }
        }
    }
}

// ───────────────────────────────────────────────────────────────────────
// COMPONENT 4 & 5: MESH STATUS & TOPOLOGY CARD
// ───────────────────────────────────────────────────────────────────────
@Composable
fun ResQMeshStatusCard(
    isMeshActive: Boolean,
    nodeId: String,
    connectedPeersCount: Int,
    connectedEndpoints: Map<String, String>,
    hasInternet: Boolean,
    serverUrl: String,
    recentRelayPath: List<String>
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = ResQDarkCard),
        border = BorderStroke(1.dp, ResQBorder)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            // Card Title Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "MESH STATUS",
                        style = Typography.labelMedium.copy(color = ResQTextSecondary)
                    )
                }

                Text(
                    text = if (isMeshActive) "ACTIVE RELAY" else "STANDBY",
                    style = Typography.labelSmall.copy(
                        color = if (isMeshActive) ResQGreenBright else ResQTextMuted
                    )
                )
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Metadata Grid
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                // Node ID
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("Node ID", style = Typography.bodyMedium)
                    Text(
                        text = nodeId,
                        style = Typography.labelMedium,
                        color = ResQTextPrimary
                    )
                }

                // Connected Peers
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("Connected Peers", style = Typography.bodyMedium)
                    Text(
                        text = if (connectedPeersCount > 0) "$connectedPeersCount active" else "0 (Scanning)",
                        style = Typography.labelMedium,
                        color = if (connectedPeersCount > 0) ResQBlueLight else ResQTextMuted
                    )
                }

                // Cloud Gateway
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("Cloud Gateway", style = Typography.bodyMedium)
                    Text(
                        text = if (hasInternet) "Online (Sync Ready)" else "Offline (P2P Relay Only)",
                        style = Typography.labelMedium,
                        color = if (hasInternet) ResQGreenBright else ResQOrange
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))
            HorizontalDivider(color = ResQBorderSubtle)
            Spacer(modifier = Modifier.height(10.dp))

            // Network Topology Section
            Text(
                text = "NETWORK TOPOLOGY",
                style = Typography.labelSmall.copy(color = ResQTextSecondary)
            )

            Spacer(modifier = Modifier.height(8.dp))

            // Topology Flow Visualizer
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(ResQDarkCardSubtle)
                    .padding(horizontal = 8.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                TopologyNode(label = "You", subLabel = nodeId.take(8), isActive = true)
                Text("➔", color = ResQTextMuted, fontSize = 12.sp)
                TopologyNode(
                    label = "Peers",
                    subLabel = if (connectedPeersCount > 0) "$connectedPeersCount connected" else "N/A",
                    isActive = connectedPeersCount > 0
                )
                Text("➔", color = ResQTextMuted, fontSize = 12.sp)
                TopologyNode(
                    label = "Relays",
                    subLabel = if (recentRelayPath.isNotEmpty()) "${recentRelayPath.size} hops" else "Mesh Ready",
                    isActive = isMeshActive
                )
                Text("➔", color = ResQTextMuted, fontSize = 12.sp)
                TopologyNode(
                    label = "Gateway",
                    subLabel = if (hasInternet) "Online" else "Pending",
                    isActive = hasInternet
                )
            }
        }
    }
}

@Composable
private fun TopologyNode(label: String, subLabel: String, isActive: Boolean) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = label,
            style = Typography.labelMedium,
            color = if (isActive) ResQBlueLight else ResQTextMuted
        )
        Text(
            text = subLabel,
            style = Typography.labelSmall,
            color = if (isActive) ResQTextPrimary else ResQTextMuted
        )
    }
}

// ───────────────────────────────────────────────────────────────────────
// COMPONENT 6: RELAY QUEUE (PRIORITY ORDER) CARD
// ───────────────────────────────────────────────────────────────────────
@Composable
fun ResQRelayQueueCard(
    queueItems: List<SosPacket>,
    showAll: Boolean,
    onToggleShowAll: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = ResQDarkCard),
        border = BorderStroke(1.dp, ResQBorder)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "RELAY QUEUE (PRIORITY ORDER)",
                        style = Typography.labelMedium.copy(color = ResQTextSecondary)
                    )
                }

                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = ResQDarkCardSubtle,
                    border = BorderStroke(1.dp, ResQBorderSubtle)
                ) {
                    Text(
                        text = "${queueItems.size} in queue",
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                        style = Typography.labelSmall,
                        color = if (queueItems.isNotEmpty()) ResQBlueLight else ResQTextMuted
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            if (queueItems.isEmpty()) {
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = ResQDarkCardSubtle,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = "Queue empty. Discovered SOS emergency packets will be buffered and forwarded based on life-safety priority rank.",
                        modifier = Modifier.padding(10.dp),
                        style = Typography.bodyMedium.copy(fontSize = 11.sp),
                        color = ResQTextMuted
                    )
                }
            } else {
                val displayList = if (showAll) queueItems else queueItems.take(3)

                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    displayList.forEachIndexed { index, item ->
                        val priorityEnum = SosPriority.fromString(item.priority)
                        val badgeColor = when (priorityEnum) {
                            SosPriority.URGENT -> ResQRedBright
                            SosPriority.HIGH -> ResQOrange
                            SosPriority.MEDIUM -> ResQYellow
                        }
                        val statusLabel = if (index == 0) "Forwarding first" else "Queued"

                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = ResQDarkCardSubtle,
                            border = BorderStroke(1.dp, ResQBorderSubtle),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(10.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Text(
                                        text = when (priorityEnum) {
                                            SosPriority.URGENT -> "🚨"
                                            SosPriority.HIGH -> "🟠"
                                            SosPriority.MEDIUM -> "🟡"
                                        },
                                        fontSize = 14.sp
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Column {
                                        Text(
                                            text = item.messageId,
                                            style = Typography.labelMedium,
                                            color = ResQTextPrimary
                                        )
                                        Text(
                                            text = "Bat: ${item.batteryLevel}% • $statusLabel • Hops: ${item.hopCount}",
                                            style = Typography.bodyMedium.copy(fontSize = 10.sp),
                                            color = ResQTextSecondary
                                        )
                                    }
                                }

                                Surface(
                                    shape = RoundedCornerShape(4.dp),
                                    color = badgeColor.copy(alpha = 0.15f),
                                    border = BorderStroke(1.dp, badgeColor)
                                ) {
                                    Text(
                                        text = priorityEnum.label,
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                        style = Typography.labelSmall,
                                        color = badgeColor
                                    )
                                }
                            }
                        }
                    }
                }

                if (queueItems.size > 3) {
                    TextButton(
                        onClick = onToggleShowAll,
                        modifier = Modifier.align(Alignment.End)
                    ) {
                        Text(
                            text = if (showAll) "Show less" else "View all (${queueItems.size})",
                            style = Typography.labelMedium,
                            color = ResQBlueLight
                        )
                    }
                }
            }
        }
    }
}

// ───────────────────────────────────────────────────────────────────────
// COMPONENT 7: CURRENT LOCATION CARD
// ───────────────────────────────────────────────────────────────────────
@Composable
fun ResQLocationCard(
    latitude: Double?,
    longitude: Double?,
    timestamp: Long?,
    onOpenMaps: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = ResQDarkCard),
        border = BorderStroke(1.dp, ResQBorder)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "CURRENT LOCATION",
                    style = Typography.labelMedium.copy(color = ResQTextSecondary)
                )

                Text(
                    text = if (latitude != null) "GPS Locked" else "Acquiring...",
                    style = Typography.labelSmall.copy(
                        color = if (latitude != null) ResQGreenBright else ResQOrange
                    )
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            Surface(
                shape = RoundedCornerShape(8.dp),
                color = ResQDarkCardSubtle,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(10.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = if (latitude != null && longitude != null) {
                                String.format(Locale.US, "%.5f, %.5f", latitude, longitude)
                            } else {
                                "Waiting for GPS Fix..."
                            },
                            style = Typography.labelMedium,
                            color = ResQTextPrimary
                        )
                        Text(
                            text = if (timestamp != null) {
                                "Fix Time: " + SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(timestamp))
                            } else {
                                "Accuracy: Standard GPS Provider"
                            },
                            style = Typography.bodyMedium.copy(fontSize = 10.sp),
                            color = ResQTextSecondary
                        )
                    }

                    Button(
                        onClick = onOpenMaps,
                        enabled = latitude != null && longitude != null,
                        shape = RoundedCornerShape(8.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = ResQBlue),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                    ) {
                        Text("OPEN IN MAPS", style = Typography.labelSmall, color = Color.White)
                    }
                }
            }
        }
    }
}

// ───────────────────────────────────────────────────────────────────────
// COMPONENT 8: LIVE MESH ACTIVITY
// ───────────────────────────────────────────────────────────────────────
@Composable
fun ResQLiveActivityCard(
    logs: List<String>,
    listState: androidx.compose.foundation.lazy.LazyListState,
    onClearLogs: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .height(200.dp),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = ResQDarkCard),
        border = BorderStroke(1.dp, ResQBorder)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "LIVE MESH ACTIVITY",
                    style = Typography.labelMedium.copy(color = ResQTextSecondary)
                )

                TextButton(
                    onClick = onClearLogs,
                    contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp)
                ) {
                    Text("Clear Log", style = Typography.labelSmall, color = ResQBlueLight)
                }
            }

            Spacer(modifier = Modifier.height(4.dp))

            Surface(
                shape = RoundedCornerShape(8.dp),
                color = ResQDarkCardSubtle,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            ) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(8.dp)
                ) {
                    if (logs.isEmpty()) {
                        item {
                            Text(
                                text = "Mesh initialized. Tap 'Start Mesh' to connect nearby peers.",
                                style = Typography.bodyMedium.copy(fontSize = 11.sp),
                                color = ResQTextMuted
                            )
                        }
                    } else {
                        items(logs) { log ->
                            Text(
                                text = log,
                                style = Typography.bodyMedium.copy(
                                    fontSize = 11.sp,
                                    fontFamily = FontFamily.Monospace
                                ),
                                color = when {
                                    log.contains("🚨") || log.contains("❌") -> ResQRedBright
                                    log.contains("✅") || log.contains("⚡") -> ResQGreenBright
                                    log.contains("🚀") || log.contains("🔗") -> ResQBlueLight
                                    log.contains("🛡️") || log.contains("⚠️") -> ResQYellow
                                    else -> ResQTextSecondary
                                },
                                modifier = Modifier.padding(vertical = 1.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

// ───────────────────────────────────────────────────────────────────────
// COMPONENT 9: BOTTOM NAVIGATION BAR
// ───────────────────────────────────────────────────────────────────────
@Composable
fun ResQBottomNavigationBar(
    selectedTab: NavigationTab,
    onTabSelected: (NavigationTab) -> Unit
) {
    NavigationBar(
        containerColor = ResQDarkSurface,
        contentColor = ResQTextPrimary,
        tonalElevation = 0.dp
    ) {
        NavigationBarItem(
            selected = selectedTab == NavigationTab.DASHBOARD,
            onClick = { onTabSelected(NavigationTab.DASHBOARD) },
            icon = { Text("📡", fontSize = 16.sp) },
            label = { Text("Dashboard", style = Typography.labelSmall) },
            colors = NavigationBarItemDefaults.colors(
                selectedIconColor = ResQBlueLight,
                selectedTextColor = ResQBlueLight,
                unselectedIconColor = ResQTextMuted,
                unselectedTextColor = ResQTextMuted,
                indicatorColor = ResQBlueBg
            )
        )

        NavigationBarItem(
            selected = selectedTab == NavigationTab.ALERTS,
            onClick = { onTabSelected(NavigationTab.ALERTS) },
            icon = { Text("🚨", fontSize = 16.sp) },
            label = { Text("Alerts", style = Typography.labelSmall) },
            colors = NavigationBarItemDefaults.colors(
                selectedIconColor = ResQRedBright,
                selectedTextColor = ResQRedBright,
                unselectedIconColor = ResQTextMuted,
                unselectedTextColor = ResQTextMuted,
                indicatorColor = ResQRedBg
            )
        )

        NavigationBarItem(
            selected = selectedTab == NavigationTab.MAP,
            onClick = { onTabSelected(NavigationTab.MAP) },
            icon = { Text("🗺️", fontSize = 16.sp) },
            label = { Text("Map", style = Typography.labelSmall) },
            colors = NavigationBarItemDefaults.colors(
                selectedIconColor = ResQGreenBright,
                selectedTextColor = ResQGreenBright,
                unselectedIconColor = ResQTextMuted,
                unselectedTextColor = ResQTextMuted,
                indicatorColor = ResQGreenBg
            )
        )

        NavigationBarItem(
            selected = selectedTab == NavigationTab.SETTINGS,
            onClick = { onTabSelected(NavigationTab.SETTINGS) },
            icon = { Text("⚙️", fontSize = 16.sp) },
            label = { Text("Settings", style = Typography.labelSmall) },
            colors = NavigationBarItemDefaults.colors(
                selectedIconColor = ResQTextPrimary,
                selectedTextColor = ResQTextPrimary,
                unselectedIconColor = ResQTextMuted,
                unselectedTextColor = ResQTextMuted,
                indicatorColor = ResQDarkCard
            )
        )
    }
}