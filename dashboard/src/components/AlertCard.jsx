import React from 'react';
import { Radio, Battery, ShieldAlert, ArrowRight, CheckCircle2, Clock, MapPin, Activity, ExternalLink } from 'lucide-react';

export default function AlertCard({ alert, isSelected, onSelect, onUpdateStatus }) {
  const isCritical = alert.severity === 'CRITICAL';
  const isResolved = alert.status === 'RESOLVED';
  const isResponding = alert.status === 'RESPONDING';

  return (
    <div
      onClick={() => onSelect(alert)}
      className={`p-4 rounded-xl border transition-all duration-200 cursor-pointer relative overflow-hidden ${
        isSelected
          ? 'bg-slate-800/90 border-red-500/80 shadow-lg shadow-red-500/10 ring-1 ring-red-500/50'
          : 'bg-dark-800/80 border-slate-800 hover:border-slate-700 hover:bg-dark-800'
      }`}
    >
      {/* Top Banner */}
      <div className="flex items-center justify-between gap-2 mb-3">
        <div className="flex items-center gap-2">
          <span className={`p-1.5 rounded-lg ${isCritical ? 'bg-red-500/20 text-red-400' : 'bg-amber-500/20 text-amber-400'}`}>
            <ShieldAlert className="w-4 h-4 animate-pulse" />
          </span>
          <div>
            <h4 className="font-mono font-bold text-sm tracking-wide text-white flex items-center gap-2">
              {alert.messageId}
              <span className={`text-[10px] font-sans px-2 py-0.5 rounded-full font-semibold border ${
                isResolved
                  ? 'bg-emerald-500/10 text-emerald-400 border-emerald-500/30'
                  : isResponding
                  ? 'bg-blue-500/10 text-blue-400 border-blue-500/30'
                  : 'bg-red-500/10 text-red-400 border-red-500/30 animate-pulse'
              }`}>
                {alert.status}
              </span>
            </h4>
          </div>
        </div>

        <div className="flex items-center gap-2 text-xs font-mono text-slate-400">
          {alert.batteryLevel !== null && (
            <span className="flex items-center gap-1 bg-slate-800 px-2 py-0.5 rounded border border-slate-700">
              <Battery className={`w-3.5 h-3.5 ${alert.batteryLevel < 20 ? 'text-red-400' : 'text-emerald-400'}`} />
              {alert.batteryLevel}%
            </span>
          )}
        </div>
      </div>

      {/* Message & Coordinates */}
      <p className="text-sm text-slate-200 font-medium mb-3 line-clamp-2">
        {alert.messageText}
      </p>

      <div className="grid grid-cols-1 gap-2 text-xs font-mono text-slate-400 bg-slate-900/60 p-2.5 rounded-lg border border-slate-800/80 mb-3">
        {/* Location — clickable Google Maps link */}
        {alert.latitude != null && alert.longitude != null && !isNaN(alert.latitude) && !isNaN(alert.longitude) ? (
          <div className="space-y-1.5">
            <div className="flex items-center gap-1.5">
              <MapPin className="w-3.5 h-3.5 text-red-400 shrink-0" />
              <span className="text-[10px] uppercase text-slate-500 font-semibold">Location</span>
            </div>
            <a
              href={`https://www.google.com/maps/search/?api=1&query=${alert.latitude},${alert.longitude}`}
              target="_blank"
              rel="noopener noreferrer"
              onClick={(e) => e.stopPropagation()}
              className="text-slate-200 hover:text-white transition truncate block"
            >
              {alert.latitude.toFixed(5)}, {alert.longitude.toFixed(5)}
            </a>
            <a
              href={`https://www.google.com/maps/search/?api=1&query=${alert.latitude},${alert.longitude}`}
              target="_blank"
              rel="noopener noreferrer"
              onClick={(e) => e.stopPropagation()}
              className="inline-flex items-center gap-1.5 text-[11px] font-semibold text-cyan-400 hover:text-cyan-300 transition bg-cyan-500/10 hover:bg-cyan-500/20 border border-cyan-500/30 px-2.5 py-1 rounded-md"
            >
              <ExternalLink className="w-3 h-3" />
              Open in Google Maps ↗
            </a>
          </div>
        ) : (
          <div className="flex items-center gap-1.5">
            <MapPin className="w-3.5 h-3.5 text-slate-600 shrink-0" />
            <span className="text-slate-500 italic">📍 Location unavailable</span>
          </div>
        )}

        {/* Timestamp */}
        <div className="flex items-center gap-1.5 pt-1 border-t border-slate-800/50">
          <Clock className="w-3.5 h-3.5 text-slate-500 shrink-0" />
          <span>{new Date(alert.timestamp).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit', second: '2-digit' })}</span>
        </div>
      </div>

      {/* Visual Mesh Relay Hop Path */}
      <div className="bg-slate-950/60 rounded-lg p-2.5 border border-slate-800/80 mb-3">
        <div className="flex items-center justify-between text-[11px] text-slate-400 mb-1.5 font-mono font-medium">
          <span className="flex items-center gap-1">
            <Radio className="w-3 h-3 text-cyan-400" />
            RELAY HOP PATH ({alert.hopCount} {alert.hopCount === 1 ? 'hop' : 'hops'})
          </span>
          <span className="text-emerald-400">TTL: {alert.ttl}</span>
        </div>

        <div className="flex items-center gap-1 overflow-x-auto py-1 scrollbar-none">
          {alert.relayPath && alert.relayPath.length > 0 ? (
            alert.relayPath.map((node, idx) => (
              <React.Fragment key={idx}>
                <span className={`text-[10px] font-mono px-2 py-0.5 rounded whitespace-nowrap border ${
                  idx === 0 
                    ? 'bg-red-950/60 text-red-300 border-red-800/60'
                    : 'bg-slate-800 text-slate-300 border-slate-700'
                }`}>
                  {idx === 0 ? '📱 Origin: ' : '🔄 '}{node.replace('DEVICE-', '')}
                </span>
                {idx < alert.relayPath.length - 1 && (
                  <ArrowRight className="w-3 h-3 text-slate-600 shrink-0" />
                )}
              </React.Fragment>
            ))
          ) : (
            <span className="text-[10px] font-mono text-slate-500">{alert.senderId}</span>
          )}

          <ArrowRight className="w-3 h-3 text-emerald-500 shrink-0" />
          <span className="text-[10px] font-mono px-2 py-0.5 rounded bg-emerald-950/60 text-emerald-300 border border-emerald-800/60 whitespace-nowrap flex items-center gap-1">
            🌐 {alert.gatewayId.replace('DEVICE-', '')}
          </span>
        </div>
      </div>

      {/* Action Buttons */}
      <div className="flex items-center gap-2 pt-1 border-t border-slate-800/80">
        {alert.status !== 'RESOLVED' && (
          <button
            onClick={(e) => {
              e.stopPropagation();
              onUpdateStatus(alert.messageId, 'RESOLVED');
            }}
            className="flex-1 bg-emerald-600/20 hover:bg-emerald-600/30 text-emerald-400 text-xs font-semibold py-1.5 px-3 rounded-lg border border-emerald-500/30 transition flex items-center justify-center gap-1.5"
          >
            <CheckCircle2 className="w-3.5 h-3.5" />
            Mark Evacuated / Resolved
          </button>
        )}
        {alert.status === 'ACTIVE' && (
          <button
            onClick={(e) => {
              e.stopPropagation();
              onUpdateStatus(alert.messageId, 'RESPONDING');
            }}
            className="bg-blue-600/20 hover:bg-blue-600/30 text-blue-400 text-xs font-semibold py-1.5 px-3 rounded-lg border border-blue-500/30 transition flex items-center justify-center gap-1.5"
          >
            <Activity className="w-3.5 h-3.5" />
            Dispatch Drone/Team
          </button>
        )}
      </div>
    </div>
  );
}