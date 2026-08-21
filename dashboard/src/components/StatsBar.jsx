import React from 'react';
import { ShieldAlert, Radio, Server, Cpu, CheckCircle } from 'lucide-react';

export default function StatsBar({ stats, isConnected }) {
  return (
    <div className="grid grid-cols-2 md:grid-cols-5 gap-3">
      {/* 1. Active SOS Count */}
      <div className="bg-dark-800/90 border border-slate-800 p-3.5 rounded-xl flex items-center gap-3 relative overflow-hidden">
        <div className="p-2.5 rounded-lg bg-red-500/10 text-red-400 border border-red-500/20 shrink-0">
          <ShieldAlert className="w-5 h-5 animate-pulse" />
        </div>
        <div>
          <p className="text-xs font-mono uppercase text-slate-400 font-medium">Active Emergencies</p>
          <p className="text-2xl font-black font-mono text-red-400">{stats.activeEmergencies}</p>
        </div>
        {stats.activeEmergencies > 0 && (
          <span className="absolute top-2 right-2 flex h-2 w-2">
            <span className="animate-ping absolute inline-flex h-full w-full rounded-full bg-red-400 opacity-75"></span>
            <span className="relative inline-flex rounded-full h-2 w-2 bg-red-500"></span>
          </span>
        )}
      </div>

      {/* 2. Total Relayed Emergencies */}
      <div className="bg-dark-800/90 border border-slate-800 p-3.5 rounded-xl flex items-center gap-3">
        <div className="p-2.5 rounded-lg bg-cyan-500/10 text-cyan-400 border border-cyan-500/20 shrink-0">
          <Radio className="w-5 h-5" />
        </div>
        <div>
          <p className="text-xs font-mono uppercase text-slate-400 font-medium">Total Relayed</p>
          <p className="text-2xl font-black font-mono text-slate-100">{stats.totalEmergencies}</p>
        </div>
      </div>

      {/* 3. Average Hops */}
      <div className="bg-dark-800/90 border border-slate-800 p-3.5 rounded-xl flex items-center gap-3">
        <div className="p-2.5 rounded-lg bg-indigo-500/10 text-indigo-400 border border-indigo-500/20 shrink-0">
          <Cpu className="w-5 h-5" />
        </div>
        <div>
          <p className="text-xs font-mono uppercase text-slate-400 font-medium">Avg Mesh Hops</p>
          <p className="text-2xl font-black font-mono text-indigo-300">{stats.averageHops}</p>
        </div>
      </div>

      {/* 4. Active Gateways */}
      <div className="bg-dark-800/90 border border-slate-800 p-3.5 rounded-xl flex items-center gap-3">
        <div className="p-2.5 rounded-lg bg-emerald-500/10 text-emerald-400 border border-emerald-500/20 shrink-0">
          <Server className="w-5 h-5" />
        </div>
        <div>
          <p className="text-xs font-mono uppercase text-slate-400 font-medium">Gateways Active</p>
          <p className="text-2xl font-black font-mono text-emerald-400">{stats.activeGateways}</p>
        </div>
      </div>

      {/* 5. Cloud Sync Status */}
      <div className="bg-dark-800/90 border border-slate-800 p-3.5 rounded-xl flex items-center gap-3 col-span-2 md:col-span-1">
        <div className={`p-2.5 rounded-lg border shrink-0 ${
          isConnected
            ? 'bg-emerald-500/10 text-emerald-400 border-emerald-500/20'
            : 'bg-red-500/10 text-red-400 border-red-500/20'
        }`}>
          <CheckCircle className="w-5 h-5" />
        </div>
        <div>
          <p className="text-xs font-mono uppercase text-slate-400 font-medium">Live Gateway Stream</p>
          <p className={`text-sm font-bold font-mono ${isConnected ? 'text-emerald-400' : 'text-red-400'}`}>
            {isConnected ? 'ONLINE / LISTENING' : 'CONNECTING...'}
          </p>
        </div>
      </div>
    </div>
  );
}