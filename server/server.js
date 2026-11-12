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

// ---------------- REST API ROUTES ----------------

// 0. Cloud Gateway Status & Information Landing Page
app.get('/', (req, res) => {
  // If client explicitly requests JSON (e.g. curl or API tool)
  if (
    req.query.format === 'json' ||
    (req.headers.accept &&
      req.headers.accept.includes('application/json') &&
      !req.headers.accept.includes('text/html'))
  ) {
    return res.json({
      service: 'ResQMesh Emergency Cloud Gateway API',
      status: 'ONLINE',
      version: '1.0.0',
      dashboardUrl: 'https://jeangrey.vercel.app/',
      uptimeSeconds: Math.floor(process.uptime()),
      totalAlerts: sosAlerts.length,
      activeAlerts: sosAlerts.filter(a => a.status === 'ACTIVE').length,
      endpoints: {
        health: 'GET /health',
        sosIngest: 'POST /api/sos',
        sosList: 'GET /api/sos',
        stats: 'GET /api/stats',
        simulate: 'POST /api/sos/simulate'
      },
      websocket: {
        status: 'READY',
        clientsConnected: io.engine ? io.engine.clientsCount : 0
      }
    });
  }

  // Otherwise, serve professional branded HTML Gateway landing page
  const total = sosAlerts.length;
  const active = sosAlerts.filter(a => a.status === 'ACTIVE').length;
  const wsClients = io.engine ? io.engine.clientsCount : 0;
  const uptime = Math.floor(process.uptime());

  res.send(`<!DOCTYPE html>
<html lang="en">
<head>
  <meta charset="UTF-8">
  <meta name="viewport" content="width=device-width, initial-scale=1.0">
  <title>ResQMesh — Emergency Cloud Gateway API</title>
  <link rel="icon" type="image/svg+xml" href="data:image/svg+xml,<svg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 24 24' fill='%23EF4444'><path d='M12 2L1 21h22L12 2zm0 3.99L19.53 19H4.47L12 5.99zM11 10v4h2v-4h-2zm0 6v2h2v-2h-2z'/></svg>">
  <style>
    :root {
      --bg: #0b0f19;
      --card-bg: #111827;
      --card-border: #1f2937;
      --text-primary: #f9fafb;
      --text-secondary: #9ca3af;
      --accent-red: #ef4444;
      --accent-green: #10b981;
      --accent-blue: #3b82f6;
    }
    * { box-sizing: border-box; margin: 0; padding: 0; }
    body {
      font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, "Helvetica Neue", Arial, sans-serif;
      background-color: var(--bg);
      color: var(--text-primary);
      min-height: 100vh;
      display: flex;
      flex-direction: column;
      align-items: center;
      justify-content: center;
      padding: 1.5rem;
    }
    .container {
      max-width: 760px;
      width: 100%;
      background: var(--card-bg);
      border: 1px solid var(--card-border);
      border-radius: 16px;
      padding: 2.25rem;
      box-shadow: 0 20px 25px -5px rgba(0, 0, 0, 0.5), 0 8px 10px -6px rgba(0, 0, 0, 0.5);
    }
    .header {
      display: flex;
      align-items: center;
      justify-content: space-between;
      flex-wrap: wrap;
      gap: 1rem;
      border-bottom: 1px solid var(--card-border);
      padding-bottom: 1.25rem;
      margin-bottom: 1.25rem;
    }
    .brand {
      display: flex;
      align-items: center;
      gap: 0.75rem;
    }
    .logo-icon {
      font-size: 2rem;
    }
    h1 {
      font-size: 1.45rem;
      font-weight: 700;
      letter-spacing: -0.025em;
    }
    .badge {
      display: inline-flex;
      align-items: center;
      gap: 0.5rem;
      background: rgba(16, 185, 129, 0.12);
      color: var(--accent-green);
      border: 1px solid rgba(16, 185, 129, 0.25);
      padding: 0.35rem 0.85rem;
      border-radius: 9999px;
      font-size: 0.8rem;
      font-weight: 600;
      letter-spacing: 0.05em;
      text-transform: uppercase;
    }
    .pulse-dot {
      width: 8px;
      height: 8px;
      background-color: var(--accent-green);
      border-radius: 50%;
      box-shadow: 0 0 8px var(--accent-green);
      animation: pulse 2s infinite;
    }
    @keyframes pulse {
      0%, 100% { opacity: 1; transform: scale(1); }
      50% { opacity: 0.4; transform: scale(0.85); }
    }
    .description {
      color: var(--text-secondary);
      line-height: 1.6;
      margin-bottom: 1.5rem;
      font-size: 0.95rem;
    }
    .cta-banner {
      background: linear-gradient(135deg, rgba(239, 68, 68, 0.12) 0%, rgba(59, 130, 246, 0.12) 100%);
      border: 1px solid rgba(239, 68, 68, 0.3);
      border-radius: 12px;
      padding: 1.25rem 1.5rem;
      display: flex;
      align-items: center;
      justify-content: space-between;
      flex-wrap: wrap;
      gap: 1rem;
      margin-bottom: 1.75rem;
    }
    .cta-text {
      display: flex;
      flex-direction: column;
      gap: 0.25rem;
    }
    .cta-text strong {
      font-size: 1rem;
      color: #fff;
    }
    .cta-text span {
      font-size: 0.85rem;
      color: var(--text-secondary);
    }
    .btn {
      display: inline-flex;
      align-items: center;
      gap: 0.5rem;
      background: var(--accent-red);
      color: white;
      text-decoration: none;
      font-weight: 600;
      font-size: 0.925rem;
      padding: 0.75rem 1.5rem;
      border-radius: 8px;
      transition: all 0.2s ease;
      box-shadow: 0 4px 12px rgba(239, 68, 68, 0.3);
    }
    .btn:hover {
      background: #dc2626;
      transform: translateY(-1px);
      box-shadow: 0 6px 16px rgba(239, 68, 68, 0.4);
    }
    .grid {
      display: grid;
      grid-template-columns: repeat(auto-fit, minmax(140px, 1fr));
      gap: 0.75rem;
      margin-bottom: 1.75rem;
    }
    .stat-card {
      background: #1a2234;
      border: 1px solid var(--card-border);
      border-radius: 10px;
      padding: 0.85rem 1rem;
    }
    .stat-label {
      font-size: 0.725rem;
      color: var(--text-secondary);
      text-transform: uppercase;
      letter-spacing: 0.05em;
      margin-bottom: 0.25rem;
    }
    .stat-val {
      font-size: 1.25rem;
      font-weight: 700;
      color: var(--text-primary);
    }
    .section-title {
      font-size: 0.8rem;
      text-transform: uppercase;
      letter-spacing: 0.05em;
      color: var(--text-secondary);
      margin-bottom: 0.6rem;
      font-weight: 600;
    }
    .endpoints {
      background: #0f172a;
      border: 1px solid var(--card-border);
      border-radius: 10px;
      overflow: hidden;
      font-family: ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, monospace;
      font-size: 0.825rem;
    }
    .endpoint-row {
      display: flex;
      align-items: center;
      justify-content: space-between;
      padding: 0.65rem 1rem;
      border-bottom: 1px solid var(--card-border);
    }
    .endpoint-row:last-child {
      border-bottom: none;
    }
    .method {
      display: inline-block;
      width: 55px;
      font-weight: 700;
      font-size: 0.75rem;
    }
    .post { color: #f59e0b; }
    .get { color: #10b981; }
    .delete { color: #ef4444; }
    .patch { color: #8b5cf6; }
    .ws { color: #38bdf8; }
    .path { color: #e2e8f0; }
    .desc { color: #64748b; font-size: 0.8rem; font-family: -apple-system, sans-serif; }
    .footer {
      margin-top: 1.5rem;
      text-align: center;
      font-size: 0.8rem;
      color: #64748b;
    }
    .footer a {
      color: var(--text-secondary);
      text-decoration: underline;
    }
  </style>
</head>
<body>
  <div class="container">
    <div class="header">
      <div class="brand">
        <span class="logo-icon">🛡️</span>
        <div>
          <h1>ResQMesh Cloud Gateway</h1>
          <p style="font-size: 0.8rem; color: var(--text-secondary);">Emergency SOS Relay API &amp; WebSocket Server</p>
        </div>
      </div>
      <div class="badge">
        <span class="pulse-dot"></span>
        Gateway Operational
      </div>
    </div>

    <p class="description">
      This service acts as the central ingestion point and WebSocket broadcast bridge for the 
      <strong>ResQMesh Offline Emergency Network</strong>. Android mesh gateways automatically upload hop-relayed 
      SOS packets here, which are streamed in real time to emergency responders.
    </p>

    <div class="cta-banner">
      <div class="cta-text">
        <strong>🗺️ Looking for the Disaster Command Center?</strong>
        <span>The interactive live map dashboard is deployed on Vercel.</span>
      </div>
      <a href="https://jeangrey.vercel.app/" class="btn" target="_blank" rel="noopener noreferrer">
        Open Control Dashboard &rarr;
      </a>
    </div>

    <div class="section-title">Gateway Telemetry</div>
    <div class="grid">
      <div class="stat-card">
        <div class="stat-label">Total Emergencies</div>
        <div class="stat-val">${total}</div>
      </div>
      <div class="stat-card">
        <div class="stat-label">Active Incidents</div>
        <div class="stat-val" style="color: var(--accent-red);">${active}</div>
      </div>
      <div class="stat-card">
        <div class="stat-label">WebSocket Status</div>
        <div class="stat-val" style="color: var(--accent-green); font-size: 1.1rem; padding-top: 0.1rem;">READY</div>
      </div>
      <div class="stat-card">
        <div class="stat-label">Connected Clients</div>
        <div class="stat-val">${wsClients}</div>
      </div>
    </div>

    <div class="section-title">Available API Endpoints</div>
    <div class="endpoints">
      <div class="endpoint-row">
        <div><span class="method post">POST</span> <span class="path">/api/sos</span></div>
        <span class="desc">Ingest emergency SOS payload from Android mesh gateway</span>
      </div>
      <div class="endpoint-row">
        <div><span class="method get">GET</span> <span class="path">/api/sos</span></div>
        <span class="desc">Retrieve all stored emergency incidents</span>
      </div>
      <div class="endpoint-row">
        <div><span class="method get">GET</span> <span class="path">/api/stats</span></div>
        <span class="desc">Aggregate mesh telemetry and hop statistics</span>
      </div>
      <div class="endpoint-row">
        <div><span class="method post">POST</span> <span class="path">/api/sos/simulate</span></div>
        <span class="desc">Inject simulated incident for live drill testing</span>
      </div>
      <div class="endpoint-row">
        <div><span class="method get">GET</span> <span class="path">/health</span></div>
        <span class="desc">Service health probe for uptime monitors</span>
      </div>
      <div class="endpoint-row">
        <div><span class="method ws">WS</span> <span class="path">/socket.io</span></div>
        <span class="desc">Live bidirectional stream for dashboard clients</span>
      </div>
    </div>

    <div class="footer">
      ResQMesh &bull; Track 6: Disaster Management &amp; Public Safety &bull;
      <a href="https://jeangrey.vercel.app/" target="_blank">Control Dashboard</a> &bull;
      <a href="/api/stats" target="_blank">View /api/stats</a>
    </div>
  </div>
</body>
</html>`);
});

