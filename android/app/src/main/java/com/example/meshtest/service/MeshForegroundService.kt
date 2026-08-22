package com.example.meshtest.service

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.meshtest.MainActivity
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

/**
 * Foreground Service: keeps ResQMesh P2P relay alive in background.
 * - PARTIAL_WAKE_LOCK: survives screen-off
 * - START_STICKY + onTaskRemoved AlarmManager: survives swipe-away
 * - Deterministic tie-breaker with delayed fallback: prevents cross-connection race collisions
 * - Staggered auto-retry on drop / disconnect
 */
class MeshForegroundService : Service() {

    private val TAG = "MeshForegroundService"
    private val NOTIFICATION_ID = 1001
    private val CHANNEL_ID = "resqmesh_relay_channel"
    private val SERVICE_ID = "com.example.meshtest.sos"

    private val FALLBACK_INITIATE_DELAY_MS = 5_000L

    private lateinit var connectionsClient: ConnectionsClient
    private lateinit var localDeviceName: String
    private lateinit var meshRouter: MeshRouter
    private val priorityQueue = SosPriorityQueue()

    private val connectedEndpoints  = mutableMapOf<String, String>()  // endpointId -> name
    private val connectingEndpoints = mutableSetOf<String>()          // endpointIds in progress
    private val discoveredEndpoints = mutableMapOf<String, String>()  // endpointId -> name

    private var wakeLock: PowerManager.WakeLock? = null
    private var totalRelayedCount = 0
    private val handler = Handler(Looper.getMainLooper())

