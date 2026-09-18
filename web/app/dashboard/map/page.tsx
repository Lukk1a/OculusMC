"use client";

import { useState, useEffect } from "react";
import { getToken } from "@/lib/api";

export default function MapPage() {
  const [world, setWorld] = useState("world");
  const [refresh, setRefresh] = useState(false);
  const [iframeMap, setIframeMap] = useState(""); // If users want dynmap/bluemap they could put it here

  const [mapUrl, setMapUrl] = useState<string>("");
  const [loading, setLoading] = useState(false);

  const fetchMap = async () => {
    setLoading(true);
    try {
      const token = getToken();
      const res = await fetch(`/api/map/overview?world=${world}&refresh=true`, {
        headers: token ? { 'Authorization': `Bearer ${token}` } : {}
      });
      if (!res.ok) throw new Error("Failed to fetch map");
      const blob = await res.blob();
      setMapUrl(URL.createObjectURL(blob));
    } catch (err) {
      console.error(err);
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    fetchMap();
  }, [world, refresh]);

  return (
    <div className="space-y-6">
      <h1 className="text-3xl font-bold tracking-tight text-vbg-text-primary">Server Map</h1>
      
      {iframeMap ? (
        <iframe src={iframeMap} className="w-full h-[800px] border-0 rounded-lg  bg-vbg-surface-primary" />
      ) : (
        <div className="bg-vbg-surface-secondary p-6 rounded-lg  border border-vbg-border-subtle">
          <div className="flex gap-4 mb-4">
            <select 
              className="bg-vbg-surface-primary border border-vbg-border-subtle rounded px-3 py-2 text-vbg-text-primary"
              value={world}
              onChange={e => setWorld(e.target.value)}
            >
              <option value="world">Overworld</option>
              <option value="world_nether">Nether</option>
              <option value="world_the_end">The End</option>
            </select>
            <button 
              className="px-4 py-2 bg-[#ededed] text-[#000000] hover:bg-[#a1a1aa] rounded transition flex items-center justify-center gap-2"
              onClick={() => setRefresh(!refresh)}
              disabled={loading}
            >
              {loading ? "Loading..." : "Refresh Overview"}
            </button>
          </div>
          
          <div className="w-full overflow-auto bg-[#0a0a0a] rounded-lg border border-[#171717] relative flex items-center justify-center" style={{ minHeight: '600px' }}>
            {mapUrl ? (
              <img 
                src={mapUrl}
                alt="Map Overview" 
                className="max-w-none block"
              />
            ) : (
              <div className="text-[#a1a1aa]">Loading map overview...</div>
            )}
          </div>
        </div>
      )}
    </div>
  );
}

