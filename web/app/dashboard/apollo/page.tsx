"use client";

import { toast } from "@/lib/toast";
import { useState, useEffect } from "react";
import { fetchApi } from "@/lib/api";

type Waypoint = {
  player: string;
  name: string;
  world: string;
  x: number;
  y: number;
  z: number;
  color?: string;
};

export default function ApolloPage() {
  const [isAvailable, setIsAvailable] = useState<boolean | null>(null);
  const [waypoints, setWaypoints] = useState<Waypoint[]>([]);
  
  const [newWaypoint, setNewWaypoint] = useState({ name: "", world: "world", x: 0, y: 0, z: 0, color: "#ffffff" });
  
  const [titleForm, setTitleForm] = useState({ player: "", title: "", subtitle: "" });
  const [xrayForm, setXrayForm] = useState({ player: "", enable: true });

  const [loading, setLoading] = useState(true);

  useEffect(() => {
    loadWaypoints();
  }, []);

  const loadWaypoints = async () => {
    try {
      setLoading(true);
      const data = await fetchApi("/api/apollo/waypoints");
      setWaypoints(data);
      setIsAvailable(true);
    } catch (e: any) {
      if (e.message === "APOLLO_NOT_AVAILABLE") {
        setIsAvailable(false);
      }
    } finally {
      setLoading(false);
    }
  };

  const createWaypoint = async (e: React.FormEvent) => {
    e.preventDefault();
    try {
      await fetchApi("/api/apollo/waypoint", {
        method: "POST",
        body: JSON.stringify({ ...newWaypoint, player: "all" }), // Server side ignores player in persistence but expects it
      });
      setNewWaypoint({ name: "", world: "world", x: 0, y: 0, z: 0, color: "#ffffff" });
      loadWaypoints();
    } catch (e: any) {
      alert("Error creating waypoint: " + e.message);
    }
  };

  const deleteWaypoint = async (name: string) => {
    try {
      await fetchApi(`/api/apollo/waypoint?name=${encodeURIComponent(name)}`, {
        method: "DELETE",
      });
      loadWaypoints();
    } catch (e: any) {
      alert("Error deleting waypoint: " + e.message);
    }
  };

  const sendTitle = async (e: React.FormEvent) => {
    e.preventDefault();
    try {
      await fetchApi("/api/apollo/title", {
        method: "POST",
        body: JSON.stringify(titleForm),
      });
      toast.error("Title sent!");
    } catch (e: any) {
      alert("Error sending title: " + e.message);
    }
  };

  const setXray = async (e: React.FormEvent) => {
    e.preventDefault();
    try {
      await fetchApi("/api/apollo/xray", {
        method: "POST",
        body: JSON.stringify(xrayForm),
      });
      toast.error(`X-Ray ${xrayForm.enable ? "enabled" : "disabled"} for ${xrayForm.player}`);
    } catch (e: any) {
      alert("Error: " + e.message);
    }
  };

  if (loading) {
    return <div className="p-8 text-vbg-text-primary">Loading Apollo...</div>;
  }

  if (isAvailable === false) {
    return (
      <div className="p-8">
        <div className="max-w-2xl mx-auto bg-vbg-surface-primary border border-[var(--color-obsidian)] rounded-lg p-6">
          <h2 className="text-xl font-semibold text-[var(--color-coral-red)] mb-2">Apollo Not Available</h2>
          <p className="text-vbg-text-secondary">
            Lunar Client Apollo is not installed on the server. Please install Apollo in the plugins folder and restart the server to use these features.
          </p>
        </div>
      </div>
    );
  }

  return (
    <div className="space-y-8">
      <div>
        <h1 className="text-3xl font-bold text-vbg-text-primary mb-2">Lunar Client Apollo</h1>
        <p className="text-vbg-text-secondary">Manage Lunar Client features for your players.</p>
      </div>

      <div className="grid grid-cols-1 md:grid-cols-2 gap-8">
        {/* Waypoints */}
        <div className="bg-vbg-surface-primary border border-[var(--color-obsidian)] rounded-lg p-6">
          <h2 className="text-xl font-semibold text-vbg-text-primary mb-4">Server Waypoints</h2>
          <form onSubmit={createWaypoint} className="space-y-4 mb-6">
            <div className="grid grid-cols-2 gap-4">
              <div>
                <label htmlFor="wp-name" className="block text-xs text-vbg-text-secondary mb-1">Waypoint Name</label>
                <input id="wp-name" aria-label="Waypoint Name" placeholder="Name" className="w-full bg-vbg-surface-secondary text-vbg-text-primary rounded p-2" value={newWaypoint.name} onChange={e => setNewWaypoint({...newWaypoint, name: e.target.value})} required />
              </div>
              <div>
                <label htmlFor="wp-world" className="block text-xs text-vbg-text-secondary mb-1">World</label>
                <input id="wp-world" aria-label="World Name" placeholder="World" className="w-full bg-vbg-surface-secondary text-vbg-text-primary rounded p-2" value={newWaypoint.world} onChange={e => setNewWaypoint({...newWaypoint, world: e.target.value})} required />
              </div>
              <div>
                <label htmlFor="wp-x" className="block text-xs text-vbg-text-secondary mb-1">X Coordinate</label>
                <input id="wp-x" aria-label="X Coordinate" type="number" placeholder="X" className="w-full bg-vbg-surface-secondary text-vbg-text-primary rounded p-2" value={newWaypoint.x} onChange={e => setNewWaypoint({...newWaypoint, x: parseInt(e.target.value) || 0})} required />
              </div>
              <div>
                <label htmlFor="wp-y" className="block text-xs text-vbg-text-secondary mb-1">Y Coordinate</label>
                <input id="wp-y" aria-label="Y Coordinate" type="number" placeholder="Y" className="w-full bg-vbg-surface-secondary text-vbg-text-primary rounded p-2" value={newWaypoint.y} onChange={e => setNewWaypoint({...newWaypoint, y: parseInt(e.target.value) || 0})} required />
              </div>
              <div>
                <label htmlFor="wp-z" className="block text-xs text-vbg-text-secondary mb-1">Z Coordinate</label>
                <input id="wp-z" aria-label="Z Coordinate" type="number" placeholder="Z" className="w-full bg-vbg-surface-secondary text-vbg-text-primary rounded p-2" value={newWaypoint.z} onChange={e => setNewWaypoint({...newWaypoint, z: parseInt(e.target.value) || 0})} required />
              </div>
              <div>
                <label htmlFor="wp-color" className="block text-xs text-vbg-text-secondary mb-1">Waypoint Color</label>
                <input id="wp-color" aria-label="Waypoint Color" type="color" className="bg-vbg-surface-secondary h-[40px] w-full rounded p-1 cursor-pointer" value={newWaypoint.color} onChange={e => setNewWaypoint({...newWaypoint, color: e.target.value})} />
              </div>
            </div>
            <button type="submit" className="w-full bg-teal-500 hover:bg-teal-500/80 text-vbg-text-primary font-medium py-2 px-4 rounded">Create Waypoint</button>
          </form>

          <div className="space-y-2">
            {waypoints.length === 0 && <p className="text-[var(--color-ash)]">No waypoints configured.</p>}
            {waypoints.map(w => (
              <div key={w.name} className="flex items-center justify-between bg-vbg-surface-secondary p-3 rounded">
                <div>
                  <div className="text-vbg-text-primary font-medium flex items-center gap-2">
                    {w.name}
                    <div className="w-3 h-3 rounded-full" style={{ backgroundColor: w.color || '#fff' }}></div>
                  </div>
                  <div className="text-sm text-vbg-text-secondary">{w.world} ({w.x}, {w.y}, {w.z})</div>
                </div>
                <button onClick={() => deleteWaypoint(w.name)} aria-label={`Delete waypoint ${w.name}`} className="text-[var(--color-coral-red)] hover:text-[var(--color-coral-red)]">Delete</button>
              </div>
            ))}
          </div>
        </div>

        <div className="space-y-8">
          {/* Titles */}
          <div className="bg-vbg-surface-primary border border-[var(--color-obsidian)] rounded-lg p-6">
            <h2 className="text-xl font-semibold text-vbg-text-primary mb-4">Send Title</h2>
            <form onSubmit={sendTitle} className="space-y-4">
              <div>
                <label htmlFor="title-player" className="block text-xs text-vbg-text-secondary mb-1">Player Name</label>
                <input id="title-player" aria-label="Target Player Name" placeholder="Player Name" className="w-full bg-vbg-surface-secondary text-vbg-text-primary rounded p-2" value={titleForm.player} onChange={e => setTitleForm({...titleForm, player: e.target.value})} required />
              </div>
              <div>
                <label htmlFor="title-text" className="block text-xs text-vbg-text-secondary mb-1">Title Text</label>
                <input id="title-text" aria-label="Title Text" placeholder="Title" className="w-full bg-vbg-surface-secondary text-vbg-text-primary rounded p-2" value={titleForm.title} onChange={e => setTitleForm({...titleForm, title: e.target.value})} />
              </div>
              <div>
                <label htmlFor="title-sub" className="block text-xs text-vbg-text-secondary mb-1">Subtitle Text</label>
                <input id="title-sub" aria-label="Subtitle Text" placeholder="Subtitle" className="w-full bg-vbg-surface-secondary text-vbg-text-primary rounded p-2" value={titleForm.subtitle} onChange={e => setTitleForm({...titleForm, subtitle: e.target.value})} />
              </div>
              <button type="submit" className="w-full bg-[var(--color-lavender)] hover:bg-[var(--color-lavender)]/80 text-vbg-text-primary font-medium py-2 px-4 rounded">Send Title</button>
            </form>
          </div>

          {/* X-Ray */}
          <div className="bg-vbg-surface-primary border border-[var(--color-obsidian)] rounded-lg p-6">
            <h2 className="text-xl font-semibold text-vbg-text-primary mb-4">Staff Mods (X-Ray)</h2>
            <form onSubmit={setXray} className="space-y-4">
              <div>
                <label htmlFor="xray-player" className="block text-xs text-vbg-text-secondary mb-1">Player Name</label>
                <input id="xray-player" aria-label="X-Ray Player Name" placeholder="Player Name" className="w-full bg-vbg-surface-secondary text-vbg-text-primary rounded p-2" value={xrayForm.player} onChange={e => setXrayForm({...xrayForm, player: e.target.value})} required />
              </div>
              <fieldset>
                <legend className="block text-xs text-vbg-text-secondary mb-2">X-Ray Status</legend>
                <div className="flex items-center space-x-4 text-vbg-text-primary">
                  <label className="flex items-center space-x-2 cursor-pointer">
                    <input type="radio" name="xray" aria-label="Enable X-Ray" checked={xrayForm.enable} onChange={() => setXrayForm({...xrayForm, enable: true})} />
                    <span>Enable</span>
                  </label>
                  <label className="flex items-center space-x-2 cursor-pointer">
                    <input type="radio" name="xray" aria-label="Disable X-Ray" checked={!xrayForm.enable} onChange={() => setXrayForm({...xrayForm, enable: false})} />
                    <span>Disable</span>
                  </label>
                </div>
              </fieldset>
              <button type="submit" className="w-full bg-red-500 hover:bg-red-500/80 text-vbg-text-primary font-medium py-2 px-4 rounded">Update X-Ray Status</button>
            </form>
          </div>
        </div>
      </div>
    </div>
  );
}

