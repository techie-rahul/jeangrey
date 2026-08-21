import React, { useState, useEffect, useRef } from 'react';
import { io } from 'socket.io-client';
import { Shield, Radio, RefreshCw, Volume2, VolumeX, Terminal, Sparkles, Trash2 } from 'lucide-react';
import EmergencyMap from './components/EmergencyMap';
import AlertCard from './components/AlertCard';
import StatsBar from './components/StatsBar';

const SERVER_URL = 'https://jeangrey.onrender.com';

export default function App() {
  const [alerts, setAlerts] = useState([]);
  const [stats, setStats] = useState({
    totalEmergencies: 0,
    activeEmergencies: 0,
    resolvedEmergencies: 0,
    averageHops: 0,
    activeGateways: 0
  });
  const [isConnected, setIsConnected] = useState(false);
  const [selectedAlert, setSelectedAlert] = useState(null);
  const [audioEnabled, setAudioEnabled] = useState(true);
  const [filter, setFilter] = useState('ALL'); // ALL, ACTIVE, RESOLVED
  const [packetLogs, setPacketLogs] = useState([]);
  const [simulating, setSimulating] = useState(false);
  const [clearing, setClearing] = useState(false);

  const socketRef = useRef(null);

  // Play synthesized emergency beep using Web Audio API
  const playSirenBeep = () => {
    if (!audioEnabled) return;
    try {
      const audioCtx = new (window.AudioContext || window.webkitAudioContext)();
      const osc = audioCtx.createOscillator();
      const gain = audioCtx.createGain();

      osc.type = 'sawtooth';
      osc.frequency.setValueAtTime(880, audioCtx.currentTime);
      osc.frequency.exponentialRampToValueAtTime(440, audioCtx.currentTime + 0.3);

      gain.gain.setValueAtTime(0.3, audioCtx.currentTime);
      gain.gain.exponentialRampToValueAtTime(0.01, audioCtx.currentTime + 0.3);

      osc.connect(gain);
      gain.connect(audioCtx.destination);

      osc.start();
      osc.stop(audioCtx.currentTime + 0.35);
    } catch (e) {
      console.warn('Audio context error:', e);
    }
  };

  const addPacketLog = (text) => {
    const timestamp = new Date().toLocaleTimeString();
    setPacketLogs(prev => [`[${timestamp}] ${text}`, ...prev.slice(0, 49)]);
  };

  const fetchStats = async () => {
    try {
      const res = await fetch(`${SERVER_URL}/api/stats`);
      if (res.ok) {
        const data = await res.json();
        setStats(data);
      }
    } catch (e) {
      console.error('Stats fetch error', e);
    }
  };

  useEffect(() => {
    const socket = io(SERVER_URL, {
      transports: ['websocket', 'polling'],
      reconnectionAttempts: 10,
    });
    socketRef.current = socket;

    socket.on('connect', () => {
      setIsConnected(true);
      addPacketLog('⚡ Connected to Emergency Cloud Gateway Server');
      fetchStats();
    });

    socket.on('disconnect', () => {
      setIsConnected(false);
      addPacketLog('❌ Disconnected from Gateway Server');
    });

    socket.on('init_data', (initialAlerts) => {
      setAlerts(initialAlerts);
      if (initialAlerts.length > 0) {
        setSelectedAlert(initialAlerts[0]);
      }
      fetchStats();
    });

    socket.on('new_sos', (newAlert) => {
      playSirenBeep();
      setAlerts(prev => [newAlert, ...prev.filter(a => a.messageId !== newAlert.messageId)]);
      setSelectedAlert(newAlert);
      addPacketLog(`🚨 INCOMING SOS | ID: ${newAlert.messageId} | Gateway: ${newAlert.gatewayId} | Hops: ${newAlert.hopCount}`);
      fetchStats();
    });

    socket.on('update_sos', (updatedAlert) => {
      setAlerts(prev => prev.map(a => a.messageId === updatedAlert.messageId ? updatedAlert : a));
      if (selectedAlert && selectedAlert.messageId === updatedAlert.messageId) {
        setSelectedAlert(updatedAlert);
      }
      addPacketLog(`🔄 STATUS UPDATED | ID: ${updatedAlert.messageId} -> ${updatedAlert.status}`);
      fetchStats();
    });

    socket.on('clear_all_sos', () => {
      setAlerts([]);
      setSelectedAlert(null);
      addPacketLog('🧹 Database reset: All emergency alerts cleared.');
      fetchStats();
    });

    return () => {
      socket.disconnect();
    };
  }, [audioEnabled]);

  const handleUpdateStatus = async (messageId, newStatus) => {
    try {
      await fetch(`${SERVER_URL}/api/sos/${messageId}/status`, {
        method: 'PATCH',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ status: newStatus })
      });
    } catch (err) {
      console.error('Status update failed:', err);
    }
  };

  const handleSimulate = async () => {
    setSimulating(true);
    try {
      await fetch(`${SERVER_URL}/api/sos/simulate`, { method: 'POST' });
    } catch (err) {
      console.error('Simulation error', err);
    } finally {
      setTimeout(() => setSimulating(false), 500);
    }
  };

  const handleClearAll = async () => {
    setClearing(true);
    try {
      await fetch(`${SERVER_URL}/api/sos`, { method: 'DELETE' });
    } catch (err) {
      console.error('Clear error', err);
    } finally {
      setTimeout(() => setClearing(false), 500);
    }
  };

  const filteredAlerts = alerts.filter(a => {
    if (filter === 'ACTIVE') return a.status !== 'RESOLVED';
    if (filter === 'RESOLVED') return a.status === 'RESOLVED';
    return true;
  });

  return (
    <div className="min-h-screen bg-dark-900 text-slate-100 flex flex-col">
      {/* Top Navbar */}
      <header className="border-b border-slate-800 bg-dark-800/80 backdrop-blur-md sticky top-0 z-50 px-4 md:px-8 py-3.5 flex items-center justify-between shadow-md">
        <div className="flex items-center gap-3">
          <div className="w-10 h-10 rounded-xl bg-gradient-to-tr from-red-600 to-amber-500 flex items-center justify-center shadow-lg shadow-red-500/20">
            <Shield className="w-5 h-5 text-white" />
          </div>
          <div>
            <div className="flex items-center gap-2">
              <h1 className="font-bold text-lg tracking-tight text-white">ResQMesh</h1>
              <span className="text-[11px] font-mono font-semibold uppercase bg-red-500/20 text-red-400 border border-red-500/30 px-2 py-0.5 rounded-md">
                Track 6.1 — Disaster Response
              </span>
            </div>
            <p className="text-xs text-slate-400">Offline Device-to-Device Mesh Relay Control Center</p>
          </div>
        </div>

        {/* Action Controls */}
        <div className="flex items-center gap-2 md:gap-3">
          <button
            onClick={() => setAudioEnabled(!audioEnabled)}
            className={`p-2 rounded-lg border text-xs font-mono flex items-center gap-1.5 transition ${
              audioEnabled ? 'bg-slate-800 border-slate-700 text-slate-200' : 'bg-slate-900 border-slate-800 text-slate-500'
            }`}
            title="Toggle Audio Alerts"
          >
            {audioEnabled ? <Volume2 className="w-4 h-4 text-emerald-400" /> : <VolumeX className="w-4 h-4" />}
            <span className="hidden sm:inline">{audioEnabled ? 'Siren ON' : 'Muted'}</span>
          </button>

          <button
            onClick={handleClearAll}
            disabled={clearing || alerts.length === 0}
            className="bg-slate-800 hover:bg-slate-700 text-slate-300 font-mono text-xs font-semibold py-2 px-3 rounded-lg border border-slate-700 transition flex items-center gap-1.5 active:scale-95 disabled:opacity-40"
            title="Clear all stored alerts"
          >
            <Trash2 className="w-3.5 h-3.5 text-red-400" />
            <span className="hidden sm:inline">Clear Feed</span>
          </button>

          <button
            onClick={handleSimulate}
            disabled={simulating}
            className="bg-gradient-to-r from-red-600 to-rose-700 hover:from-red-500 hover:to-rose-600 text-white font-mono text-xs font-bold py-2 px-3.5 rounded-lg border border-red-500/40 shadow-lg shadow-red-600/20 transition flex items-center gap-2 active:scale-95 disabled:opacity-50"
          >
            <Sparkles className={`w-3.5 h-3.5 ${simulating ? 'animate-spin' : ''}`} />
            <span>Simulate Mesh SOS</span>
          </button>
        </div>
      </header>

      {/* Main Content Layout */}
      <main className="flex-1 p-4 md:p-6 max-w-7xl mx-auto w-full space-y-5">
        {/* Top Stats Bar */}
        <StatsBar stats={stats} isConnected={isConnected} />

        {/* Split Grid: Feed + Map */}
        <div className="grid grid-cols-1 lg:grid-cols-12 gap-5 items-start">
          {/* Left Column: Alerts Feed (5 cols) */}
          <div className="lg:col-span-5 space-y-4">
            {/* Feed Header & Filters */}
            <div className="bg-dark-800/90 border border-slate-800 p-3.5 rounded-xl flex items-center justify-between">
              <div className="flex items-center gap-2">
                <Radio className="w-4 h-4 text-red-400 animate-pulse" />
                <h3 className="font-bold text-sm tracking-wide text-white uppercase font-mono">
                  Emergency Relay Feed ({filteredAlerts.length})
                </h3>
              </div>

              <div className="flex items-center gap-1 bg-slate-900 p-1 rounded-lg border border-slate-800 text-xs font-mono">
                {['ALL', 'ACTIVE', 'RESOLVED'].map((tab) => (
                  <button
                    key={tab}
                    onClick={() => setFilter(tab)}
                    className={`px-2 py-1 rounded transition text-[11px] font-semibold ${
                      filter === tab ? 'bg-slate-800 text-white shadow' : 'text-slate-400 hover:text-slate-200'
                    }`}
                  >
                    {tab}
                  </button>
                ))}
              </div>
            </div>

            {/* List of Alerts */}
            <div className="space-y-3 max-h-[580px] overflow-y-auto pr-1">
              {filteredAlerts.length === 0 ? (
                <div className="bg-dark-800/50 border border-slate-800/80 rounded-xl p-8 text-center text-slate-400">
                  <Shield className="w-10 h-10 text-slate-600 mx-auto mb-2 opacity-50" />
                  <p className="font-mono text-sm font-semibold text-slate-300">No Emergencies In Feed</p>
                  <p className="text-xs text-slate-500 mt-1">Waiting for incoming offline packets from Gateway phones...</p>
                  <button
                    onClick={handleSimulate}
                    className="mt-4 text-xs font-mono text-red-400 hover:underline inline-flex items-center gap-1"
                  >
                    + Click to trigger simulated demo packet
                  </button>
                </div>
              ) : (
                filteredAlerts.map((alert) => (
                  <AlertCard
                    key={alert.messageId}
                    alert={alert}
                    isSelected={selectedAlert?.messageId === alert.messageId}
                    onSelect={(a) => setSelectedAlert(a)}
                    onUpdateStatus={handleUpdateStatus}
                  />
                ))
              )}
            </div>

            {/* Live Packet Terminal Log */}
            <div className="bg-slate-950 border border-slate-800 rounded-xl p-3.5 font-mono text-xs shadow-inner">
              <div className="flex items-center justify-between pb-2 mb-2 border-b border-slate-800 text-slate-400">
                <span className="flex items-center gap-1.5 font-semibold text-slate-300">
                  <Terminal className="w-3.5 h-3.5 text-emerald-400" />
                  GATEWAY PACKET STREAM
                </span>
                <span className="text-[10px] text-emerald-400 bg-emerald-950/60 px-2 py-0.5 rounded border border-emerald-800/40">
                  LIVE RX
                </span>
              </div>
              <div className="space-y-1 max-h-32 overflow-y-auto text-[11px] text-slate-300 scrollbar-none font-mono">
                {packetLogs.length === 0 ? (
                  <span className="text-slate-600 italic">Waiting for mesh packets...</span>
                ) : (
                  packetLogs.map((log, idx) => (
                    <div key={idx} className="leading-relaxed text-slate-400 hover:text-slate-200 transition">
                      {log}
                    </div>
                  ))
                )}
              </div>
            </div>
          </div>

          {/* Right Column: Interactive Map (7 cols) */}
          <div className="lg:col-span-7 flex flex-col h-[750px]">
            <EmergencyMap
              alerts={filteredAlerts}
              selectedAlert={selectedAlert}
              onSelectAlert={(a) => setSelectedAlert(a)}
            />
          </div>
        </div>
      </main>
    </div>
  );
}