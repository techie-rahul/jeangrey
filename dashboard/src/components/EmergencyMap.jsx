import React, { useEffect } from 'react';
import { MapContainer, TileLayer, Marker, Popup, useMap } from 'react-leaflet';
import L from 'leaflet';

// Custom Animated Red Icon for SOS
const createSosIcon = (isCritical = true) => {
  return L.divIcon({
    className: 'custom-sos-pin',
    html: `
      <div class="relative flex items-center justify-center">
        <span class="animate-ping absolute inline-flex h-8 w-8 rounded-full ${isCritical ? 'bg-red-500' : 'bg-amber-500'} opacity-75"></span>
        <span class="relative inline-flex items-center justify-center rounded-full h-6 w-6 ${isCritical ? 'bg-red-600' : 'bg-amber-600'} text-white text-xs font-bold shadow-lg border-2 border-white">
          🚨
        </span>
      </div>
    `,
    iconSize: [24, 24],
    iconAnchor: [12, 12]
  });
};

function AutoCenterMap({ alerts, selectedAlert }) {
  const map = useMap();
  useEffect(() => {
    if (selectedAlert && selectedAlert.latitude && selectedAlert.longitude) {
      map.flyTo([selectedAlert.latitude, selectedAlert.longitude], 14, { duration: 1.5 });
    } else if (alerts && alerts.length > 0) {
      const latest = alerts[0];
      if (latest.latitude && latest.longitude) {
        map.flyTo([latest.latitude, latest.longitude], 13, { duration: 1.2 });
      }
    }
  }, [alerts, selectedAlert, map]);

  return null;
}

export default function EmergencyMap({ alerts, selectedAlert, onSelectAlert }) {
  const defaultCenter = [26.9124, 75.7873]; // Default: Jaipur coordinates

  return (
    <div className="w-full h-full min-h-[420px] rounded-xl overflow-hidden border border-slate-800 relative shadow-2xl">
      <div className="absolute top-3 right-3 z-[1000] bg-dark-900/90 backdrop-blur-md px-3 py-1.5 rounded-lg border border-slate-700 text-xs font-mono flex items-center gap-2 text-slate-300 shadow">
        <span className="w-2 h-2 rounded-full bg-emerald-500 animate-pulse"></span>
        <span>SATELLITE & MESH OVERLAY ACTIVE</span>
      </div>

      <MapContainer
        center={defaultCenter}
        zoom={12}
        scrollWheelZoom={true}
        className="w-full h-full"
      >
        <TileLayer
          attribution='&copy; <a href="https://carto.com/">CARTO</a>'
          url="https://{s}.basemaps.cartocdn.com/dark_all/{z}/{x}/{y}{r}.png"
        />

        <AutoCenterMap alerts={alerts} selectedAlert={selectedAlert} />

        {alerts.filter(alert => alert.latitude != null && alert.longitude != null && !isNaN(alert.latitude) && !isNaN(alert.longitude)).map((alert) => (
          <Marker
            key={alert.messageId}
            position={[alert.latitude, alert.longitude]}
            icon={createSosIcon(alert.severity === 'CRITICAL')}
            eventHandlers={{
              click: () => onSelectAlert(alert),
            }}
          >
            <Popup className="custom-popup">
              <div className="p-2 text-slate-900 min-w-[200px]">
                <div className="flex items-center justify-between border-b pb-1 mb-1.5">
                  <span className="font-bold text-red-600 font-mono text-sm">{alert.messageId}</span>
                  <span className="text-[10px] font-semibold uppercase px-1.5 py-0.5 rounded bg-red-100 text-red-700">
                    {alert.severity}
                  </span>
                </div>
                <p className="text-xs text-slate-700 font-medium mb-1">{alert.messageText}</p>
                <div className="text-[11px] text-slate-500 font-mono space-y-0.5">
                  <div>📍 {alert.latitude.toFixed(5)}, {alert.longitude.toFixed(5)}</div>
                  <div>🔄 Hops: <span className="font-bold text-slate-800">{alert.hopCount}</span></div>
                  <div>📡 Gateway: <span className="font-semibold">{alert.gatewayId}</span></div>
                  {alert.batteryLevel !== null && (
                    <div>🔋 Battery: {alert.batteryLevel}%</div>
                  )}
                  <a
                    href={`https://www.google.com/maps/search/?api=1&query=${alert.latitude},${alert.longitude}`}
                    target="_blank"
                    rel="noopener noreferrer"
                    className="text-blue-600 hover:underline font-semibold block mt-1"
                  >
                    Open in Google Maps ↗
                  </a>
                </div>
              </div>
            </Popup>
          </Marker>
        ))}
      </MapContainer>
    </div>
  );
}