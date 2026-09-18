"use client";

import { useState, useEffect } from "react";
import { fetchApi } from "@/lib/api";
import { PlayerSkin3D } from "./PlayerSkin3D";
import { Card, CardContent, CardFooter } from "@/components/ui/card";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Search, MapPin, Heart, Drumstick, ShieldBan, ShieldAlert, PackageOpen, Zap, Signal, Loader2 } from "lucide-react";
import { toast } from "@/lib/toast";
import { Input } from "@/components/ui/input";
import { Separator } from "@/components/ui/separator";

type Player = {
  name: string;
  uuid: string;
  world: string;
  x: number;
  y: number;
  z: number;
  ping: number;
  health: number;
  maxHealth: number;
  food: number;
  gamemode: string;
};

export default function PlayersPage() {
  const [players, setPlayers] = useState<Player[]>([]);
  const [search, setSearch] = useState("");
  const [loading, setLoading] = useState(true);

  const loadPlayers = async () => {
    try {
      const data = await fetchApi("/api/players/detailed");
      if (data) {
        setPlayers(data);
      }
    } catch (e) {
      console.error(e);
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    loadPlayers();
    const interval = setInterval(loadPlayers, 5000);
    return () => clearInterval(interval);
  }, []);

  const handleAction = async (playerName: string, action: string) => {
    try {
      await fetchApi("/api/player/action", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ name: playerName, action }),
      });
      toast.success(`Action '${action}' executed on ${playerName}`);
    } catch (e: any) {
      toast.error(e.message || "Failed to execute action");
    }
  };

  const filtered = (Array.isArray(players) ? players : []).filter(p => 
      p.name.toLowerCase().includes(search.toLowerCase()) || 
      p.uuid.toLowerCase().includes(search.toLowerCase())
    );

  return (
    <div className="space-y-8 font-sans">
      {/* Header section */}
      <div className="flex flex-col md:flex-row justify-between items-start md:items-center gap-4">
        <div>
          <h1 className="text-3xl font-semibold tracking-tight text-foreground">Players</h1>
          <p className="text-muted-foreground mt-1">Manage online players in real-time.</p>
        </div>
        <div className="flex items-center gap-3">
          <Badge variant="outline" className="text-sm px-3 py-1 bg-card border-border text-foreground font-mono">
            <span className="w-2 h-2 rounded-full bg-emerald-500 mr-2 inline-block"></span>
            {players.length} Online
          </Badge>
          <div className="relative">
            <Search className="w-4 h-4 absolute left-3 top-1/2 -translate-y-1/2 text-muted-foreground" />
            <Input 
              type="text" 
              placeholder="Search players..."
              value={search}
              onChange={e => setSearch(e.target.value)}
              className="pl-9 w-64 bg-card border-border text-foreground focus-visible:ring-neutral-700"
            />
          </div>
        </div>
      </div>

      <Separator className="bg-secondary" />

      {/* Grid */}
      {loading ? (
        <div className="flex justify-center items-center py-20 text-muted-foreground">
          <Loader2 className="animate-spin w-5 h-5 mr-3" />
          <span className="text-sm">Scanning network...</span>
        </div>
      ) : filtered.length === 0 ? (
        <div className="text-center py-20 text-muted-foreground border border-border border-dashed rounded-md bg-card">
          No players match your search.
        </div>
      ) : (
        <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 xl:grid-cols-4 gap-4">
          {filtered.map(p => (
            <Card key={p.uuid} className="bg-card border-border overflow-hidden flex flex-col rounded-md  hover:border-border transition-colors duration-200">
              <div className="h-28 bg-card relative border-b border-border flex justify-center pt-6">
                {/* 3D Skin Render */}
                <div className="absolute -bottom-6">
                  <PlayerSkin3D name={p.name} width={56} height={88} />
                </div>
                <div className="absolute top-3 right-3 flex items-center gap-1.5 text-xs font-mono text-muted-foreground bg-card border border-border px-2 py-0.5 rounded-sm">
                  <Signal className="w-3 h-3 text-emerald-500" /> {p.ping}ms
                </div>
              </div>

              <CardContent className="pt-10 pb-4 flex-1">
                <div className="text-center mb-5">
                  <h3 className="text-base font-semibold text-foreground">{p.name}</h3>
                  <p className="text-[11px] text-muted-foreground font-mono mt-0.5">{p.uuid}</p>
                </div>

                <div className="space-y-4">
                  <div className="flex items-center justify-between text-sm">
                    <div className="flex items-center text-muted-foreground gap-2">
                      <MapPin className="w-4 h-4 text-muted-foreground" />
                      <span>{p.world}</span>
                    </div>
                    <span className="font-mono text-xs text-muted-foreground">
                      {Math.round(p.x)}, {Math.round(p.y)}, {Math.round(p.z)}
                    </span>
                  </div>

                  <div className="space-y-2.5">
                    <div className="flex items-center gap-3">
                      <Heart className="w-4 h-4 text-muted-foreground" />
                      <div className="h-1 flex-1 bg-neutral-900 overflow-hidden">
                        <div className="h-full bg-neutral-400" style={{ width: `${(p.health / Math.max(p.maxHealth, 1)) * 100}%` }}></div>
                      </div>
                      <span className="text-xs font-mono text-muted-foreground w-6 text-right">{p.health}</span>
                    </div>
                    
                    <div className="flex items-center gap-3">
                      <Drumstick className="w-4 h-4 text-muted-foreground" />
                      <div className="h-1 flex-1 bg-neutral-900 overflow-hidden">
                        <div className="h-full bg-neutral-400" style={{ width: `${(p.food / 20) * 100}%` }}></div>
                      </div>
                      <span className="text-xs font-mono text-muted-foreground w-6 text-right">{p.food}</span>
                    </div>
                  </div>
                </div>
              </CardContent>

              <CardFooter className="bg-card border-t border-border p-2 grid grid-cols-4 gap-1">
                <Button variant="ghost" size="icon" onClick={() => handleAction(p.name, 'kick')} title="Kick" className="h-8 w-full text-muted-foreground hover:text-red-400 hover:bg-secondary rounded-sm">
                  <ShieldAlert className="w-4 h-4" />
                </Button>
                <Button variant="ghost" size="icon" onClick={() => handleAction(p.name, 'ban')} title="Ban" className="h-8 w-full text-muted-foreground hover:text-red-500 hover:bg-secondary rounded-sm">
                  <ShieldBan className="w-4 h-4" />
                </Button>
                <Button variant="ghost" size="icon" onClick={() => handleAction(p.name, 'pdc')} title="Edit PDC" className="h-8 w-full text-muted-foreground hover:text-foreground hover:bg-secondary rounded-sm">
                  <Zap className="w-4 h-4" />
                </Button>
                <Button variant="ghost" size="icon" onClick={() => handleAction(p.name, 'inventory')} title="Inventory" className="h-8 w-full text-muted-foreground hover:text-foreground hover:bg-secondary rounded-sm">
                  <PackageOpen className="w-4 h-4" />
                </Button>
              </CardFooter>
            </Card>
          ))}
        </div>
      )}
    </div>
  );
}
