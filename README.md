# 🛡️ ResQMesh — Offline Mesh SOS Emergency Relay

> **Track 6: Disaster Management & Public Safety**  
> **Problem 6.1: Offline Mesh SOS Relay for Signal-Dead Zones**
> 
> 🌐 **Live Cloud Gateway**: [https://jeangrey.onrender.com](https://jeangrey.onrender.com)  
> 🗺️ **Control Dashboard**: [https://jeangrey.onrender.com/api/sos](https://jeangrey.onrender.com/api/sos)

---

## 📌 Problem Overview
In major disasters (floods, earthquakes, cyclones), cellular towers and internet infrastructure are often completely destroyed or overwhelmed. Existing emergency SOS apps fail because they require an active internet connection.

**ResQMesh transforms standard smartphones into an ad-hoc, peer-to-peer emergency communication network.** An SOS payload hops phone-to-phone without internet until it reaches a device with active connectivity (Gateway node), which automatically uploads the incident to disaster response teams.

---

## ⚡ Key Architecture & Features

```
[📱 Victim Phone (Offline)] 
       │  (BLE / Wi-Fi Direct via Nearby Connections)
       ▼
[📱 Relay Phone B (Offline)] 
       │  (TTL Decrement & Deduplication)
       ▼
[📱 Relay Phone C (Offline)] 
       │
       ▼
[📱 Gateway Phone D (Online)] 
       │  (HTTPS POST /api/sos)
       ▼
[☁️ Cloud Gateway API (Node.js/Express)] 
       │  (WebSocket Real-Time Broadcast)
       ▼
[🚨 Disaster Command Center (React + Leaflet Map)]
```

- **Spontaneous Multi-Peer Mesh**: Uses Google Nearby Connections (`P2P_CLUSTER`) with deterministic tie-breaking to eliminate connection collisions.
- **Autonomous Deduplication**: `seenMessageIds` cache prevents infinite packet loops and broadcast storms.
- **TTL (Time-To-Live) Hop Limiter**: Decrements with each hop to preserve battery and network bandwidth.
- **Internet Gateway Bridge**: Automatically detects active network capabilities and uploads payloads to the cloud.
- **Live Command Center Dashboard**: Real-time Leaflet Dark Matter map with audio sirens, animated emergency markers, and hop path visualization.

---

## 📂 Project Structure

```
├── android/          # Android Mesh Relay App (Kotlin + Jetpack Compose)
│   ├── app/src/main/java/com/example/meshtest/
│   │   ├── model/SosPacket.kt         # Structured JSON Emergency Protocol
│   │   ├── mesh/MeshRouter.kt         # Deduplication & TTL Routing Engine
│   │   ├── network/GatewayUploader.kt # Internet detector & HTTP client
│   │   └── NearbyScreen.kt            # Compose UI & Spontaneous Mesh Coordinator
│
├── server/           # Cloud Gateway Backend (Node.js + Express + Socket.io)
│   ├── server.js                      # REST API & WebSocket Realtime Server
│   └── sos_database.json              # Local incident persistence store
│
├── dashboard/        # Disaster Response Control Center (React + Vite + Leaflet)
│   ├── src/components/EmergencyMap.jsx # Interactive Dark-theme incident map
│   ├── src/components/AlertCard.jsx   # Visual relay path inspector
│   └── src/App.jsx                    # Live Socket feed & audio alert siren
│
└── start-demo.bat    # 1-Click launcher for Backend + Web Dashboard
```

---

## 🚀 Quickstart Guide

### 1. Launch Backend & Dashboard
```bash
# Double click start-demo.bat or run:
cd server && npm install && npm start
cd dashboard && npm install && npm run dev
```
Open **`http://localhost:3000`** in your browser.

### 2. Run Android App
1. Open the `android/` directory in **Android Studio**.
2. Connect your Android devices (USB Debugging ON).
3. Build and install on 2 or more physical devices.
4. Tap **"Start Mesh"** on all devices.
5. On any offline device, tap **"🚨 BROADCAST SOS"** and watch the packet hop to the connected gateway and appear on the web dashboard!