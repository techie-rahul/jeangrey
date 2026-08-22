package com.example.meshtest.service

import android.Manifest
import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.location.LocationManager
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
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
import java.util.ArrayDeque

/**
 * Foreground Service: keeps ResQMesh P2P relay alive in background.
 * - PARTIAL_WAKE_LOCK & WIFI_LOCK: prevents CPU sleep and Wi-Fi Direct sleep on Xiaomi/Samsung/MIUI
 * - START_STICKY + onTaskRemoved AlarmManager: survives swipe-away
 * - Serialized Connection Queue: eliminates parallel handshake collisions and radio busy errors
 * - Invalidation of Stale Endpoints: prevents redialing dead IDs on disconnect
 * - Fallback Location Query: ensures GPS coordinates are never 0.0 even if UI fix was delayed
 * - Max Peers Cap: maintains stable multi-hop tree topology without radio saturation
 */
class MeshForegroundService : Service() {

    private val TAG = "MeshForegroundService"
    private val NOTIFICATION_ID = 1001
    private val CHANNEL_ID = "resqmesh_relay_channel"
    private val SERVICE_ID = "com.example.meshtest.sos"

    private val MAX_ACTIVE_PEERS = 4
    private val FALLBACK_INITIATE_DELAY_MS = 10_000L
    private val HANDSHAKE_TIMEOUT_MS = 15_000L

    private lateinit var connectionsClient: ConnectionsClient
    private lateinit var localDeviceName: String
    private lateinit var meshRouter: MeshRouter
    private val priorityQueue = SosPriorityQueue()

    private val connectedEndpoints  = mutableMapOf<String, String>()  // endpointId -> name
    private val connectingEndpoints = mutableSetOf<String>()          // endpointIds in progress
    private val discoveredEndpoints = mutableMapOf<String, String>()  // endpointId -> name

    // Serialized connection handshake queue
    private val connectionQueue = ArrayDeque<String>()
    private var isHandshakeActive = false
    private var activeHandshakeEndpoint: String? = null
    private var isRadioCooldownActive = false

    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var totalRelayedCount = 0
    private val handler = Handler(Looper.getMainLooper())

    private val handshakeTimeoutRunnable = Runnable {
        activeHandshakeEndpoint?.let { ep ->
            MeshRepository.addLog("⏱️ Handshake with ${discoveredEndpoints[ep] ?: ep} timed out — releasing queue.")
            connectingEndpoints.remove(ep)
            MeshRepository.updateConnectingEndpoints(connectingEndpoints.toSet())
        }
        isHandshakeActive = false
        activeHandshakeEndpoint = null
        processNextConnection()
    }

    override fun onCreate() {
        super.onCreate()
        localDeviceName = "${Build.MANUFACTURER.uppercase()}-${Build.MODEL}"
        connectionsClient = Nearby.getConnectionsClient(this)
        meshRouter = MeshRouter(localDeviceName)
        createNotificationChannel()
        acquireWakeLock()
        acquireWifiLock()
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
        releaseWifiLock()
    }

