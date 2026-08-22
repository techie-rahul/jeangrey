# 🧪 ResQMesh - Acoustic Fallback & Mesh Verification Plan

This document outlines the testing protocol for the **Acoustic (Sound-Based) Fallback Transport Tier** implemented in the `feature/acoustic-fallback` branch.

---

## 📋 Pre-Merge Checklist

- [x] **Automated Unit Tests**:
  - `AcousticBeaconTest.kt` verifies Geohash encoding/decoding accuracy across multiple global coordinates.
  - Beacon serialization from `SosPacket` and parsing back into `AcousticPayload`.
  - Partial `SosPacket` reconstruction with `ACOUSTIC-` prefix and dedup attributes for cloud ingestion.
  - Handled invalid/corrupted raw string payloads without crashing.

- [ ] **Dual Physical Device Acoustic Validation** (Quiet Room):
  1. Install APK on Device A (Victim/Transmitter) and Device B (Gateway/Receiver).
  2. Toggle **"Force Acoustic Test Mode"** to `ON` on both devices.
  3. Place devices ~1–2 meters apart.
  4. On Device A, tap **`🚨 BROADCAST SOS`**.
  5. Verify Device A emits a short audible FSK sound chirp (~1.5s).
  6. Verify Device B captures and logs `🔊 RX ACOUSTIC BEACON: ID=... | Sev=... | Geohash=...` within 3–5 seconds.

- [ ] **Acoustic Cloud Offload Validation**:
  1. Ensure Device B (Gateway) is connected to the internet.
  2. Verify Device B logs `🌐 ACOUSTIC GATEWAY: Offloading acoustic SOS to cloud...` followed by `✅ Cloud Ingest (Acoustic): Uploaded to Cloud (201)`.
  3. Open the live web dashboard (`https://jeangrey.onrender.com/api/sos` or Vercel dashboard) and confirm the `ACOUSTIC-XXXXXX` emergency alert pin appears.

- [ ] **Acoustic Rate-Limiting Check**:
  1. Rapidly tap **`🚨 BROADCAST SOS`** multiple times on Device A within 5 seconds.
  2. Verify Device A transmits once and logs `⏳ [ACOUSTIC TX] Beacon rate-limited (10s cooldown)` for subsequent attempts, preventing battery exhaustion.

- [ ] **Background Noise Tolerance Check**:
  1. Play moderate ambient background audio (e.g. music/conversation).
  2. Trigger acoustic SOS from Device A.
  3. Verify Goertzel tone filters isolate the preamble and data frequencies from ambient noise floor.

- [ ] **BLE & Wi-Fi Direct Non-Regression Verification**:
  1. Toggle **"Force Acoustic Test Mode"** to `OFF` on both devices (normal battery level > 15%).
  2. Tap **"Start Mesh"** on both devices.
  3. Verify Google Nearby Connections spontaneous handshake connects (`Peers: 1`).
  4. Tap **`🚨 BROADCAST SOS`** on Device A.
  5. Verify normal multi-hop BLE relaying (`Relayed SOS-XXXXXX (TTL: 4) ➔ 1 peer(s)`) and direct cloud ingest remain 100% functional.

---

## 🚦 Merge Criteria

Merge `feature/acoustic-fallback` into `main` **only after** steps 2, 3, and 6 pass on physical hardware.
If acoustic decoding reliability in noisy environments requires further DSP tuning, keep this feature on the branch and demo it as an isolated experimental fallback tier.