    override fun onCreate() {
        super.onCreate()
        localDeviceName = "${Build.MANUFACTURER.uppercase()}-${Build.MODEL}"
        connectionsClient = Nearby.getConnectionsClient(this)
        meshRouter = MeshRouter(localDeviceName)
        createNotificationChannel()
        acquireWakeLock()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = buildNotification("Mesh Relay Active · Standby")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                startForeground(
                    NOTIFICATION_ID, notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE or
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
                )
            } catch (e: Exception) {
                startForeground(NOTIFICATION_ID, notification)
            }
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        when (intent?.action ?: ACTION_START_MESH) {
            ACTION_START_MESH    -> startMesh()
            ACTION_STOP_MESH     -> { stopMesh(); stopSelf() }
            ACTION_BROADCAST_SOS -> {
                val type = try {
                    EmergencyType.valueOf(intent?.getStringExtra(EXTRA_EMERGENCY_TYPE) ?: "")
                } catch (_: Exception) { EmergencyType.EMERGENCY }
                val lat = intent?.getDoubleExtra(EXTRA_LATITUDE, 0.0) ?: 0.0
                val lng = intent?.getDoubleExtra(EXTRA_LONGITUDE, 0.0) ?: 0.0
                triggerSos(type, lat, lng)
            }
            ACTION_BROADCAST_MESSAGE -> {
                val messageText = intent?.getStringExtra(EXTRA_CUSTOM_MESSAGE) ?: "EMERGENCY MESSAGE"
                val lat = intent?.getDoubleExtra(EXTRA_LATITUDE, 0.0) ?: 0.0
                val lng = intent?.getDoubleExtra(EXTRA_LONGITUDE, 0.0) ?: 0.0
                triggerEmergencyMessage(messageText, lat, lng)
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ── Swipe Survival ──────────────────────────────────────────────────
    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        MeshRepository.addLog("🛡️ App swiped — background relay persisting...")
        val pending = PendingIntent.getService(
            applicationContext, 1,
            Intent(applicationContext, MeshForegroundService::class.java).apply {
                action = ACTION_START_MESH; setPackage(packageName)
            },
            PendingIntent.FLAG_ONE_SHOT or immutableFlag()
        )
        (getSystemService(Context.ALARM_SERVICE) as? AlarmManager)
            ?.set(AlarmManager.RTC_WAKEUP, System.currentTimeMillis() + 1000L, pending)
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
        stopMesh()
        releaseWakeLock()
    }

    // ── Mesh Engine ─────────────────────────────────────────────────────
    private fun startMesh() {
        if (MeshRepository.isMeshActive.value) return
        startAdvertisingAndDiscovery()
    }

    private fun startAdvertisingAndDiscovery() {
        val advOpts = AdvertisingOptions.Builder().setStrategy(Strategy.P2P_CLUSTER).build()
        val discOpts = DiscoveryOptions.Builder().setStrategy(Strategy.P2P_CLUSTER).build()

        connectionsClient.startAdvertising(
            localDeviceName, SERVICE_ID, connectionLifecycleCallback, advOpts
        ).addOnSuccessListener {
            MeshRepository.addLog("📡 Advertising as $localDeviceName")
            connectionsClient.startDiscovery(SERVICE_ID, endpointDiscoveryCallback, discOpts)
                .addOnSuccessListener {
                    MeshRepository.setMeshActive(true)
                    MeshRepository.addLog("⚡ MESH ACTIVE — Advertising & Discovering peers")
                    updateNotification("Active Peers: ${connectedEndpoints.size} | Relayed: $totalRelayedCount")
                }
                .addOnFailureListener { e ->
                    MeshRepository.addLog("❌ Discovery Failed: ${e.message}")
                    handler.postDelayed({ startAdvertisingAndDiscovery() }, 4000L)
                }
        }.addOnFailureListener { e ->
            MeshRepository.addLog("❌ Advertising Failed: ${e.message}")
            handler.postDelayed({ startAdvertisingAndDiscovery() }, 4000L)
        }
    }

    private fun stopMesh() {
        handler.removeCallbacksAndMessages(null)
        try {
            connectionsClient.stopAdvertising()
            connectionsClient.stopDiscovery()
            connectionsClient.stopAllEndpoints()
        } catch (_: Exception) {}
        connectedEndpoints.clear()
        connectingEndpoints.clear()
        discoveredEndpoints.clear()
        MeshRepository.updateConnectedEndpoints(emptyMap())
        MeshRepository.updateConnectingEndpoints(emptySet())
        MeshRepository.setMeshActive(false)
        MeshRepository.addLog("🛑 Background Mesh Stopped")
    }

    private fun triggerSos(type: EmergencyType, lat: Double, lng: Double) {
        val battery = getBatteryPercentage()
        val priority = SosPriorityCalculator.calculate(type, battery)
        val sos = SosPacket(
            senderId = localDeviceName, batteryLevel = battery,
            priority = priority.label, emergencyType = type.name,
            severity = if (priority == SosPriority.MEDIUM) "MEDIUM" else "CRITICAL",
            latitude = lat, longitude = lng, ttl = 5, hopCount = 0,
            relayPath = listOf(localDeviceName),
            messageText = when (type) {
                EmergencyType.LOST_ASSISTANCE -> "ASSISTANCE NEEDED: User is lost / needs rescue."
                EmergencyType.EMERGENCY       -> "CRITICAL EMERGENCY: Immediate help needed!"
            }
        )
        meshRouter.registerLocalSos(sos)
        priorityQueue.enqueue(sos)
        MeshRepository.updateRelayQueue(priorityQueue.getAll())
        MeshRepository.addLog("🚨 SOS ${sos.messageId} | Priority: ${priority.label} | Battery: $battery%")

        if (MeshRepository.isGatewayModeEnabled.value && GatewayUploader.hasInternetConnection(this)) {
            sos.gatewayId = localDeviceName
            GatewayUploader.uploadSos(MeshRepository.serverUrl.value, sos,
                onSuccess = { MeshRepository.addLog("✅ Cloud Upload OK") },
                onError   = { e -> MeshRepository.addLog("❌ Cloud Upload Failed: $e") }
            )
        }
        broadcastToPeers(sos)
    }

    private fun triggerEmergencyMessage(text: String, lat: Double, lng: Double) {
        val battery = getBatteryPercentage()
        val priority = SosPriority.HIGH
        val sos = SosPacket(
            senderId = localDeviceName,
            batteryLevel = battery,
            priority = priority.label,
            emergencyType = EmergencyType.EMERGENCY.name,
            severity = "CRITICAL",
            latitude = lat,
            longitude = lng,
            ttl = 5,
            hopCount = 0,
            relayPath = listOf(localDeviceName),
            messageText = text,
            packetType = "MESSAGE"
        )
        meshRouter.registerLocalSos(sos)
        priorityQueue.enqueue(sos)
        MeshRepository.updateRelayQueue(priorityQueue.getAll())
        MeshRepository.addLog("💬 MSG ${sos.messageId} | Priority: ${priority.label} | Battery: $battery%")

        if (MeshRepository.isGatewayModeEnabled.value && GatewayUploader.hasInternetConnection(this)) {
            sos.gatewayId = localDeviceName
            GatewayUploader.uploadSos(MeshRepository.serverUrl.value, sos,
                onSuccess = { MeshRepository.addLog("✅ Cloud Upload OK") },
                onError   = { e -> MeshRepository.addLog("❌ Cloud Upload Failed: $e") }
            )
        }
        broadcastToPeers(sos)
    }

    private fun broadcastToPeers(packet: SosPacket, exclude: String? = null) {
        val payload = Payload.fromBytes(packet.toByteArray())
        val targets = connectedEndpoints.keys.filter { it != exclude }
        if (targets.isEmpty()) {
            MeshRepository.addLog("⚠️ No peers to forward to.")
            return
        }
        targets.forEach { connectionsClient.sendPayload(it, payload) }
        totalRelayedCount++
        MeshRepository.addLog("🚀 Relayed ${packet.messageId} ➔ ${targets.size} peer(s)")
        updateNotification("Active Peers: ${connectedEndpoints.size} | Relayed: $totalRelayedCount")
    }

    // ── Safe Connection Request Helper ──────────────────────────────────
    private fun connectToEndpoint(endpointId: String) {
        if (connectedEndpoints.containsKey(endpointId) || connectingEndpoints.contains(endpointId)) {
            return
        }
        connectingEndpoints.add(endpointId)
        MeshRepository.updateConnectingEndpoints(connectingEndpoints.toSet())
        val peerName = discoveredEndpoints[endpointId] ?: endpointId
        MeshRepository.addLog("🤝 Requesting connection to $peerName...")
        connectionsClient.requestConnection(localDeviceName, endpointId, connectionLifecycleCallback)
            .addOnFailureListener { e ->
                connectingEndpoints.remove(endpointId)
                MeshRepository.updateConnectingEndpoints(connectingEndpoints.toSet())
                MeshRepository.addLog("⚠️ Connection request to $peerName failed: ${e.message}")
            }
    }

    // ── Nearby Callbacks ────────────────────────────────────────────────
    private val payloadCallback: PayloadCallback = object : PayloadCallback() {
        override fun onPayloadReceived(endpointId: String, payload: Payload) {
            if (payload.type != Payload.Type.BYTES) return
            val bytes = payload.asBytes() ?: return
            val hasInternet = MeshRepository.isGatewayModeEnabled.value &&
                              GatewayUploader.hasInternetConnection(this@MeshForegroundService)
            val (_, decision) = meshRouter.onReceivePayload(bytes, endpointId, hasInternet)
            when (decision) {
                is MeshDecision.InvalidPayload    -> {}
                is MeshDecision.DroppedDuplicate  -> MeshRepository.addLog("🛡️ Duplicate dropped (loop prevented)")
                is MeshDecision.DroppedTtlExpired -> MeshRepository.addLog("⏳ TTL expired")
                is MeshDecision.ProcessAndRelay   -> {
                    val orig = decision.originalPacket
                    MeshRepository.setLastReceivedSos(orig)
                    priorityQueue.enqueue(orig)
                    MeshRepository.updateRelayQueue(priorityQueue.getAll())
                    MeshRepository.addLog("🚨 RX SOS from ${orig.senderId} (hops: ${orig.hopCount})")
                    if (decision.shouldUploadToGateway) {
                        orig.gatewayId = localDeviceName
                        GatewayUploader.uploadSos(MeshRepository.serverUrl.value, orig,
                            onSuccess = { MeshRepository.addLog("✅ Relayed to Cloud") },
                            onError   = { e -> MeshRepository.addLog("❌ Cloud error: $e") }
                        )
                    }
                    decision.packetToForward?.let { broadcastToPeers(it, exclude = endpointId) }
                }
            }
        }
        override fun onPayloadTransferUpdate(id: String, update: PayloadTransferUpdate) {}
    }

    private val connectionLifecycleCallback: ConnectionLifecycleCallback = object : ConnectionLifecycleCallback() {
        override fun onConnectionInitiated(endpointId: String, info: ConnectionInfo) {
            discoveredEndpoints[endpointId] = info.endpointName
            connectingEndpoints.add(endpointId)
            MeshRepository.updateConnectingEndpoints(connectingEndpoints.toSet())
            MeshRepository.addLog("🤝 Handshake initiated with ${info.endpointName}")
            // Always accept the connection
            connectionsClient.acceptConnection(endpointId, payloadCallback)
        }

        override fun onConnectionResult(endpointId: String, result: ConnectionResolution) {
            connectingEndpoints.remove(endpointId)
            MeshRepository.updateConnectingEndpoints(connectingEndpoints.toSet())
            val statusCode = result.status.statusCode
            val isSuccess = result.status.isSuccess ||
                            statusCode == ConnectionsStatusCodes.STATUS_ALREADY_CONNECTED_TO_ENDPOINT

            if (isSuccess) {
                val name = discoveredEndpoints[endpointId] ?: "Device-${endpointId.take(4).uppercase()}"
                connectedEndpoints[endpointId] = name
                MeshRepository.updateConnectedEndpoints(connectedEndpoints.toMap())
                MeshRepository.addLog("🔗 Connected to $name! Active Peers: ${connectedEndpoints.size}")
                updateNotification("Active Peers: ${connectedEndpoints.size} | Relayed: $totalRelayedCount")
            } else {
                val name = discoveredEndpoints[endpointId] ?: endpointId
                MeshRepository.addLog("❌ Connection with $name failed ($statusCode)")
                // Staggered retry: initiator retries in 4s, receiver in 8s
                val isInitiator = localDeviceName < name
                val retryDelay = if (isInitiator) 4000L else 8000L
                handler.postDelayed({
                    if (discoveredEndpoints.containsKey(endpointId) &&
                        !connectedEndpoints.containsKey(endpointId)) {
                        connectToEndpoint(endpointId)
                    }
                }, retryDelay)
            }
        }

        override fun onDisconnected(endpointId: String) {
            val name = connectedEndpoints.remove(endpointId) ?: discoveredEndpoints[endpointId] ?: endpointId
            connectingEndpoints.remove(endpointId)
            MeshRepository.updateConnectedEndpoints(connectedEndpoints.toMap())
            MeshRepository.updateConnectingEndpoints(connectingEndpoints.toSet())
            MeshRepository.addLog("🔌 $name disconnected. Active Peers: ${connectedEndpoints.size}")
            updateNotification("Active Peers: ${connectedEndpoints.size} | Relayed: $totalRelayedCount")

            // Auto-reconnect after 4s (initiator) or 8s (receiver)
            val isInitiator = localDeviceName < name
            val retryDelay = if (isInitiator) 4000L else 8000L
            handler.postDelayed({
                if (discoveredEndpoints.containsKey(endpointId) &&
                    !connectedEndpoints.containsKey(endpointId)) {
                    connectToEndpoint(endpointId)
                }
            }, retryDelay)
        }
    }

    private val endpointDiscoveryCallback: EndpointDiscoveryCallback = object : EndpointDiscoveryCallback() {
        override fun onEndpointFound(endpointId: String, info: DiscoveredEndpointInfo) {
            if (info.serviceId != SERVICE_ID) return
            discoveredEndpoints[endpointId] = info.endpointName

            if (connectedEndpoints.containsKey(endpointId) || connectingEndpoints.contains(endpointId)) {
                return
            }

            val shouldInitiate = localDeviceName < info.endpointName
            if (shouldInitiate) {
                // Initiator connects immediately
                MeshRepository.addLog("📡 Discovered ${info.endpointName} — Initiating connection...")
                connectToEndpoint(endpointId)
            } else {
                // Follower waits for incoming handshake, with a fallback timer
                MeshRepository.addLog("👀 Discovered ${info.endpointName} — Waiting for peer handshake...")
                handler.postDelayed({
                    if (!connectedEndpoints.containsKey(endpointId) &&
                        !connectingEndpoints.contains(endpointId) &&
                        discoveredEndpoints.containsKey(endpointId)) {
                        MeshRepository.addLog("⏱️ Fallback: Initiating connection to ${info.endpointName}...")
                        connectToEndpoint(endpointId)
                    }
                }, FALLBACK_INITIATE_DELAY_MS)
            }
        }

        override fun onEndpointLost(endpointId: String) {
            val name = discoveredEndpoints[endpointId] ?: endpointId
            MeshRepository.addLog("📡 Peer $name lost from discovery range")
        }
    }

    // ── Notification & WakeLock ─────────────────────────────────────────
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(
                CHANNEL_ID, "ResQMesh Background Relay",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Keeps offline SOS mesh active in background"
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
        }
    }