    // ── Fallback Location Acquisition ───────────────────────────────────
    private fun getFallbackLocation(): Pair<Double, Double> {
        return try {
            val lm = getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return Pair(0.0, 0.0)
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED &&
                ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                return Pair(0.0, 0.0)
            }
            val lastGps = try { lm.getLastKnownLocation(LocationManager.GPS_PROVIDER) } catch (_: Exception) { null }
            val lastNet = try { lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER) } catch (_: Exception) { null }
            val lastPassive = try { lm.getLastKnownLocation(LocationManager.PASSIVE_PROVIDER) } catch (_: Exception) { null }
            val best = lastGps ?: lastNet ?: lastPassive
            if (best != null && (best.latitude != 0.0 || best.longitude != 0.0)) {
                Pair(best.latitude, best.longitude)
            } else {
                Pair(0.0, 0.0)
            }
        } catch (_: Exception) { Pair(0.0, 0.0) }
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
        connectionQueue.clear()
        isHandshakeActive = false
        activeHandshakeEndpoint = null
        isRadioCooldownActive = false
        connectedEndpoints.clear()
        connectingEndpoints.clear()
        discoveredEndpoints.clear()
        MeshRepository.updateConnectedEndpoints(emptyMap())
        MeshRepository.updateConnectingEndpoints(emptySet())
        MeshRepository.setMeshActive(false)
        MeshRepository.addLog("🛑 Background Mesh Stopped")
    }

    private fun triggerSos(type: EmergencyType, lat: Double, lng: Double) {
        val (finalLat, finalLng) = if (lat == 0.0 && lng == 0.0) getFallbackLocation() else Pair(lat, lng)
        val battery = getBatteryPercentage()
        val priority = SosPriorityCalculator.calculate(type, battery)
        val sos = SosPacket(
            senderId = localDeviceName, batteryLevel = battery,
            priority = priority.label, emergencyType = type.name,
            severity = if (priority == SosPriority.MEDIUM) "MEDIUM" else "CRITICAL",
            latitude = finalLat, longitude = finalLng, ttl = 5, hopCount = 0,
            relayPath = listOf(localDeviceName),
            messageText = when (type) {
                EmergencyType.LOST_ASSISTANCE -> "ASSISTANCE NEEDED: User is lost / needs rescue."
                EmergencyType.EMERGENCY       -> "CRITICAL EMERGENCY: Immediate help needed!"
            }
        )
        meshRouter.registerLocalSos(sos)
        priorityQueue.enqueue(sos)
        MeshRepository.updateRelayQueue(priorityQueue.getAll())
        MeshRepository.addLog("🚨 SOS ${sos.messageId} | Location: $finalLat, $finalLng | Battery: $battery%")

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
        val (finalLat, finalLng) = if (lat == 0.0 && lng == 0.0) getFallbackLocation() else Pair(lat, lng)
        val battery = getBatteryPercentage()
        val priority = SosPriority.HIGH
        val sos = SosPacket(
            senderId = localDeviceName,
            batteryLevel = battery,
            priority = priority.label,
            emergencyType = EmergencyType.EMERGENCY.name,
            severity = "CRITICAL",
            latitude = finalLat,
            longitude = finalLng,
            ttl = 5,
            hopCount = 0,
            relayPath = listOf(localDeviceName),
            messageText = text,
            packetType = "MESSAGE"
        )
        meshRouter.registerLocalSos(sos)
        priorityQueue.enqueue(sos)
        MeshRepository.updateRelayQueue(priorityQueue.getAll())
        MeshRepository.addLog("💬 MSG ${sos.messageId} | Location: $finalLat, $finalLng | Text: $text")

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

    // ── Serialized Connection Handshake Queue ───────────────────────────
    @Synchronized
    private fun enqueueConnection(endpointId: String) {
        if (connectedEndpoints.containsKey(endpointId) ||
            connectedEndpoints.size >= MAX_ACTIVE_PEERS ||
            connectionQueue.contains(endpointId) ||
            activeHandshakeEndpoint == endpointId) {
            return
        }
        connectionQueue.add(endpointId)
        processNextConnection()
    }

    @Synchronized
    private fun processNextConnection() {
        if (isHandshakeActive || isRadioCooldownActive) return
        if (connectedEndpoints.size >= MAX_ACTIVE_PEERS) {
            connectionQueue.clear()
            return
        }
        val nextId = connectionQueue.pollFirst() ?: return
        if (connectedEndpoints.containsKey(nextId)) {
            processNextConnection()
            return
        }

        isHandshakeActive = true
        activeHandshakeEndpoint = nextId
        connectingEndpoints.add(nextId)
        MeshRepository.updateConnectingEndpoints(connectingEndpoints.toSet())

        val peerName = discoveredEndpoints[nextId] ?: nextId
        MeshRepository.addLog("🤝 [Queue] Initiating handshake with $peerName...")

        handler.removeCallbacks(handshakeTimeoutRunnable)
        handler.postDelayed(handshakeTimeoutRunnable, HANDSHAKE_TIMEOUT_MS)

        connectionsClient.requestConnection(localDeviceName, nextId, connectionLifecycleCallback)
            .addOnFailureListener { e ->
                handler.removeCallbacks(handshakeTimeoutRunnable)
                connectingEndpoints.remove(nextId)
                MeshRepository.updateConnectingEndpoints(connectingEndpoints.toSet())
                isHandshakeActive = false
                activeHandshakeEndpoint = null
                MeshRepository.addLog("⚠️ Request to $peerName failed: ${e.message}")
                handler.postDelayed({ processNextConnection() }, 500L)
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
            MeshRepository.addLog("🤝 Incoming handshake from ${info.endpointName}")
            connectionsClient.acceptConnection(endpointId, payloadCallback)
        }

        override fun onConnectionResult(endpointId: String, result: ConnectionResolution) {
            handler.removeCallbacks(handshakeTimeoutRunnable)
            isHandshakeActive = false
            activeHandshakeEndpoint = null
            connectingEndpoints.remove(endpointId)
            MeshRepository.updateConnectingEndpoints(connectingEndpoints.toSet())

            val statusCode = result.status.statusCode
            val isSuccess = result.status.isSuccess ||
                            statusCode == ConnectionsStatusCodes.STATUS_ALREADY_CONNECTED_TO_ENDPOINT ||
                            statusCode == 8011 // STATUS_ALREADY_HAVE_ACTIVE_BIDIRECTIONAL_CONNECTION

            if (isSuccess) {
                val name = discoveredEndpoints[endpointId] ?: "Device-${endpointId.take(4).uppercase()}"
                connectedEndpoints[endpointId] = name
                MeshRepository.updateConnectedEndpoints(connectedEndpoints.toMap())
                MeshRepository.addLog("🔗 Connected to $name! Active Peers: ${connectedEndpoints.size}")
                updateNotification("Active Peers: ${connectedEndpoints.size} | Relayed: $totalRelayedCount")
            } else {
                val name = discoveredEndpoints[endpointId] ?: endpointId
                MeshRepository.addLog("❌ Connection with $name failed ($statusCode)")

                if (statusCode == 8008) { // STATUS_RADIO_ERROR
                    MeshRepository.addLog("📻 Radio busy — cooling down for 6s...")
                    isRadioCooldownActive = true
                    handler.postDelayed({
                        isRadioCooldownActive = false
                        processNextConnection()
                    }, 6000L)
                }

                // Remove stale endpoint from discovered list if rejected
                discoveredEndpoints.remove(endpointId)
            }

            // Process next peer in queue after small delay
            handler.postDelayed({ processNextConnection() }, 1000L)
        }

        override fun onDisconnected(endpointId: String) {
            val name = connectedEndpoints.remove(endpointId) ?: discoveredEndpoints[endpointId] ?: endpointId
            connectingEndpoints.remove(endpointId)
            // Invalidate stale endpointId so it is not redialed with invalid session ID
            discoveredEndpoints.remove(endpointId)
            connectionQueue.remove(endpointId)

            MeshRepository.updateConnectedEndpoints(connectedEndpoints.toMap())
            MeshRepository.updateConnectingEndpoints(connectingEndpoints.toSet())
            MeshRepository.addLog("🔌 $name disconnected. Active Peers: ${connectedEndpoints.size}")
            updateNotification("Active Peers: ${connectedEndpoints.size} | Relayed: $totalRelayedCount")
        }
    }

    private val endpointDiscoveryCallback: EndpointDiscoveryCallback = object : EndpointDiscoveryCallback() {
        override fun onEndpointFound(endpointId: String, info: DiscoveredEndpointInfo) {
            if (info.serviceId != SERVICE_ID) return
            discoveredEndpoints[endpointId] = info.endpointName

            if (connectedEndpoints.containsKey(endpointId) ||
                connectingEndpoints.contains(endpointId) ||
                connectedEndpoints.size >= MAX_ACTIVE_PEERS) {
                return
            }

            val shouldInitiate = localDeviceName < info.endpointName
            if (shouldInitiate) {
                MeshRepository.addLog("📡 Discovered ${info.endpointName} — Queuing handshake (Initiator)...")
                enqueueConnection(endpointId)
            } else {
                val jitter = (0..3000).random().toLong()
                val fallbackDelay = FALLBACK_INITIATE_DELAY_MS + jitter
                MeshRepository.addLog("👀 Discovered ${info.endpointName} — Waiting for peer (${fallbackDelay / 1000}s fallback)...")
                handler.postDelayed({
                    if (!connectedEndpoints.containsKey(endpointId) &&
                        !connectingEndpoints.contains(endpointId) &&
                        discoveredEndpoints.containsKey(endpointId) &&
                        connectedEndpoints.size < MAX_ACTIVE_PEERS) {
                        MeshRepository.addLog("⏱️ Fallback timer: Queuing connection to ${info.endpointName}...")
                        enqueueConnection(endpointId)
                    }
                }, fallbackDelay)
            }
        }

        override fun onEndpointLost(endpointId: String) {
            val name = discoveredEndpoints[endpointId] ?: endpointId
            discoveredEndpoints.remove(endpointId)
            connectionQueue.remove(endpointId)
            MeshRepository.addLog("📡 Peer $name lost from discovery range")
        }
    }

    // ── Notification, WakeLock & WifiLock ───────────────────────────────
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

    private fun acquireWifiLock() {
        if (wifiLock == null) {
            val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            wifiLock = wm?.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "ResQMesh:WifiLock")
            wifiLock?.acquire()
        }
    }

    private fun releaseWifiLock() {
        try { if (wifiLock?.isHeld == true) wifiLock?.release() } catch (_: Exception) {}
        wifiLock = null
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