package com.example.meshtest

import android.content.Context
import android.os.BatteryManager
import android.os.Build
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
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

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

    // State
    var isMeshActive by remember { mutableStateOf(false) }
    var serverUrl by remember { mutableStateOf(DEFAULT_SERVER_URL) }
    var isGatewayModeEnabled by remember { mutableStateOf(true) }
    val connectedEndpoints = remember { mutableStateMapOf<String, String>() } // id -> name
    val connectingEndpoints = remember { mutableStateSetOf<String>() }
    val terminalLogs = remember { mutableStateListOf<String>() }

    // Priority Relay Queue & UI State
    val priorityQueue = remember { SosPriorityQueue() }
    var relayQueueItems by remember { mutableStateOf(listOf<SosPacket>()) }
    var showSituationDialog by remember { mutableStateOf(false) }
    var createdSosInfo by remember { mutableStateOf<Triple<String, SosPriority, Int>?>(null) }

    fun getBatteryPercentage(): Int {
        return try {
            val bm = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
            bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: 85
        } catch (e: Exception) {
            85
        }
    }

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
                                        addLog("❌ CLOUD UPLOAD ERROR: $err")
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

        val sos = SosPacket(
            senderId = localDeviceName,
            batteryLevel = battery,
            priority = priority.label,
            emergencyType = type.name,
            severity = if (priority == SosPriority.MEDIUM) "MEDIUM" else "CRITICAL",
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
                onError = { err -> addLog("❌ Direct Cloud Upload Failed: $err") }
            )
        }

        broadcastPacketToPeers(sos)
    }

    DisposableEffect(Unit) {
        onDispose {
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
            }

            Spacer(modifier = Modifier.height(12.dp))

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

            // Node Status Info Card
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