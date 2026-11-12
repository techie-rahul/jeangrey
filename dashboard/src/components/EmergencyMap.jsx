import React, { useState, useEffect } from 'react';
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
  const [mapMode, setMapMode] = useState('dark'); // 'dark' | 'satellite' | 'street'

  return (
    <div className="w-full h-full min-h-[420px] rounded-xl overflow-hidden border border-slate-800 relative shadow-2xl">
      {/* Map Mode Selector */}
      <div className="absolute top-3 right-3 z-[1000] bg-dark-900/90 backdrop-blur-md p-1 rounded-lg border border-slate-700 text-xs font-mono flex items-center gap-1 shadow-lg">
        <button
          type="button"
          onClick={() => setMapMode('dark')}
          className={`px-2.5 py-1 rounded transition text-xs flex items-center gap-1.5 ${
            mapMode === 'dark'
              ? 'bg-red-600 text-white font-semibold shadow'
              : 'text-slate-400 hover:text-slate-200 hover:bg-slate-800'
          }`}
        >
          <span className="w-1.5 h-1.5 rounded-full bg-slate-300"></span>
          Dark
        </button>
        <button
          type="button"
          onClick={() => setMapMode('satellite')}
          className={`px-2.5 py-1 rounded transition text-xs flex items-center gap-1.5 ${
            mapMode === 'satellite'
              ? 'bg-red-600 text-white font-semibold shadow'
              : 'text-slate-400 hover:text-slate-200 hover:bg-slate-800'
          }`}
        >
          <span className="w-1.5 h-1.5 rounded-full bg-emerald-400"></span>
          Satellite
        </button>
        <button
          type="button"
          onClick={() => setMapMode('street')}
          className={`px-2.5 py-1 rounded transition text-xs flex items-center gap-1.5 ${
            mapMode === 'street'
              ? 'bg-red-600 text-white font-semibold shadow'
              : 'text-slate-400 hover:text-slate-200 hover:bg-slate-800'
          }`}
        >
          <span className="w-1.5 h-1.5 rounded-full bg-blue-400"></span>
          Street
        </button>
      </div>

      <MapContainer
        center={defaultCenter}
        zoom={12}
        scrollWheelZoom={true}
        className="w-full h-full"
      >
        {mapMode === 'dark' && (
          <>
            <TileLayer
              key="esri-dark-base"
              attribution='&copy; <a href="https://www.esri.com/">Esri</a>'
              url="https://server.arcgisonline.com/ArcGIS/rest/services/Canvas/World_Dark_Gray_Base/MapServer/tile/{z}/{y}/{x}"
              maxZoom={16}
            />
            <TileLayer
              key="esri-dark-ref"
              attribution='&copy; <a href="https://www.esri.com/">Esri</a>'
              url="https://server.arcgisonline.com/ArcGIS/rest/services/Canvas/World_Dark_Gray_Reference/MapServer/tile/{z}/{y}/{x}"
              maxZoom={16}
            />
          </>
        )}

        {mapMode === 'satellite' && (
          <TileLayer
            key="esri-sat"
            attribution='&copy; <a href="https://www.esri.com/">Esri</a>'
            url="https://server.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/{z}/{y}/{x}"
            maxZoom={19}
          />
        )}

        {mapMode === 'street' && (
          <TileLayer
            key="osm-street"
            attribution='&copy; <a href="https://www.openstreetmap.org/copyright">OpenStreetMap</a>'
            url="https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png"
            maxZoom={19}
          />
        )}

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
