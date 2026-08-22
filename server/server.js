const express = require('express');
const http = require('http');
const { Server } = require('socket.io');
const cors = require('cors');
const morgan = require('morgan');
const fs = require('fs');
const path = require('path');

const app = express();
const server = http.createServer(app);
const io = new Server(server, {
  cors: {
    origin: '*',
    methods: ['GET', 'POST', 'PATCH', 'DELETE']
  }
});

const PORT = process.env.PORT || 5000;
const DATA_FILE = path.join(__dirname, 'sos_database.json');

// Middleware
app.use(cors());
app.use(express.json());
app.use(morgan('dev'));

// Local persistence helper
let sosAlerts = [];
try {
  if (fs.existsSync(DATA_FILE)) {
    sosAlerts = JSON.parse(fs.readFileSync(DATA_FILE, 'utf-8'));
  }
} catch (err) {
  console.error('Error reading database file, initializing empty store.', err);
  sosAlerts = [];
}

const saveAlerts = () => {
  try {
    fs.writeFileSync(DATA_FILE, JSON.stringify(sosAlerts, null, 2));
  } catch (err) {
    console.error('Failed to save alerts to file:', err);
  }
};

// ---------------- PER-VICTIM RATE LIMITER ----------------
// 10 emergency packets per original victim (senderId) per 60-second window.
// Relay devices and gateways do NOT consume quota — the key is always the
// original sender identity embedded inside the packet, not the uploading device.

const RATE_LIMIT_MAX = 10;          // max requests per window
const RATE_LIMIT_WINDOW_MS = 60000; // 60 seconds

// Map<senderId, number[]>  — stores timestamps of accepted requests
const rateLimitStore = new Map();

/**
 * Returns true if the request should be ALLOWED, false if rate-limited.
 * Automatically prunes expired timestamps on each call.
 */
function checkRateLimit(senderId) {
  const now = Date.now();
  let timestamps = rateLimitStore.get(senderId);

  if (!timestamps) {
    timestamps = [];
    rateLimitStore.set(senderId, timestamps);
  }

  // Prune entries older than the window
  while (timestamps.length > 0 && timestamps[0] <= now - RATE_LIMIT_WINDOW_MS) {
    timestamps.shift();
  }

  if (timestamps.length >= RATE_LIMIT_MAX) {
    return false; // rate-limited
  }

  timestamps.push(now);
  return true; // allowed
}

// Periodic cleanup of stale entries to prevent memory growth
setInterval(() => {
  const now = Date.now();
  for (const [key, timestamps] of rateLimitStore.entries()) {
    // Remove expired timestamps
    while (timestamps.length > 0 && timestamps[0] <= now - RATE_LIMIT_WINDOW_MS) {
      timestamps.shift();
    }
    // Remove empty entries
    if (timestamps.length === 0) {
      rateLimitStore.delete(key);
    }
  }
}, RATE_LIMIT_WINDOW_MS);

// ---------------- REST API ROUTES ----------------

// 1. Gateway Phone Uploads SOS
app.post('/api/sos', (req, res) => {
  const {
    messageId,
    senderId,
    timestamp,
    latitude,
    longitude,
    severity = 'CRITICAL',
    batteryLevel,
    ttl,
    hopCount,
    relayPath = [],
    gatewayId,
    packetType = 'SOS',
    messageText = 'EMERGENCY SOS: Immediate assistance required.'
  } = req.body;

  if (!messageId || !senderId) {
    return res.status(400).json({ error: 'Missing required fields: messageId, senderId' });
  }

  // Per-victim rate limit check (keyed by original senderId, NOT relay/gateway)
  if (!checkRateLimit(senderId)) {
    console.log(`🚫 [RATE LIMITED] Victim ${senderId} exceeded ${RATE_LIMIT_MAX} requests in ${RATE_LIMIT_WINDOW_MS / 1000}s window`);
    return res.status(429).json({
      error: 'Rate limit exceeded. Maximum 10 emergency requests per minute.'
    });
  }

  // Deduplication check on server
  const existingIndex = sosAlerts.findIndex(a => a.messageId === messageId);
  if (existingIndex !== -1) {
    const existing = sosAlerts[existingIndex];
    if (relayPath.length > existing.relayPath.length) {
      existing.relayPath = relayPath;
      existing.gatewayId = gatewayId || existing.gatewayId;
      saveAlerts();
      io.emit('update_sos', existing);
    }
    return res.status(200).json({ status: 'DUPLICATE_IGNORED', alert: existing });
  }

  // Preserve exact coordinates from the original sender — never silently replace with defaults
  const parsedLat = (latitude != null && !isNaN(parseFloat(latitude))) ? parseFloat(latitude) : null;
  const parsedLng = (longitude != null && !isNaN(parseFloat(longitude))) ? parseFloat(longitude) : null;

  console.log(`📍 SOS LOCATION RECEIVED: lat=${parsedLat}, lng=${parsedLng} (raw: lat=${latitude}, lng=${longitude})`);

  const newAlert = {
    messageId,
    senderId,
    timestamp: timestamp || Date.now(),
    receivedAt: Date.now(),
    latitude: parsedLat,
    longitude: parsedLng,
    severity,
    batteryLevel: batteryLevel !== undefined ? batteryLevel : null,
    ttl: ttl !== undefined ? ttl : 0,
    hopCount: hopCount || (relayPath.length > 0 ? relayPath.length - 1 : 0),
    relayPath: relayPath.length > 0 ? relayPath : [senderId],
    gatewayId: gatewayId || 'UNKNOWN_GATEWAY',
    packetType,
    messageText,
    status: 'ACTIVE'
  };

  sosAlerts.unshift(newAlert);
  saveAlerts();

  console.log(`🚨 [NEW EMERGENCY RECEIVED] ID: ${messageId} | Origin: ${senderId} | Hops: ${newAlert.hopCount} | Via Gateway: ${newAlert.gatewayId} | 📍 lat=${parsedLat}, lng=${parsedLng}`);

  // Broadcast to Live Web Dashboard via WebSocket
  io.emit('new_sos', newAlert);

  res.status(201).json({
    status: 'SUCCESS',
    message: 'SOS received and broadcasted to emergency dashboard',
    alert: newAlert
  });
});

