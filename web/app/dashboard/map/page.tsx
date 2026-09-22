"use client";

import React, { useState, useEffect, useCallback } from "react";
import { fetchApi } from "@/lib/api";
import { toast } from "@/lib/toast";
import MinecraftMap, { PlayerCoord, Waypoint } from "@/components/MinecraftMap";
import { Globe, MapPin, Check, Copy, X } from "lucide-react";

export default function MapPage() {
  const [world, setWorld] = useState("world");
  const [players, setPlayers] = useState<PlayerCoord[]>([]);
  const [waypoints, setWaypoints] = useState<Waypoint[]>([]);
  const [showWaypointDrawer, setShowWaypointDrawer] = useState(false);
  const [showWaypointModal, setShowWaypointModal] = useState(false);
  const [copiedText, setCopiedText] = useState("");

  const [newWaypoint, setNewWaypoint] = useState({
    name: "",
    x: 0,
    y: 64,
    z: 0,
    color: "#e4f222",
  });

  // Fetch Live Players & Server Telemetry
  const fetchPlayers = useCallback(async () => {
    try {
      const stats = await fetchApi("/api/stats");
      if (stats && Array.isArray(stats.players)) {
        setPlayers(stats.players);
      }
    } catch {
      // Ignore background telemetry errors
    }
  }, []);

  // Fetch Waypoints
  const fetchWaypoints = useCallback(async () => {
    try {
      const data = await fetchApi("/api/waypoints");
      if (Array.isArray(data)) {
        setWaypoints(data);
      }
    } catch {
      // Fallback: try apollo waypoints if /api/waypoints differs
      try {
        const apolloData = await fetchApi("/api/apollo/waypoints");
        if (Array.isArray(apolloData)) {
          setWaypoints(apolloData);
        }
      } catch {
        // Silently handle
      }
    }
  }, []);

  useEffect(() => {
    fetchPlayers();
    fetchWaypoints();
    const interval = setInterval(fetchPlayers, 3000);
    return () => clearInterval(interval);
  }, [fetchPlayers, fetchWaypoints]);

  // Handle Request to Create Waypoint (From Right-Click on Map Canvas)
  const handleRequestCreateWaypoint = (target: { x: number; z: number; world: string; y?: number }) => {
    setNewWaypoint({
      name: "",
      x: Math.round(target.x),
      y: target.y !== undefined ? Math.round(target.y) : 64,
      z: Math.round(target.z),
      color: "#e4f222",
    });
    setShowWaypointModal(true);
  };

  // Submit New Waypoint
  const handleCreateWaypoint = async (e?: React.FormEvent) => {
    if (e) e.preventDefault();
    if (!newWaypoint.name || !newWaypoint.name.trim()) {
      toast.error("Waypoint name is required");
      return;
    }

    const payload = {
      name: newWaypoint.name.trim(),
      world: world,
      x: Number(newWaypoint.x),
      y: Number(newWaypoint.y),
      z: Number(newWaypoint.z),
      color: newWaypoint.color || "#e4f222",
      player: "all",
    };

    try {
      // Save to standard waypoint registry
      await fetchApi("/api/waypoint", {
        method: "POST",
        body: JSON.stringify(payload),
      });

      // Also forward to Apollo so in-game Lunar Client users see it immediately
      try {
        await fetchApi("/api/apollo/waypoint", {
          method: "POST",
          body: JSON.stringify(payload),
        });
      } catch {
        // Apollo may not be installed; ignore
      }

      toast.success(`Waypoint "${payload.name}" plotted`);
      setShowWaypointModal(false);
      setNewWaypoint({ name: "", x: 0, y: 64, z: 0, color: "#e4f222" });
      fetchWaypoints();
    } catch (err: any) {
      toast.error(err.message || "Failed to create waypoint");
    }
  };

  // Delete Waypoint
  const handleDeleteWaypoint = async (name: string) => {
    try {
      await fetchApi(`/api/waypoint?name=${encodeURIComponent(name)}`, {
        method: "DELETE",
      });
      try {
        await fetchApi(`/api/apollo/waypoint?name=${encodeURIComponent(name)}`, {
          method: "DELETE",
        });
      } catch {
        // Apollo optional
      }
      toast.success(`Waypoint "${name}" removed`);
      setWaypoints((prev) => prev.filter((w) => w.name !== name));
    } catch (err: any) {
      toast.error(err.message || "Failed to delete waypoint");
    }
  };

  const copyToClipboard = (text: string, label: string) => {
    navigator.clipboard.writeText(text);
    setCopiedText(label);
    setTimeout(() => setCopiedText(""), 2000);
  };

  return (
    <div className="relative w-full h-[calc(100vh-6.5rem)] flex flex-col min-h-0 space-y-2">
      {/* Sub-header Bar */}
      <div className="flex items-center justify-between px-1 shrink-0">
        <div className="flex items-center gap-2">
          <Globe className="w-4 h-4 text-foreground" />
          <h1 className="text-sm font-semibold tracking-tight text-foreground">
            Tactical World Radar
          </h1>
          <span className="text-[11px] font-mono px-2 py-0.5 rounded-[4px] bg-secondary border border-border text-muted-foreground">
            Direct Anvil MCA Chunk Engine · 1024×1024 Blocks
          </span>
        </div>

        <div className="flex items-center gap-3 text-xs font-mono text-muted-foreground">
          <span className="flex items-center gap-1.5">
            <span className="w-2 h-2 rounded-full bg-emerald-500" />
            <span>{players.filter((p) => !p.world || p.world === world).length} Players in {world}</span>
          </span>
          <span className="text-border">|</span>
          <span>{waypoints.filter((w) => !w.world || w.world === world).length} Waypoints</span>
        </div>
      </div>

      {/* Main Focus Map Container */}
      <div className="relative flex-1 w-full rounded-[12px] overflow-hidden border border-border min-h-0 shadow-lg">
        <MinecraftMap
          currentDimension={world}
          onDimensionChange={setWorld}
          players={players}
          waypoints={waypoints}
          onRequestCreateWaypoint={handleRequestCreateWaypoint}
          onDeleteWaypoint={handleDeleteWaypoint}
          onToggleWaypointDrawer={() => setShowWaypointDrawer((prev) => !prev)}
          waypointCount={waypoints.length}
          height="100%"
        />

        {/* Slide-over Tactical Waypoints Drawer */}
        {showWaypointDrawer && (
          <div className="absolute top-14 right-3 bottom-3 w-84 max-w-[calc(100%-1.5rem)] bg-[#0f1011]/95 border border-[#23252a] rounded-[12px] p-4 shadow-2xl backdrop-blur-md flex flex-col z-30 animate-in fade-in slide-in-from-right-4 duration-150">
            <div className="flex items-center justify-between pb-3 border-b border-[#23252a] shrink-0">
              <div className="flex items-center gap-2">
                <MapPin className="w-4 h-4 text-[#e4f222]" />
                <span className="text-sm font-medium text-[#ffffff]">Tactical Waypoints</span>
                <span className="text-[10px] font-mono px-1.5 py-0.5 rounded-full bg-white/[0.08] text-white">
                  {waypoints.length}
                </span>
              </div>
              <div className="flex items-center gap-1.5">
                <button
                  type="button"
                  onClick={() => {
                    setNewWaypoint({
                      name: "",
                      x: 0,
                      y: 64,
                      z: 0,
                      color: "#e4f222",
                    });
                    setShowWaypointModal(true);
                  }}
                  className="px-2.5 py-1 rounded-[6px] bg-[#e4f222] text-[#08090a] font-medium text-[11px] cursor-pointer hover:brightness-110 transition-all flex items-center gap-1"
                >
                  <span>+ New</span>
                </button>
                <button
                  type="button"
                  onClick={() => setShowWaypointDrawer(false)}
                  className="p-1 rounded text-[#8a8f98] hover:text-[#ffffff] hover:bg-white/[0.05] cursor-pointer transition-colors"
                >
                  <X className="w-4 h-4" />
                </button>
              </div>
            </div>

            {/* Waypoint list */}
            <div className="flex-1 overflow-y-auto space-y-2 py-3 pr-1">
              {waypoints.length === 0 ? (
                <div className="text-center py-8 space-y-2">
                  <MapPin className="w-6 h-6 text-[#62666d] mx-auto" />
                  <div className="text-xs text-[#8a8f98]">No waypoints plotted yet.</div>
                  <p className="text-[11px] text-[#62666d]">
                    Right-click anywhere on the map to place a tactical beacon.
                  </p>
                </div>
              ) : (
                waypoints.map((wp) => (
                  <div
                    key={wp.name}
                    className="p-3 bg-[#161718] border border-[#23252a] hover:border-[#383b3f] rounded-[6px] transition-all space-y-2 group"
                  >
                    <div className="flex items-center justify-between">
                      <div className="flex items-center gap-2 min-w-0">
                        <div
                          className="w-2.5 h-2.5 rounded-full shrink-0"
                          style={{ backgroundColor: wp.color || "#e4f222" }}
                        />
                        <span className="text-xs font-medium text-[#ffffff] truncate">{wp.name}</span>
                      </div>
                      <div className="flex items-center gap-1 shrink-0">
                        <button
                          type="button"
                          onClick={() => copyToClipboard(`/tp @s ${wp.x} ${wp.y || 64} ${wp.z}`, wp.name)}
                          className="p-1 rounded hover:bg-white/[0.06] text-[#8a8f98] hover:text-[#ffffff] transition-colors cursor-pointer"
                          title="Copy /tp command"
                        >
                          {copiedText === wp.name ? (
                            <Check className="w-3.5 h-3.5 text-[#27a644]" />
                          ) : (
                            <Copy className="w-3.5 h-3.5" />
                          )}
                        </button>
                        <button
                          type="button"
                          onClick={() => handleDeleteWaypoint(wp.name)}
                          className="p-1 rounded hover:bg-[#eb5757]/20 text-[#8a8f98] hover:text-[#eb5757] transition-colors cursor-pointer"
                          title="Delete waypoint"
                        >
                          <X className="w-3.5 h-3.5" />
                        </button>
                      </div>
                    </div>

                    <div className="flex items-center justify-between text-[11px] font-mono text-[#8a8f98]">
                      <span className="uppercase text-[#62666d]">{wp.world || "world"}</span>
                      <span>[{wp.x}, {wp.y || 64}, {wp.z}]</span>
                    </div>
                  </div>
                ))
              )}
            </div>

            <div className="pt-2 border-t border-[#23252a] text-[10px] font-mono text-[#62666d] text-center shrink-0">
              Tip: Right-click on map to place a beacon at coordinates
            </div>
          </div>
        )}
      </div>

      {/* Waypoint Creation Modal */}
      {showWaypointModal && (
        <div
          className="fixed inset-0 z-50 flex items-center justify-center p-4 bg-[#08090a]/80 backdrop-blur-sm animate-in fade-in duration-100"
          onClick={() => setShowWaypointModal(false)}
        >
          <div
            className="bg-[#0f1011] border border-[#23252a] rounded-[12px] p-6 w-full max-w-md shadow-2xl space-y-5"
            onClick={(e) => e.stopPropagation()}
          >
            {/* Modal Header */}
            <div className="flex items-center justify-between pb-3 border-b border-[#23252a]">
              <div className="flex items-center gap-2.5">
                <div className="w-7 h-7 rounded-[6px] bg-[#e4f222]/10 border border-[#e4f222]/30 flex items-center justify-center text-[#e4f222]">
                  <MapPin className="w-4 h-4 text-[#e4f222]" />
                </div>
                <div>
                  <h3 className="text-sm font-medium text-[#ffffff]">Place Tactical Waypoint</h3>
                  <p className="text-[11px] font-mono text-[#8a8f98]">
                    {world} · [{newWaypoint.x}, {newWaypoint.y}, {newWaypoint.z}]
                  </p>
                </div>
              </div>
              <button
                type="button"
                onClick={() => setShowWaypointModal(false)}
                className="text-[#8a8f98] hover:text-[#ffffff] text-sm p-1 rounded hover:bg-white/[0.04] transition-colors cursor-pointer"
              >
                <X className="w-4 h-4" />
              </button>
            </div>

            {/* Form */}
            <form onSubmit={handleCreateWaypoint} className="space-y-4">
              {/* Waypoint Name */}
              <div className="space-y-1.5">
                <label className="block text-xs font-mono text-[#8a8f98]">
                  WAYPOINT NAME / IDENTIFIER <span className="text-[#eb5757]">*</span>
                </label>
                <input
                  type="text"
                  autoFocus
                  value={newWaypoint.name}
                  onChange={(e) => setNewWaypoint((prev) => ({ ...prev, name: e.target.value }))}
                  placeholder="e.g. Forward Outpost, Iron Farm, Nether Portal"
                  className="w-full px-3 py-2 bg-[#161718] border border-[#23252a] focus:border-[#d0d6e0] rounded-[6px] text-xs text-[#ffffff] outline-none transition-all placeholder:text-[#62666d]"
                  maxLength={32}
                />
              </div>

              {/* Color Picker with Beacon Palette */}
              <div className="space-y-1.5">
                <label className="block text-xs font-mono text-[#8a8f98]">
                  BEACON BEAM COLOR
                </label>
                <div className="grid grid-cols-7 gap-2">
                  {[
                    { color: "#e4f222", name: "Acid Lime" },
                    { color: "#27a644", name: "Pulse Green" },
                    { color: "#02b8cc", name: "Signal Teal" },
                    { color: "#6366f1", name: "Iris Violet" },
                    { color: "#eb5757", name: "Ruby Red" },
                    { color: "#f59e0b", name: "Amber Gold" },
                    { color: "#ffffff", name: "Paper White" },
                  ].map((item) => {
                    const isSelected = newWaypoint.color === item.color;
                    return (
                      <button
                        key={item.color}
                        type="button"
                        onClick={() => setNewWaypoint((prev) => ({ ...prev, color: item.color }))}
                        title={item.name}
                        className={`h-8 rounded-[6px] transition-all flex items-center justify-center cursor-pointer border ${
                          isSelected
                            ? "border-white ring-2 ring-white/20 scale-105"
                            : "border-white/10 hover:border-white/40"
                        }`}
                        style={{ backgroundColor: item.color }}
                      >
                        {isSelected && (
                          <Check
                            className={`w-3.5 h-3.5 ${
                              item.color === "#ffffff" || item.color === "#e4f222"
                                ? "text-black"
                                : "text-white"
                            }`}
                          />
                        )}
                      </button>
                    );
                  })}
                </div>
              </div>

              {/* Coordinates Fine-tuning */}
              <div className="grid grid-cols-3 gap-2">
                <div className="space-y-1">
                  <label className="block text-[10px] font-mono text-[#8a8f98]">X COORD</label>
                  <input
                    type="number"
                    value={newWaypoint.x}
                    onChange={(e) => setNewWaypoint((prev) => ({ ...prev, x: Number(e.target.value) }))}
                    className="w-full px-2.5 py-1.5 bg-[#161718] border border-[#23252a] rounded-[6px] text-xs font-mono text-[#ffffff] outline-none"
                  />
                </div>
                <div className="space-y-1">
                  <label className="block text-[10px] font-mono text-[#8a8f98]">Y (ELEVATION)</label>
                  <input
                    type="number"
                    value={newWaypoint.y}
                    onChange={(e) => setNewWaypoint((prev) => ({ ...prev, y: Number(e.target.value) }))}
                    className="w-full px-2.5 py-1.5 bg-[#161718] border border-[#23252a] rounded-[6px] text-xs font-mono text-[#ffffff] outline-none"
                  />
                </div>
                <div className="space-y-1">
                  <label className="block text-[10px] font-mono text-[#8a8f98]">Z COORD</label>
                  <input
                    type="number"
                    value={newWaypoint.z}
                    onChange={(e) => setNewWaypoint((prev) => ({ ...prev, z: Number(e.target.value) }))}
                    className="w-full px-2.5 py-1.5 bg-[#161718] border border-[#23252a] rounded-[6px] text-xs font-mono text-[#ffffff] outline-none"
                  />
                </div>
              </div>

              {/* HUD Beacon Preview */}
              <div className="p-3 bg-[#161718] border border-[#23252a] rounded-[6px] flex items-center justify-between">
                <span className="text-[10px] font-mono text-[#62666d]">HUD BEACON PREVIEW</span>
                <div className="flex items-center gap-2 px-2.5 py-1 rounded-[4px] bg-[#08090a] border border-[#23252a]">
                  <div
                    className="w-2.5 h-2.5 rounded-full"
                    style={{ backgroundColor: newWaypoint.color }}
                  />
                  <span className="text-xs font-medium text-[#ffffff]">
                    {newWaypoint.name.trim() || "Waypoint Name"}
                  </span>
                  <span className="text-[10px] font-mono text-[#8a8f98]">
                    [{newWaypoint.x}, {newWaypoint.y}, {newWaypoint.z}]
                  </span>
                </div>
              </div>

              {/* Modal Actions */}
              <div className="flex items-center justify-end gap-2 pt-2">
                <button
                  type="button"
                  onClick={() => setShowWaypointModal(false)}
                  className="px-4 py-2 rounded-[6px] border border-[#23252a] text-[#d0d6e0] hover:text-[#ffffff] hover:border-[#383b3f] text-xs cursor-pointer transition-all"
                >
                  Cancel
                </button>
                <button
                  type="submit"
                  disabled={!newWaypoint.name.trim()}
                  className="px-4 py-2 rounded-[6px] bg-[#e4f222] text-[#08090a] font-medium text-xs flex items-center gap-1.5 cursor-pointer hover:brightness-110 transition-all disabled:opacity-50 disabled:cursor-not-allowed"
                >
                  <MapPin className="w-3.5 h-3.5" />
                  <span>Place Waypoint</span>
                </button>
              </div>
            </form>
          </div>
        </div>
      )}
    </div>
  );
}
