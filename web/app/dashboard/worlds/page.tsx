"use client";

import { useEffect, useState } from "react";
import { fetchApi } from "@/lib/api";
import { Globe, Loader2, CloudRain, CloudLightning, Sun, Moon, Clock, Settings, ShieldAlert } from "lucide-react";
import { Card, CardContent, CardHeader, CardTitle, CardDescription } from "@/components/ui/card";
import { Switch } from "@/components/ui/switch";
import { Slider } from "@/components/ui/slider";
import { Button } from "@/components/ui/button";
import { Badge } from "@/components/ui/badge";
import { toast } from "@/lib/toast";
import { Separator } from "@/components/ui/separator";

export default function WorldsPage() {
  const [worlds, setWorlds] = useState<any[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  const loadWorlds = async () => {
    setLoading(true);
    setError(null);
    try {
      const res = await fetchApi("/api/worlds");
      setWorlds(res);
    } catch (err: any) {
      setError(err.message || "Failed to load worlds");
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    loadWorlds();
  }, []);

  const updateWorld = async (name: string, payload: any) => {
    try {
      await fetchApi(`/api/worlds/${encodeURIComponent(name)}/action`, {
        method: "POST",
        headers: {
          "Content-Type": "application/json"
        },
        body: JSON.stringify(payload)
      });
      toast.success(`Updated ${name}`);
      loadWorlds(); // Refresh to get the latest state
    } catch (err: any) {
      toast.error(err.message || "Failed to update world");
    }
  };

  if (loading) {
    return (
      <div className="flex justify-center items-center py-32 text-muted-foreground">
        <Loader2 className="animate-spin w-5 h-5 mr-3" />
        <span className="text-sm">Loading worlds...</span>
      </div>
    );
  }

  if (error) {
    return (
      <div className="p-8 max-w-5xl mx-auto">
        <Card className="border-red-900 bg-card rounded-md ">
          <CardContent className="flex items-center gap-4 pt-6 text-red-500">
            <ShieldAlert className="w-5 h-5" />
            <div>
              <h3 className="font-semibold text-sm">Failed to load</h3>
              <p className="text-sm text-red-400">{error}</p>
            </div>
          </CardContent>
        </Card>
      </div>
    );
  }

  return (
    <div className="space-y-8 font-sans">
      <div className="flex flex-col md:flex-row justify-between items-start md:items-center gap-4">
        <div>
          <h1 className="text-3xl font-semibold tracking-tight text-foreground">Worlds</h1>
          <p className="text-muted-foreground mt-1">Manage time, weather, and gamerules for all dimensions.</p>
        </div>
        <Button onClick={loadWorlds} variant="outline" className="bg-card border-border text-foreground hover:bg-secondary hover:text-foreground rounded-sm">
          Refresh Data
        </Button>
      </div>

      <Separator className="bg-secondary" />

      <div className="grid grid-cols-1 xl:grid-cols-2 gap-6">
        {worlds.map((world: any) => {
          // Calculate time percentage (0 to 24000)
          const timeValue = world.time ? Math.min(24000, Math.max(0, world.time)) : 0;
          
          return (
            <Card key={world.name} className="bg-card border-border  overflow-hidden flex flex-col rounded-md">
              <CardHeader className="bg-card border-b border-border pb-5 pt-5">
                <div className="flex items-center gap-4">
                  <div className="w-10 h-10 bg-card border border-border flex items-center justify-center rounded-sm">
                    <Globe className="w-5 h-5 text-muted-foreground" />
                  </div>
                  <div>
                    <CardTitle className="text-lg font-semibold text-foreground">{world.name}</CardTitle>
                    <CardDescription className="flex items-center gap-2 mt-1">
                      <Badge variant="outline" className="bg-card text-muted-foreground border-border rounded-sm px-2 py-0 text-xs">
                        {world.difficulty || "Normal"}
                      </Badge>
                      <span className="text-xs font-mono text-muted-foreground">
                        Time: {timeValue}
                      </span>
                    </CardDescription>
                  </div>
                </div>
              </CardHeader>
              
              <CardContent className="p-6 grid grid-cols-1 lg:grid-cols-2 gap-8">
                
                {/* Environment Controls */}
                <div className="space-y-6">
                  <div className="flex items-center gap-2 text-foreground font-medium mb-1">
                    <Clock className="w-4 h-4 text-muted-foreground" />
                    <h3 className="text-sm">Environment</h3>
                  </div>

                  {/* Time Slider */}
                  <div className="space-y-3">
                    <div className="flex justify-between items-center text-sm">
                      <span className="text-muted-foreground">Time of Day</span>
                      <span className="font-mono text-xs bg-card border border-border px-2 py-0.5 rounded-sm text-muted-foreground">{timeValue}</span>
                    </div>
                    <Slider 
                      defaultValue={[timeValue]} 
                      max={24000} 
                      step={1000}
                      onValueCommitted={(val) => updateWorld(world.name, { time: typeof val === 'number' ? val : val[0] })}
                      className="my-5"
                    />
                    <div className="flex gap-2">
                      <Button variant="outline" size="sm" className="flex-1 text-xs border-border bg-card text-muted-foreground hover:bg-secondary hover:text-foreground rounded-sm" onClick={() => updateWorld(world.name, { time: 0 })}>
                        <Sun className="w-3 h-3 mr-1.5" /> Day
                      </Button>
                      <Button variant="outline" size="sm" className="flex-1 text-xs border-border bg-card text-muted-foreground hover:bg-secondary hover:text-foreground rounded-sm" onClick={() => updateWorld(world.name, { time: 13000 })}>
                        <Moon className="w-3 h-3 mr-1.5" /> Night
                      </Button>
                    </div>
                  </div>

                  <Separator className="bg-secondary" />

                  {/* Weather Pills */}
                  <div className="space-y-3">
                    <span className="text-sm text-muted-foreground">Weather</span>
                    <div className="flex gap-1.5 bg-card p-1 rounded-sm border border-border">
                      <Button 
                        variant="ghost" 
                        size="sm" 
                        onClick={() => updateWorld(world.name, { storm: false, thunder: false })}
                        className={`flex-1 text-xs rounded-sm h-8 ${!world.hasStorm ? 'bg-card text-foreground  border border-border' : 'text-muted-foreground hover:text-foreground'}`}
                      >
                        <Sun className="w-3.5 h-3.5 mr-1.5" /> Clear
                      </Button>
                      <Button 
                        variant="ghost" 
                        size="sm" 
                        onClick={() => updateWorld(world.name, { storm: true, thunder: false })}
                        className={`flex-1 text-xs rounded-sm h-8 ${world.hasStorm && !world.isThundering ? 'bg-card text-foreground  border border-border' : 'text-muted-foreground hover:text-foreground'}`}
                      >
                        <CloudRain className="w-3.5 h-3.5 mr-1.5" /> Rain
                      </Button>
                      <Button 
                        variant="ghost" 
                        size="sm" 
                        onClick={() => updateWorld(world.name, { storm: true, thunder: true })}
                        className={`flex-1 text-xs rounded-sm h-8 ${world.isThundering ? 'bg-card text-foreground  border border-border' : 'text-muted-foreground hover:text-foreground'}`}
                      >
                        <CloudLightning className="w-3.5 h-3.5 mr-1.5" /> Storm
                      </Button>
                    </div>
                  </div>
                </div>

                {/* Game Rules */}
                <div className="space-y-6">
                  <div className="flex items-center gap-2 text-foreground font-medium mb-1">
                    <Settings className="w-4 h-4 text-muted-foreground" />
                    <h3 className="text-sm">Gamerules</h3>
                  </div>
                  
                  <div className="space-y-4 bg-card p-4 rounded-sm border border-border">
                    {['doDaylightCycle', 'doWeatherCycle', 'keepInventory', 'mobGriefing'].map(rule => {
                      const isEnabled = world.gameRules?.[rule] === 'true';
                      return (
                        <div key={rule} className="flex items-center justify-between">
                          <span className="text-sm text-muted-foreground">{rule}</span>
                          <Switch 
                            checked={isEnabled} 
                            onCheckedChange={(checked) => updateWorld(world.name, { gameRules: { [rule]: checked ? 'true' : 'false' } })}
                            className="data-[state=checked]:bg-emerald-500 data-[state=unchecked]:bg-neutral-700"
                          />
                        </div>
                      )
                    })}
                  </div>
                </div>
                
              </CardContent>
            </Card>
          );
        })}
      </div>
    </div>
  );
}