    private fun buildNotification(status: String): Notification {
        val openIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).apply { flags = Intent.FLAG_ACTIVITY_SINGLE_TOP },
            immutableFlag()
        )
        val stopIntent = PendingIntent.getService(
            this, 2,
            Intent(this, MeshForegroundService::class.java).apply { action = ACTION_STOP_MESH },
            immutableFlag()
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("🛡️ ResQMesh Background Relay")
            .setContentText(status)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setOngoing(true).setContentIntent(openIntent)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop Mesh", stopIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW).build()
    }

    private fun updateNotification(status: String) {
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .notify(NOTIFICATION_ID, buildNotification(status))
    }

    private fun acquireWakeLock() {
        if (wakeLock == null) {
            wakeLock = (getSystemService(Context.POWER_SERVICE) as PowerManager)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ResQMesh:WakeLock")
            wakeLock?.acquire(24 * 60 * 60 * 1000L)
        }
    }

    private fun releaseWakeLock() {
        try { if (wakeLock?.isHeld == true) wakeLock?.release() } catch (_: Exception) {}
        wakeLock = null
    }

    private fun getBatteryPercentage() = try {
        (getSystemService(Context.BATTERY_SERVICE) as? android.os.BatteryManager)
            ?.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: 85
    } catch (_: Exception) { 85 }

    private fun immutableFlag() =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0

    // ── Static Helpers ──────────────────────────────────────────────────
    companion object {
        const val ACTION_START_MESH        = "com.example.meshtest.START_MESH"
        const val ACTION_STOP_MESH         = "com.example.meshtest.STOP_MESH"
        const val ACTION_BROADCAST_SOS     = "com.example.meshtest.BROADCAST_SOS"
        const val ACTION_BROADCAST_MESSAGE = "com.example.meshtest.BROADCAST_MESSAGE"
        const val EXTRA_EMERGENCY_TYPE     = "extra_emergency_type"
        const val EXTRA_CUSTOM_MESSAGE     = "extra_custom_message"
        const val EXTRA_LATITUDE           = "extra_latitude"
        const val EXTRA_LONGITUDE          = "extra_longitude"

        fun startService(context: Context) {
            val i = Intent(context, MeshForegroundService::class.java).apply { action = ACTION_START_MESH }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(i)
            else context.startService(i)
        }

        fun stopService(context: Context) {
            context.startService(Intent(context, MeshForegroundService::class.java).apply { action = ACTION_STOP_MESH })
        }

        fun triggerSos(context: Context, type: EmergencyType, lat: Double, lng: Double) {
            val i = Intent(context, MeshForegroundService::class.java).apply {
                action = ACTION_BROADCAST_SOS
                putExtra(EXTRA_EMERGENCY_TYPE, type.name)
                putExtra(EXTRA_LATITUDE, lat); putExtra(EXTRA_LONGITUDE, lng)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(i)
            else context.startService(i)
        }

        fun triggerEmergencyMessage(context: Context, text: String, lat: Double, lng: Double) {
            val i = Intent(context, MeshForegroundService::class.java).apply {
                action = ACTION_BROADCAST_MESSAGE
                putExtra(EXTRA_CUSTOM_MESSAGE, text)
                putExtra(EXTRA_LATITUDE, lat); putExtra(EXTRA_LONGITUDE, lng)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(i)
            else context.startService(i)
        }
    }
}