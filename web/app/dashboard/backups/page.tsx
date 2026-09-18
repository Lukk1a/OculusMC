"use client";

import { toast } from "@/lib/toast";
import { useEffect, useState } from "react";
import { fetchApi, getToken } from "@/lib/api";
import { Loader2, Archive, Play, Download, Clock } from "lucide-react";

export default function BackupsPage() {
  const [backups, setBackups] = useState<any[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [creating, setCreating] = useState(false);

  const loadBackups = async () => {
    setLoading(true);
    setError(null);
    try {
      const res = await fetchApi("/api/backups");
      setBackups(res);
    } catch (err: any) {
      setError(err.message || "Failed to load backups");
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    loadBackups();
  }, []);

  const createBackup = async () => {
    setCreating(true);
    try {
      await fetchApi("/api/backups/create", { method: "POST" });
      toast.success("Backup started! It will run in the background.");
      // Check again after a few seconds
      setTimeout(loadBackups, 3000);
    } catch (err: any) {
      toast.error("Failed to start backup: " + err.message);
    } finally {
      setCreating(false);
    }
  };

  const downloadBackup = async (filename: string) => {
    try {
      const token = getToken();
      const headers: Record<string, string> = {};
      if (token) {
        headers["Authorization"] = `Bearer ${token}`;
      }
      const res = await fetch(`/api/backups/${encodeURIComponent(filename)}`, {
        headers
      });
      if (!res.ok) {
        throw new Error(`Download failed: ${res.statusText}`);
      }
      const blob = await res.blob();
      const url = window.URL.createObjectURL(blob);
      const a = document.createElement("a");
      a.href = url;
      a.download = filename;
      document.body.appendChild(a);
      a.click();
      a.remove();
      window.URL.revokeObjectURL(url);
    } catch (err: any) {
      alert("Failed to download: " + err.message);
    }
  };

  return (
    <div className="space-y-6">
      <div className="flex items-center justify-between mb-8">
        <div>
          <h1 className="text-2xl font-semibold text-vbg-text-primary tracking-tight">Backups</h1>
          <p className="text-sm text-vbg-text-secondary mt-1">Manage automated and manual server backups</p>
        </div>
        
        <button 
          onClick={createBackup}
          disabled={creating}
          className="bg-vbg-surface-contrast text-vbg-text-on-contrast rounded-vbg px-3 py-1.5 text-sm font-medium hover:bg-neutral-200 transition-colors flex items-center gap-2"
        >
          {creating ? <Loader2 className="w-4 h-4 animate-spin" /> : <Play className="w-4 h-4" />}
          {creating ? "Creating..." : "Create Backup Now"}
        </button>
      </div>

      <div className="bg-vbg-surface-secondary border border-vbg-border-subtle rounded-vbg  overflow-hidden">
        {error && (
          <div className="p-4 text-[var(--color-coral-red)] bg-red-400/10 border-b border-red-500/20 text-sm">
            {error}
          </div>
        )}

        {loading ? (
          <div className="p-8 text-center text-vbg-text-secondary text-sm flex items-center justify-center gap-2">
            <Loader2 className="w-4 h-4 animate-spin" /> Loading backups...
          </div>
        ) : backups.length === 0 ? (
          <div className="p-12 text-center flex flex-col items-center justify-center text-vbg-text-secondary">
            <Archive className="w-12 h-12 mb-4 opacity-20" />
            <p>No backups found</p>
            <p className="text-xs mt-1">Click "Create Backup Now" to start your first backup</p>
          </div>
        ) : (
          <table className="w-full text-sm text-left">
            <thead>
              <tr className="border-b border-vbg-border-subtle text-vbg-text-secondary">
                <th className="font-normal px-6 py-4">Filename</th>
                <th className="font-normal px-6 py-4 w-32">Size</th>
                <th className="font-normal px-6 py-4 w-48">Date Created</th>
                <th className="font-normal px-6 py-4 w-24">Actions</th>
              </tr>
            </thead>
            <tbody>
              {backups.map(b => (
                <tr key={b.name} className="border-b border-vbg-border-subtle/50 hover:bg-[--color-obsidian]/50 transition-colors">
                  <td className="px-6 py-4 font-medium text-vbg-text-primary flex items-center gap-3">
                    <Archive className="w-4 h-4 text-[var(--color-signal-teal)]" />
                    {b.name}
                  </td>
                  <td className="px-6 py-4 text-vbg-text-secondary">
                    {(b.size / 1024 / 1024).toFixed(2)} MB
                  </td>
                  <td className="px-6 py-4 text-vbg-text-secondary flex items-center gap-2">
                    <Clock className="w-3 h-3" />
                    {new Date(b.lastModified).toLocaleString()}
                  </td>
                  <td className="px-6 py-4">
                    <button 
                      onClick={() => downloadBackup(b.name)}
                      className="text-vbg-text-secondary hover:text-vbg-text-primary flex items-center gap-1"
                    >
                      <Download className="w-4 h-4" /> Download
                    </button>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </div>
    </div>
  );
}