// 2. Fetch all SOS alerts
app.get('/api/sos', (req, res) => {
  res.json({ count: sosAlerts.length, alerts: sosAlerts });
});

// 3. Clear all SOS alerts (Reset Database)
app.delete('/api/sos', (req, res) => {
  sosAlerts = [];
  saveAlerts();
  io.emit('clear_all_sos');
  console.log('🧹 [DATABASE CLEARED] All SOS alerts reset.');
  res.json({ status: 'SUCCESS', message: 'Database reset successfully' });
});

// 4. Update alert status
app.patch('/api/sos/:id/status', (req, res) => {
  const { id } = req.params;
  const { status } = req.body;

  const alert = sosAlerts.find(a => a.messageId === id);
  if (!alert) {
    return res.status(404).json({ error: 'Alert not found' });
  }

  alert.status = status || alert.status;
  saveAlerts();

  io.emit('update_sos', alert);
  res.json({ status: 'UPDATED', alert });
});

// 5. Statistics Endpoint
app.get('/api/stats', (req, res) => {
  const total = sosAlerts.length;
  const active = sosAlerts.filter(a => a.status === 'ACTIVE').length;
  const resolved = sosAlerts.filter(a => a.status === 'RESOLVED').length;
  const totalHops = sosAlerts.reduce((acc, curr) => acc + (curr.hopCount || 0), 0);
  const avgHops = total > 0 ? (totalHops / total).toFixed(1) : 0;
  const uniqueGateways = new Set(sosAlerts.map(a => a.gatewayId)).size;

  res.json({
    totalEmergencies: total,
    activeEmergencies: active,
    resolvedEmergencies: resolved,
    averageHops: avgHops,
    activeGateways: uniqueGateways
  });
});

// 6. Test Simulator Route
app.post('/api/sos/simulate', (req, res) => {
  const sampleLocations = [
    { lat: 26.9124, lng: 75.7873, area: 'Sector 4, Central Flood Zone' },
    { lat: 26.9200, lng: 75.8100, area: 'Hillside Trek Route km 12' },
    { lat: 26.8900, lng: 75.7600, area: 'Coastal Bridge Settlement' }
  ];
  const loc = sampleLocations[Math.floor(Math.random() * sampleLocations.length)];
  const randomId = 'SOS-' + Math.random().toString(36).substring(2, 8).toUpperCase();

  const mockPayload = {
    messageId: randomId,
    senderId: 'DEVICE-A-VICTIM',
    timestamp: Date.now(),
    latitude: loc.lat + (Math.random() - 0.5) * 0.01,
    longitude: loc.lng + (Math.random() - 0.5) * 0.01,
    severity: 'CRITICAL',
    batteryLevel: Math.floor(Math.random() * 40) + 10,
    ttl: 2,
    hopCount: 3,
    relayPath: ['DEVICE-A-VICTIM', 'DEVICE-B-RELAY', 'DEVICE-C-RELAY', 'DEVICE-D-GATEWAY'],
    gatewayId: 'DEVICE-D-GATEWAY',
    messageText: `Flash flood evacuation needed at ${loc.area} (Simulated Test Alert)`
  };

  const newAlert = {
    ...mockPayload,
    receivedAt: Date.now(),
    status: 'ACTIVE'
  };

  sosAlerts.unshift(newAlert);
  saveAlerts();
  io.emit('new_sos', newAlert);

  console.log(`⚡ [SIMULATED SOS INJECTED] ${randomId}`);
  res.json({ message: 'Simulated SOS created successfully', alert: newAlert });
});

// WebSocket Connection Management
io.on('connection', (socket) => {
  console.log(`🔌 Dashboard Client Connected: [${socket.id}]`);
  socket.emit('init_data', sosAlerts);

  socket.on('disconnect', () => {
    console.log(`❌ Dashboard Client Disconnected: [${socket.id}]`);
  });
});

// Start Server
server.listen(PORT, () => {
  console.log(`
=====================================================
🛡️  ResQMesh - Emergency Cloud Gateway API Running!
🌐  Server URL: http://localhost:${PORT}
📡  WebSocket Ready for Live Dashboard
🚨  SOS Ingest Endpoint: POST http://localhost:${PORT}/api/sos
=====================================================
  `);
});