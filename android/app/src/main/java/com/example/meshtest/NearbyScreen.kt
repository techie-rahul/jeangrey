package com.example.meshtest

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.BatteryManager
import android.os.Build
import android.os.Looper
import android.util.Log
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.core.content.ContextCompat
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.meshtest.mesh.MeshDecision
import com.example.meshtest.mesh.MeshRouter
import com.example.meshtest.model.EmergencyType
import com.example.meshtest.model.SosPacket
import com.example.meshtest.model.SosPriority
import com.example.meshtest.model.SosPriorityCalculator
import com.example.meshtest.model.SosPriorityQueue
import com.example.meshtest.network.GatewayUploader
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.*
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationResult
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

import com.example.meshtest.battery.BatteryMonitor
import com.example.meshtest.acoustic.AcousticBeacon
import com.example.meshtest.acoustic.AcousticTransport
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults

private const val TAG = "ResQMesh"
private const val SERVICE_ID = "com.example.meshtest.sos"

// Verified Active Fresh Public Cloud Gateway Endpoint
private const val DEFAULT_SERVER_URL = "https://jeangrey.onrender.com"

@Composable
fun NearbyScreen(context: Context, permissionsGranted: Boolean) {
    val connectionsClient = remember { Nearby.getConnectionsClient(context) }
    val localDeviceName = remember {
        "${Build.MANUFACTURER.uppercase()}-${Build.MODEL}"
    }

    val meshRouter = remember { MeshRouter(localDeviceName) }
    val coroutineScope = rememberCoroutineScope()
    val listState = rememberLazyListState()

    val batteryMonitor = remember { BatteryMonitor(context) }
    val acousticTransport = remember { AcousticTransport(context) }

    val isAcousticFallbackTriggered by batteryMonitor.shouldTriggerAcoustic.collectAsState()
    val currentBatteryPct by batteryMonitor.batteryPercentage.collectAsState()
    val isPowerSaveMode by batteryMonitor.isPowerSaveMode.collectAsState()
    var forceAcousticTestMode by remember { mutableStateOf(false) }

    val isAcousticActive = isAcousticFallbackTriggered || forceAcousticTestMode

    // State
    var isMeshActive by remember { mutableStateOf(false) }
    var serverUrl by remember { mutableStateOf(DEFAULT_SERVER_URL) }
    var isGatewayModeEnabled by remember { mutableStateOf(true) }
    val connectedEndpoints = remember { mutableStateMapOf<String, String>() } // id -> name
    val connectingEndpoints = remember { mutableStateSetOf<String>() }
    val terminalLogs = remember { mutableStateListOf<String>() }
    var isRateLimited by remember { mutableStateOf(false) }

    // ── Location state ──────────────────────────────────────────────────
    var latitude by remember { mutableStateOf<Double?>(null) }
    var longitude by remember { mutableStateOf<Double?>(null) }
    var locAccuracy by remember { mutableStateOf<Float?>(null) }
    var locTimestamp by remember { mutableStateOf<Long?>(null) }

    // Acquire real device GPS location continuously using FusedLocationProviderClient + LocationManager
    LaunchedEffect(permissionsGranted) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION)
            == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION)
            == PackageManager.PERMISSION_GRANTED
        ) {
            try {
                // 1. Google Play Services Fused Location Provider (Fastest & Most Accurate)
                val fusedClient = LocationServices.getFusedLocationProviderClient(context)
                fusedClient.lastLocation.addOnSuccessListener { loc ->
                    if (loc != null) {
                        latitude = loc.latitude
                        longitude = loc.longitude
                        locAccuracy = loc.accuracy
                        locTimestamp = loc.time
                        Log.d(TAG, "📍 FUSED LAST LOCATION: lat=${loc.latitude}, lng=${loc.longitude}, accuracy=${loc.accuracy}m")
                    }
                }

                val locationRequest = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 2000L)
                    .setMinUpdateIntervalMillis(1000L)
                    .build()

                val locationCallback = object : LocationCallback() {
                    override fun onLocationResult(result: LocationResult) {
                        val loc = result.lastLocation ?: return
                        latitude = loc.latitude
                        longitude = loc.longitude
                        locAccuracy = loc.accuracy
                        locTimestamp = loc.time
                        Log.d(TAG, "📍 ACCURATE GPS FIX: lat=${loc.latitude}, lng=${loc.longitude}, accuracy=${loc.accuracy}m")
                    }
                }
                fusedClient.requestLocationUpdates(locationRequest, locationCallback, Looper.getMainLooper())

                // 2. Fallback to System LocationManager
                val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
                val lastLoc = lm.getLastKnownLocation(LocationManager.GPS_PROVIDER)
                    ?: lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
                    ?: lm.getLastKnownLocation(LocationManager.PASSIVE_PROVIDER)
                if (lastLoc != null && latitude == null) {
                    latitude = lastLoc.latitude
                    longitude = lastLoc.longitude
                    locAccuracy = lastLoc.accuracy
                    locTimestamp = lastLoc.time
                    Log.d(TAG, "📍 INITIAL LOCATION: lat=${lastLoc.latitude}, lng=${lastLoc.longitude}")
                }

                val locationListener = android.location.LocationListener { l ->
                    latitude = l.latitude
                    longitude = l.longitude
                    locAccuracy = l.accuracy
                    locTimestamp = l.time
                    Log.d(TAG, "📍 ACCURATE GPS FIX: lat=${l.latitude}, lng=${l.longitude}, accuracy=${l.accuracy}m")
                }

                if (lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                    lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 2000L, 1.0f, locationListener, Looper.getMainLooper())
                }
                if (lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                    lm.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 2000L, 1.0f, locationListener, Looper.getMainLooper())
                }
            } catch (e: Exception) {
                Log.e(TAG, "Location acquisition failed", e)
            }
        } else {
            Log.w(TAG, "Location permission not granted — location will be unavailable")
        }
    }

    // Priority Relay Queue & UI State
    val priorityQueue = remember { SosPriorityQueue() }
    var relayQueueItems by remember { mutableStateOf(listOf<SosPacket>()) }
    var showSituationDialog by remember { mutableStateOf(false) }
    var createdSosInfo by remember { mutableStateOf<Triple<String, SosPriority, Int>?>(null) }

    fun getBatteryPercentage(): Int = currentBatteryPct

    fun addLog(msg: String) {
        val time = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        terminalLogs.add("[$time] $msg")
        coroutineScope.launch {
            if (terminalLogs.isNotEmpty()) {
                listState.animateScrollToItem(terminalLogs.size - 1)
            }
        }
    }

    fun clearLogs() {
        terminalLogs.clear()
        addLog("🧹 Log cleared.")
    }

    // --- Broadcast SOS Helper ---
    fun broadcastPacketToPeers(packet: SosPacket, excludeEndpointId: String? = null) {
        val payload = Payload.fromBytes(packet.toByteArray())
        val targets = connectedEndpoints.keys.filter { it != excludeEndpointId }

        if (targets.isEmpty()) {
            addLog("⚠️ No other connected mesh peers to forward to.")
            return
        }

        targets.forEach { targetId ->
            connectionsClient.sendPayload(targetId, payload)
                .addOnSuccessListener {
                    Log.d(TAG, "Sent ${packet.messageId} to $targetId")
                }
                .addOnFailureListener { e ->
                    Log.e(TAG, "Failed send to $targetId", e)
                }
        }
        addLog("🚀 Relayed ${packet.messageId} (TTL: ${packet.ttl}) ➔ ${targets.size} peer(s)")
    }

    // --- Callbacks ---

    val payloadCallback = remember {
        object : PayloadCallback() {
            override fun onPayloadReceived(endpointId: String, payload: Payload) {
                if (payload.type == Payload.Type.BYTES) {
                    val bytes = payload.asBytes() ?: return
                    val hasInternet = isGatewayModeEnabled && GatewayUploader.hasInternetConnection(context)

                    val (packet, decision) = meshRouter.onReceivePayload(bytes, endpointId, hasInternet)
                    val senderName = connectedEndpoints[endpointId] ?: endpointId

                    when (decision) {
                        is MeshDecision.InvalidPayload -> {
                            addLog("❌ Unknown data received from $senderName")
                        }
                        is MeshDecision.DroppedDuplicate -> {
                            addLog("🛡️ Dropped duplicate ${decision.messageId} from $senderName (Loop Prevented)")
                        }
                        is MeshDecision.DroppedTtlExpired -> {
                            addLog("⏳ TTL expired for ${decision.messageId} from $senderName")
                        }
                        is MeshDecision.ProcessAndRelay -> {
                            val orig = decision.originalPacket
                            Log.d(TAG, "📍 RELAY LOCATION (original sender ${orig.senderId}): lat=${orig.latitude}, lng=${orig.longitude}")
                            priorityQueue.enqueue(orig)
                            relayQueueItems = priorityQueue.getAll()
                            addLog("🚨 RX SOS ${orig.messageId} | Priority: ${orig.priority} | Origin: ${orig.senderId} | Hops: ${orig.hopCount}")

                            // Check Gateway Upload
                            if (decision.shouldUploadToGateway) {
                                addLog("🌐 GATEWAY ACTIVE: Uploading ${orig.messageId} to Cloud...")
                                orig.gatewayId = localDeviceName
                                GatewayUploader.uploadSos(
                                    serverBaseUrl = serverUrl,
                                    packet = orig,
                                    onSuccess = { res ->
                                        addLog("✅ CLOUD UPLOAD SUCCESS: $res")
                                    },
                                    onError = { err ->
                                        if (err == "RATE_LIMITED") {
                                            isRateLimited = true
                                            addLog("⚠️ Rate limit reached — SOS queued locally, don't panic!")
                                        } else {
                                            addLog("❌ CLOUD UPLOAD ERROR: $err")
                                        }
                                    }
                                )
                            }

                            // Automatic Relay / Forwarding to other peers (A -> B -> C)
                            decision.packetToForward?.let { forwardPacket ->
                                broadcastPacketToPeers(forwardPacket, excludeEndpointId = endpointId)
                            }
                        }
                    }
                }
            }

            override fun onPayloadTransferUpdate(endpointId: String, update: PayloadTransferUpdate) {}
        }
    }

    val connectionLifecycleCallback = remember {
        object : ConnectionLifecycleCallback() {
            override fun onConnectionInitiated(endpointId: String, info: ConnectionInfo) {
                Log.d(TAG, "Connection initiated with ${info.endpointName}")
                addLog("🤝 Handshake with: ${info.endpointName}")
                connectionsClient.acceptConnection(endpointId, payloadCallback)
            }

            override fun onConnectionResult(endpointId: String, result: ConnectionResolution) {
                connectingEndpoints.remove(endpointId)
                if (result.status.statusCode == ConnectionsStatusCodes.STATUS_OK) {
                    val peerName = "Device-${endpointId.take(4).uppercase()}"
                    connectedEndpoints[endpointId] = peerName
                    addLog("🔗 Connected with $peerName (Active Peers: ${connectedEndpoints.size})")
                } else {
                    addLog("❌ Connection status (${result.status.statusCode}) with $endpointId")
                }
            }

            override fun onDisconnected(endpointId: String) {
                val name = connectedEndpoints.remove(endpointId) ?: endpointId
                connectingEndpoints.remove(endpointId)
                addLog("🔌 Disconnected from $name")
            }
        }
    }

    val endpointDiscoveryCallback = remember {
        object : EndpointDiscoveryCallback() {
            override fun onEndpointFound(endpointId: String, info: DiscoveredEndpointInfo) {
                Log.d(TAG, "Found endpoint: ${info.endpointName} ($endpointId)")
                
                val shouldInitiate = localDeviceName < info.endpointName

                if (shouldInitiate && !connectedEndpoints.containsKey(endpointId) && !connectingEndpoints.contains(endpointId)) {
                    connectingEndpoints.add(endpointId)
                    addLog("📡 Auto-connecting to peer: ${info.endpointName}")
                    connectionsClient.requestConnection(localDeviceName, endpointId, connectionLifecycleCallback)
                        .addOnFailureListener { e ->
                            connectingEndpoints.remove(endpointId)
                            Log.e(TAG, "Connection request failed", e)
                        }
                } else if (!shouldInitiate) {
                    addLog("👀 Discovered ${info.endpointName} (Waiting for incoming handshake)")
                }
            }

            override fun onEndpointLost(endpointId: String) {
                Log.d(TAG, "Lost nearby endpoint: $endpointId")
            }
        }
    }

    fun startMesh() {
        val advOptions = AdvertisingOptions.Builder().setStrategy(Strategy.P2P_CLUSTER).build()
        val discOptions = DiscoveryOptions.Builder().setStrategy(Strategy.P2P_CLUSTER).build()

        connectionsClient.startAdvertising(localDeviceName, SERVICE_ID, connectionLifecycleCallback, advOptions)
            .addOnSuccessListener {
                connectionsClient.startDiscovery(SERVICE_ID, endpointDiscoveryCallback, discOptions)
                    .addOnSuccessListener {
                        isMeshActive = true
                        addLog("⚡ MESH ACTIVE: Advertising & Discovering as $localDeviceName")
                    }
                    .addOnFailureListener { e ->
                        addLog("❌ Discovery Failed: ${e.message}")
                    }
            }
            .addOnFailureListener { e ->
                addLog("❌ Advertising Failed: ${e.message}")
            }
    }

    fun stopMesh() {
        connectionsClient.stopAdvertising()
        connectionsClient.stopDiscovery()
        connectionsClient.stopAllEndpoints()
        connectedEndpoints.clear()
        connectingEndpoints.clear()
        isMeshActive = false
        addLog("🛑 Mesh Stopped")
    }

    fun triggerPrioritySos(type: EmergencyType) {
        val battery = getBatteryPercentage()
        val priority = SosPriorityCalculator.calculate(type, battery)
        val msgText = when (type) {
            EmergencyType.LOST_ASSISTANCE -> "ASSISTANCE NEEDED: User is lost / needs search & rescue support."
            EmergencyType.EMERGENCY -> "CRITICAL EMERGENCY: Immediate Medical & Evacuation Support Requested!"
        }

        // Use the real device location if available; otherwise use SosPacket defaults
        val sosLat = latitude ?: 0.0
        val sosLng = longitude ?: 0.0
        Log.d(TAG, "📍 SOS LOCATION CAPTURED: lat=$sosLat, lng=$sosLng (raw state: lat=$latitude, lng=$longitude)")
        addLog("📍 Location: lat=$sosLat, lng=$sosLng")

        val sos = SosPacket(
            senderId = localDeviceName,
            batteryLevel = battery,
            priority = priority.label,
            emergencyType = type.name,
            severity = if (priority == SosPriority.MEDIUM) "MEDIUM" else "CRITICAL",
            latitude = sosLat,
            longitude = sosLng,
            ttl = 5,
            hopCount = 0,
            relayPath = listOf(localDeviceName),
            messageText = msgText
        )

        meshRouter.registerLocalSos(sos)
        priorityQueue.enqueue(sos)
        relayQueueItems = priorityQueue.getAll()

        createdSosInfo = Triple(sos.messageId, priority, battery)
        addLog("🚨 [SOS INITIATED] ID: ${sos.messageId} | Priority: ${priority.label} | Battery: $battery%")

        if (isGatewayModeEnabled && GatewayUploader.hasInternetConnection(context)) {
            addLog("🌐 LOCAL GATEWAY: Uploading directly to cloud backend...")
            sos.gatewayId = localDeviceName
            GatewayUploader.uploadSos(
                serverBaseUrl = serverUrl,
                packet = sos,
                onSuccess = { res -> addLog("✅ Direct Cloud Upload: $res") },
                onError = { err ->
                    if (err == "RATE_LIMITED") {
                        isRateLimited = true
                        addLog("⚠️ Rate limit reached — SOS queued locally, don't panic!")
                    } else {
                        addLog("❌ Direct Cloud Upload Failed: $err")
                    }
                }
            )
        }

        broadcastPacketToPeers(sos)

        // Simultaneous Acoustic Fallback Broadcast if active
        if (isAcousticActive) {
            val beaconStr = AcousticBeacon.fromSosPacket(sos)
            val sent = acousticTransport.transmit(beaconStr)
            if (sent) {
                addLog("🔊 [ACOUSTIC TX] Emitted sound beacon: $beaconStr")
            } else {
                addLog("⏳ [ACOUSTIC TX] Beacon rate-limited (10s cooldown)")
            }
        }
    }

    // Acoustic Fallback Background Listening & Automatic Ingestion Lifecycle
    LaunchedEffect(isAcousticActive, permissionsGranted) {
        if (isAcousticActive && permissionsGranted) {
            val triggerReason = when {
                forceAcousticTestMode -> "Manual Test Mode"
                isPowerSaveMode -> "Power Saver Active"
                currentBatteryPct <= 15 -> "Low Battery (<=15%)"
                else -> "Active"
            }
            addLog("🔊 [ACOUSTIC ACTIVE] Fallback tier engaged ($triggerReason) — Mic listening...")

            acousticTransport.startListening { rawBeacon ->
                val payload = AcousticBeacon.parseBeacon(rawBeacon)
                if (payload != null) {
                    addLog("🔊 RX ACOUSTIC BEACON: ID=${payload.id} | Sev=${payload.severityCode} | Geohash=${payload.geohash}")

                    // Deduplicate against seen IDs in MeshRouter
                    val dedupId = "ACOUSTIC-${payload.id}"
                    val isNew = if (!meshRouter.isMessageSeen(dedupId) && !meshRouter.isMessageSeen(payload.id)) {
                        meshRouter.registerMessageId(dedupId)
                        meshRouter.registerMessageId(payload.id)
                        true
                    } else {
                        false
                    }

                    if (isNew) {
                        val partialSos = AcousticBeacon.toPartialSosPacket(payload, localDeviceName)
                        val hasInternet = isGatewayModeEnabled && GatewayUploader.hasInternetConnection(context)
                        if (hasInternet) {
                            addLog("🌐 ACOUSTIC GATEWAY: Offloading acoustic SOS to cloud...")
                            GatewayUploader.uploadSos(
                                serverBaseUrl = serverUrl,
                                packet = partialSos,
                                onSuccess = { res -> addLog("✅ Cloud Ingest (Acoustic): $res") },
                                onError = { err -> addLog("❌ Cloud Ingest Error (Acoustic): $err") }
                            )
                        } else if (connectedEndpoints.isNotEmpty()) {
                            // Forward over BLE mesh to other peers if connected
                            addLog("📡 BLE RELAY: Bridging acoustic SOS over BLE mesh to ${connectedEndpoints.size} peer(s)...")
                            broadcastPacketToPeers(partialSos)
                        } else {
                            // Device B is ALSO completely offline (No Internet & No BLE) -> Acoustic Re-Chirp Relay!
                            addLog("🔊 [ACOUSTIC RELAY] No BLE/Internet — Re-chirping acoustic beacon to propagate...")
                            coroutineScope.launch {
                                kotlinx.coroutines.delay(2000L) // 2s jitter delay to prevent sound collision
                                val sent = acousticTransport.transmit(rawBeacon)
                                if (sent) {
                                    addLog("🔊 [ACOUSTIC RELAY] Re-chirped sound beacon: $rawBeacon")
                                }
                            }
                        }
                    } else {
                        addLog("🛡️ Dropped duplicate acoustic beacon ${payload.id} (Loop Prevented)")
                    }
                }
            }
        } else {
            if (acousticTransport.isListening()) {
                acousticTransport.stopListening()
                addLog("🔇 [ACOUSTIC STANDBY] Sound fallback tier idle")
            }
        }
    }

    DisposableEffect(Unit) {
        batteryMonitor.startMonitoring()
        onDispose {
            batteryMonitor.stopMonitoring()
            acousticTransport.stopListening()
            connectionsClient.stopAdvertising()
            connectionsClient.stopDiscovery()
            connectionsClient.stopAllEndpoints()
        }
    }

    // --- UI Screen ---
    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = Color(0xFF0B0F19)
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "ResQMesh",
                        fontSize = 24.sp,
                        fontWeight = FontWeight.Black,
                        color = Color.White
                    )
                    Text(
                        text = "Offline SOS Relay Node",
                        fontSize = 12.sp,
                        color = Color(0xFF94A3B8)
                    )
                }

                Column(horizontalAlignment = Alignment.End) {
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = if (isMeshActive) Color(0xFF10B981).copy(alpha = 0.2f) else Color(0xFFEF4444).copy(alpha = 0.2f),
                        border = BorderStroke(1.dp, if (isMeshActive) Color(0xFF10B981) else Color(0xFFEF4444))
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(8.dp)
                                    .clip(CircleShape)
                                    .background(if (isMeshActive) Color(0xFF10B981) else Color(0xFFEF4444))
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = if (isMeshActive) "MESH ON" else "OFFLINE",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.Monospace,
                                color = if (isMeshActive) Color(0xFF10B981) else Color(0xFFEF4444)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(4.dp))

                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = if (isAcousticActive) Color(0xFFF59E0B).copy(alpha = 0.2f) else Color(0xFF64748B).copy(alpha = 0.2f),
                        border = BorderStroke(1.dp, if (isAcousticActive) Color(0xFFF59E0B) else Color(0xFF64748B))
                    ) {
                        Text(
                            text = if (isAcousticActive) "🔊 ACOUSTIC ACTIVE" else "🔇 ACOUSTIC IDLE",
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace,
                            color = if (isAcousticActive) Color(0xFFF59E0B) else Color(0xFF94A3B8),
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // ── Rate Limit Warning Banner ─────────────────────────────────
            if (isRateLimited) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF451A03)),
                    border = BorderStroke(1.dp, Color(0xFFF59E0B))
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "⚠️  Too many requests — Don't panic!",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFFFBBF24)
                            )
                            Text(
                                text = "Your SOS has been saved locally. The cloud will sync shortly.",
                                fontSize = 11.sp,
                                color = Color(0xFFFDE68A)
                            )
                        }
                        TextButton(onClick = { isRateLimited = false }) {
                            Text("✕", color = Color(0xFFF59E0B), fontSize = 16.sp)
                        }
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
            }

            // Big Red SOS Button
            Button(
                onClick = { showSituationDialog = true },
                enabled = permissionsGranted,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(64.dp),
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFDC2626))
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = "🚨 SOS",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Black,
                        color = Color.White
                    )
                    Text(
                        text = "HOLD OR TAP TO SELECT SITUATION & SEND",
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White.copy(alpha = 0.8f)
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Mesh Controls
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = { startMesh() },
                    enabled = permissionsGranted && !isMeshActive,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2563EB))
                ) {
                    Text("Start Mesh")
                }

                OutlinedButton(
                    onClick = { stopMesh() },
                    enabled = isMeshActive,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text("Stop Mesh", color = Color.White)
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Node Status & Acoustic Fallback Card
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B))
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("Node: $localDeviceName", fontSize = 12.sp, color = Color.White, fontWeight = FontWeight.Bold)
                        Text("Peers: ${connectedEndpoints.size}", fontSize = 12.sp, color = Color(0xFF38BDF8), fontWeight = FontWeight.Bold)
                    }

                    Spacer(modifier = Modifier.height(6.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("⚡ Battery Level", fontSize = 11.sp, color = Color(0xFF94A3B8))
                        Text(
                            text = "$currentBatteryPct% ${if (isPowerSaveMode) "(Power Saver)" else ""}",
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            color = if (currentBatteryPct <= 15 || isPowerSaveMode) Color(0xFFF59E0B) else Color(0xFF10B981)
                        )
                    }

                    Spacer(modifier = Modifier.height(6.dp))

                    // Live GPS Fix Display
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("📍 GPS Location", fontSize = 11.sp, color = Color(0xFF94A3B8))
                        Text(
                            text = if (latitude != null && longitude != null) {
                                "%.4f, %.4f".format(Locale.US, latitude, longitude) + (locAccuracy?.let { " (±${it.toInt()}m)" } ?: "")
                            } else {
                                "Acquiring GPS Fix..."
                            },
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            color = if (latitude != null && longitude != null) Color(0xFF10B981) else Color(0xFFF59E0B)
                        )
                    }

                    Spacer(modifier = Modifier.height(6.dp))

                    // Gateway Server URL Display (Automatically Configured)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Cloud Gateway",
                            fontSize = 11.sp,
                            color = Color(0xFF94A3B8)
                        )
                        Text(
                            text = DEFAULT_SERVER_URL,
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                            color = Color(0xFF38BDF8)
                        )
                    }

                    Spacer(modifier = Modifier.height(8.dp))
                    HorizontalDivider(color = Color(0xFF334155))
                    Spacer(modifier = Modifier.height(8.dp))

                    // Force Acoustic Fallback Test Mode Toggle
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "🔊 Force Acoustic Test Mode",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            )
                            Text(
                                text = "Chirp & listen without draining battery",
                                fontSize = 10.sp,
                                color = Color(0xFF94A3B8)
                            )
                        }

                        Switch(
                            checked = forceAcousticTestMode,
                            onCheckedChange = { forceAcousticTestMode = it },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = Color.White,
                                checkedTrackColor = Color(0xFFF59E0B),
                                uncheckedThumbColor = Color(0xFF94A3B8),
                                uncheckedTrackColor = Color(0xFF334155)
                            )
                        )
                    }

                    if (isAcousticActive) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Button(
                            onClick = {
                                val shortId = "SOS" + (100..999).random()
                                val geohash = AcousticBeacon.encodeGeohash(latitude ?: 26.9124, longitude ?: 75.7873, 6)
                                val testBeacon = "$shortId|2|$geohash"
                                val sent = acousticTransport.transmit(testBeacon)
                                if (sent) {
                                    addLog("🔊 [MANUAL CHIRP] Emitted sound beacon: $testBeacon (Zero BLE / Wi-Fi needed)")
                                } else {
                                    addLog("⏳ [MANUAL CHIRP] Rate-limited (10s cooldown between chirps)")
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(8.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFD97706))
                        ) {
                            Text("🔊 TRANSMIT TEST CHIRP NOW", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color.White)
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Relay Queue Visualization Card
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B)),
                border = BorderStroke(1.dp, Color(0xFF334155))
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "RELAY QUEUE (PRIORITY ORDER)",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace,
                            color = Color(0xFF94A3B8)
                        )
                        Text(
                            text = "${relayQueueItems.size} in queue",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF38BDF8)
                        )
                    }

                    Spacer(modifier = Modifier.height(6.dp))

                    if (relayQueueItems.isEmpty()) {
                        Text(
                            text = "Queue empty. Broadcast or receive an SOS to see priority forwarding order.",
                            fontSize = 10.sp,
                            color = Color(0xFF64748B),
                            fontFamily = FontFamily.Monospace
                        )
                    } else {
                        relayQueueItems.take(3).forEachIndexed { idx, item ->
                            val priorityEnum = SosPriority.fromString(item.priority)
                            val badgeColor = when (priorityEnum) {
                                SosPriority.URGENT -> Color(0xFFEF4444)
                                SosPriority.HIGH -> Color(0xFFF97316)
                                SosPriority.MEDIUM -> Color(0xFFEAB308)
                            }
                            val statusLabel = if (idx == 0) "Forwarding first" else "Queued"

                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 3.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        text = when (priorityEnum) {
                                            SosPriority.URGENT -> "🚨"
                                            SosPriority.HIGH -> "🟠"
                                            SosPriority.MEDIUM -> "🟡"
                                        },
                                        fontSize = 13.sp
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Column {
                                        Text(
                                            text = item.messageId,
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Bold,
                                            fontFamily = FontFamily.Monospace,
                                            color = Color.White
                                        )
                                        Text(
                                            text = "Bat: ${item.batteryLevel}% • $statusLabel",
                                            fontSize = 10.sp,
                                            color = Color(0xFF94A3B8)
                                        )
                                    }
                                }

                                Surface(
                                    shape = RoundedCornerShape(4.dp),
                                    color = badgeColor.copy(alpha = 0.2f),
                                    border = BorderStroke(1.dp, badgeColor)
                                ) {
                                    Text(
                                        text = priorityEnum.label,
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                        fontSize = 9.sp,
                                        fontWeight = FontWeight.Black,
                                        fontFamily = FontFamily.Monospace,
                                        color = badgeColor
                                    )
                                }
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))
            // Live Relay Terminal Log Header + Clear Button
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "LIVE MESH TELEMETRY LOG",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace,
                    color = Color(0xFF94A3B8)
                )

                TextButton(
                    onClick = { clearLogs() },
                    contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp)
                ) {
                    Text("🧹 Clear Log", fontSize = 11.sp, color = Color(0xFF38BDF8), fontFamily = FontFamily.Monospace)
                }
            }

            Spacer(modifier = Modifier.height(4.dp))

            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF020617)),
                border = BorderStroke(1.dp, Color(0xFF1E293B))
            ) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(10.dp)
                ) {
                    if (terminalLogs.isEmpty()) {
                        item {
                            Text(
                                text = "Mesh initialized. Tap 'Start Mesh' to connect nearby peers.",
                                fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace,
                                color = Color(0xFF64748B)
                            )
                        }
                    } else {
                        items(terminalLogs.toList()) { log ->
                            Text(
                                text = log,
                                fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace,
                                color = when {
                                    log.contains("🚨") -> Color(0xFFF87171)
                                    log.contains("✅") -> Color(0xFF4ADE80)
                                    log.contains("🚀") -> Color(0xFF38BDF8)
                                    log.contains("🛡️") -> Color(0xFFFBBF24)
                                    else -> Color(0xFFCBD5E1)
                                },
                                modifier = Modifier.padding(vertical = 1.dp)
                            )
                        }
                    }
                }
            }
        }
    }

    // --- Situation Selection Dialog ---
    if (showSituationDialog) {
        AlertDialog(
            onDismissRequest = { showSituationDialog = false },
            containerColor = Color(0xFF1E293B),
            title = {
                Text(
                    text = "WHAT'S HAPPENING?",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        text = "Select your current emergency situation:",
                        fontSize = 13.sp,
                        color = Color(0xFF94A3B8)
                    )

                    // Option 1: Lost / Need Assistance
                    Surface(
                        onClick = {
                            showSituationDialog = false
                            triggerPrioritySos(EmergencyType.LOST_ASSISTANCE)
                        },
                        shape = RoundedCornerShape(12.dp),
                        color = Color(0xFF0F172A),
                        border = BorderStroke(1.dp, Color(0xFFEAB308).copy(alpha = 0.6f)),
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
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color.White
                                )
                                Text(
                                    text = "Non-life-threatening assistance",
                                    fontSize = 11.sp,
                                    color = Color(0xFF94A3B8)
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
                        color = Color(0xFF0F172A),
                        border = BorderStroke(1.dp, Color(0xFFEF4444).copy(alpha = 0.6f)),
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
                                    text = "I'm in an emergency",
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color.White
                                )
                                Text(
                                    text = "Critical danger / medical urgent",
                                    fontSize = 11.sp,
                                    color = Color(0xFF94A3B8)
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { showSituationDialog = false }) {
                    Text("Cancel", color = Color(0xFF94A3B8))
                }
            }
        )
    }

    // --- SOS Confirmation / Result Dialog ---
    createdSosInfo?.let { (msgId, priority, battery) ->
        val badgeColor = when (priority) {
            SosPriority.URGENT -> Color(0xFFEF4444)
            SosPriority.HIGH -> Color(0xFFF97316)
            SosPriority.MEDIUM -> Color(0xFFEAB308)
        }

        AlertDialog(
            onDismissRequest = { createdSosInfo = null },
            containerColor = Color(0xFF1E293B),
            title = {
                Text(
                    text = "SOS CREATED",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Black,
                    color = Color.White
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("SOS ID:", fontSize = 13.sp, color = Color(0xFF94A3B8))
                        Text(msgId, fontSize = 13.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace, color = Color.White)
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Priority:", fontSize = 13.sp, color = Color(0xFF94A3B8))
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = badgeColor.copy(alpha = 0.2f),
                            border = BorderStroke(1.dp, badgeColor)
                        ) {
                            Text(
                                text = priority.label,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Black,
                                color = badgeColor
                            )
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Battery Level:", fontSize = 13.sp, color = Color(0xFF94A3B8))
                        Text("$battery%", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Color.White)
                    }

                    Spacer(modifier = Modifier.height(4.dp))

                    Text(
                        text = "Your SOS will be given priority in the relay network.",
                        fontSize = 12.sp,
                        color = Color(0xFF38BDF8),
                        fontWeight = FontWeight.Medium
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = { createdSosInfo = null },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2563EB))
                ) {
                    Text("OK")
                }
            }
        )
    }
}