// Health check endpoint for uptime monitors and Render health probes
app.get('/health', (req, res) => {
  res.status(200).json({
    status: 'OK',
    service: 'resqmesh-backend',
    uptime: Math.floor(process.uptime()),
    timestamp: new Date().toISOString()
  });
});

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

// Fallback 404 handler for undefined routes
app.use((req, res) => {
  if (req.accepts('html')) {
    res.status(404).send(`<!DOCTYPE html>
<html lang="en">
<head>
  <meta charset="utf-8">
  <title>404 Not Found - ResQMesh Gateway</title>
  <style>
    body { font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, sans-serif; background: #0b0f19; color: #f1f5f9; display: flex; align-items: center; justify-content: center; min-height: 100vh; margin: 0; }
    .card { background: #111827; padding: 2rem; border-radius: 12px; text-align: center; max-width: 480px; border: 1px solid #1f2937; }
    h2 { color: #ef4444; margin-bottom: 0.75rem; }
    p { color: #9ca3af; line-height: 1.5; margin-bottom: 1rem; }
    a { color: #38bdf8; text-decoration: none; font-weight: 600; }
    a:hover { text-decoration: underline; }
  </style>
</head>
<body>
  <div class="card">
    <h2>404 - Endpoint Not Found</h2>
    <p>The endpoint <code>${req.originalUrl}</code> does not exist on the ResQMesh Emergency Cloud Gateway API.</p>
    <p><a href="/">Return to Gateway Status Page</a> &bull; <a href="https://jeangrey.vercel.app/" target="_blank">Open Live Dashboard</a></p>
  </div>
</body>
</html>`);
  } else {
    res.status(404).json({
      error: 'Endpoint not found',
      path: req.originalUrl,
      dashboardUrl: 'https://jeangrey.vercel.app/'
    });
  }
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
