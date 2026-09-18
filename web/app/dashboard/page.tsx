"use client";

import { useEffect, useState } from "react";
import { telemetryWs } from "@/lib/ws";
import {
  LineChart,
  Line,
  XAxis,
  YAxis,
  Tooltip,
  ResponsiveContainer,
  CartesianGrid,
} from "recharts";

export default function DashboardPage() {
  const [dataPoints, setDataPoints] = useState<any[]>([{ time: "00:00:00", tps: 20, cpu: 0, memory: 0 }]);
  const [kpis, setKpis] = useState({
    players: 0,
    maxPlayers: 0,
    tps: "20.0",
    cpu: "0.0%",
    memory: "0 MB",
    dau: 0,
    mau: 0,
    deaths: 0,
    loadedChunks: 0,
    totalEntities: 0,
  });

  useEffect(() => {
    telemetryWs.connect();

    const unsubscribe = telemetryWs.subscribe((msg) => {
      if (msg.type === "telemetry") {
        const now = new Date().toLocaleTimeString([], {
          hour: "2-digit",
          minute: "2-digit",
          second: "2-digit",
        });

        const currentTps = msg.tps?.[0]?.toFixed(1) || "20.0";
        const currentCpu = msg.cpuLoad
          ? (msg.cpuLoad * 100).toFixed(1) + "%"
          : "0.0%";
        const heapUsedMb = msg.heapUsed
          ? (msg.heapUsed / 1024 / 1024).toFixed(0)
          : "0";
        const heapMaxMb = msg.heapMax
          ? (msg.heapMax / 1024 / 1024).toFixed(0)
          : "0";

        setKpis({
          players: msg.onlinePlayers || 0,
          maxPlayers: msg.maxPlayers || 0,
          tps: currentTps,
          cpu: currentCpu,
          memory: `${heapUsedMb} MB / ${heapMaxMb} MB`,
          dau: msg.dau || 0,
          mau: msg.mau || 0,
          deaths: msg.deaths || 0,
          loadedChunks: msg.loadedChunks || 0,
          totalEntities: msg.totalEntities || 0,
        });

        setDataPoints((prev) => {
          const newPoints = [
            ...prev,
            {
              time: now,
              tps: parseFloat(currentTps),
              cpu: msg.cpuLoad ? parseFloat((msg.cpuLoad * 100).toFixed(1)) : 0,
              memory: msg.heapUsed ? msg.heapUsed / 1024 / 1024 : 0,
            },
          ];
          if (newPoints.length > 60) {
            newPoints.shift();
          }
          return newPoints;
        });
      }
    });

    return () => {
      unsubscribe();
      telemetryWs.disconnect();
    };
  }, []);

  return (
    <div className="space-y-12">
      <div className="flex flex-col gap-2">
        <h1 className="text-2xl font-semibold tracking-tight text-foreground">
          Overview
        </h1>
        <p className="text-muted-foreground text-sm">
          Real-time telemetry and performance metrics.
        </p>
      </div>

      {/* Minimalist Data Grid, no individual cards */}
      <div>
        <h2 className="text-sm font-medium border-b border-border pb-2 mb-4">Key Performance Indicators</h2>
        <div className="grid grid-cols-2 lg:grid-cols-4 gap-y-8 gap-x-4">
          <div>
            <div className="text-sm text-muted-foreground mb-1">Players</div>
              <div className="text-2xl text-foreground font-mono">
                {kpis.players} <span className="text-muted-foreground">/</span> {kpis.maxPlayers}
              </div>
            </div>
            <div>
              <div className="text-sm text-muted-foreground mb-1">TPS</div>
              <div className="text-2xl text-foreground font-mono">
                {kpis.tps}
              </div>
            </div>
            <div>
              <div className="text-sm text-muted-foreground mb-1">CPU Load</div>
              <div className="text-2xl text-foreground font-mono">
                {kpis.cpu}
              </div>
            </div>
            <div>
              <div className="text-sm text-muted-foreground mb-1">Memory</div>
              <div className="text-2xl text-foreground font-mono">
                {kpis.memory.split(" / ")[0]} <span className="text-muted-foreground">/ {kpis.memory.split(" / ")[1]}</span>
              </div>
            </div>
            <div>
              <div className="text-sm text-muted-foreground mb-1">Loaded Chunks</div>
              <div className="text-2xl text-foreground font-mono">
                {kpis.loadedChunks.toLocaleString()}
              </div>
            </div>
            <div>
              <div className="text-sm text-muted-foreground mb-1">Entities</div>
              <div className="text-2xl text-foreground font-mono">
                {kpis.totalEntities.toLocaleString()}
              </div>
            </div>
            <div>
              <div className="text-sm text-muted-foreground mb-1">Active Users</div>
              <div className="text-2xl text-foreground font-mono">
                {kpis.dau} <span className="text-sm text-muted-foreground font-sans">DAU</span> <span className="text-muted-foreground">/</span> {kpis.mau} <span className="text-sm text-muted-foreground font-sans">MAU</span>
              </div>
            </div>
            <div>
              <div className="text-sm text-muted-foreground mb-1">Deaths</div>
              <div className="text-2xl text-foreground font-mono">
                {kpis.deaths.toLocaleString()}
              </div>
            </div>
          </div>
        </div>

        <div className="grid grid-cols-1 gap-8 lg:grid-cols-2">
          {/* TPS & CPU Chart */}
          <div className="flex flex-col border border-border bg-card p-6 rounded-md">
            <div className="mb-6">
              <h3 className="text-sm font-medium">TPS & CPU</h3>
              <p className="text-sm text-muted-foreground">Server ticks per second and CPU utilization</p>
            </div>
            <div className="h-[250px] w-full">
              <ResponsiveContainer width="100%" height="100%">
                <LineChart
                  data={dataPoints}
                  margin={{ top: 5, right: 10, left: -20, bottom: 0 }}
                >
                  <CartesianGrid
                    strokeDasharray="3 3"
                    stroke="var(--color-border)"
                    vertical={false}
                  />
                  <XAxis
                    dataKey="time"
                    stroke="var(--color-muted-foreground)"
                    fontSize={12}
                    tickLine={false}
                    axisLine={false}
                    minTickGap={30}
                  />
                  <YAxis
                    yAxisId="left"
                    stroke="var(--color-muted-foreground)"
                    fontSize={12}
                    domain={[0, 20]}
                    tickLine={false}
                    axisLine={false}
                  />
                  <YAxis
                    yAxisId="right"
                    orientation="right"
                    stroke="var(--color-muted-foreground)"
                    fontSize={12}
                    domain={[0, 100]}
                    tickLine={false}
                    axisLine={false}
                  />
                  <Tooltip
                    contentStyle={{
                      backgroundColor: "var(--color-background)",
                      border: "1px solid var(--color-border)",
                      borderRadius: "4px",
                      color: "var(--color-foreground)",
                      fontFamily: "var(--font-mono)",
                    }}
                    itemStyle={{ color: "var(--color-foreground)" }}
                  />
                  {/* Using green for good health (TPS) and neutral for CPU */}
                  <Line
                    yAxisId="left"
                    type="stepAfter"
                    dataKey="tps"
                    stroke="#10b981"
                    strokeWidth={1.5}
                    dot={false}
                    isAnimationActive={false}
                  />
                  <Line
                    yAxisId="right"
                    type="monotone"
                    dataKey="cpu"
                    stroke="#a1a1aa"
                    strokeWidth={1.5}
                    dot={false}
                    isAnimationActive={false}
                  />
                </LineChart>
              </ResponsiveContainer>
            </div>
          </div>

          {/* Memory Chart */}
          <div className="flex flex-col border border-border bg-card p-6 rounded-md">
            <div className="mb-6">
              <h3 className="text-sm font-medium">Memory Usage</h3>
              <p className="text-sm text-muted-foreground">JVM Heap Utilization (MB)</p>
            </div>
            <div className="h-[250px] w-full">
              <ResponsiveContainer width="100%" height="100%">
                <LineChart
                  data={dataPoints}
                  margin={{ top: 5, right: 10, left: -20, bottom: 0 }}
                >
                  <CartesianGrid
                    strokeDasharray="3 3"
                    stroke="var(--color-border)"
                    vertical={false}
                  />
                  <XAxis
                    dataKey="time"
                    stroke="var(--color-muted-foreground)"
                    fontSize={12}
                    tickLine={false}
                    axisLine={false}
                    minTickGap={30}
                  />
                  <YAxis
                    stroke="var(--color-muted-foreground)"
                    fontSize={12}
                    tickLine={false}
                    axisLine={false}
                  />
                  <Tooltip
                    contentStyle={{
                      backgroundColor: "var(--color-background)",
                      border: "1px solid var(--color-border)",
                      borderRadius: "4px",
                      color: "var(--color-foreground)",
                      fontFamily: "var(--font-mono)",
                    }}
                    itemStyle={{ color: "var(--color-foreground)" }}
                  />
                  {/* Flat monochrome line for memory */}
                  <Line
                    type="monotone"
                    dataKey="memory"
                    stroke="#ededed"
                    strokeWidth={1.5}
                    dot={false}
                    isAnimationActive={false}
                  />
                </LineChart>
              </ResponsiveContainer>
            </div>
        </div>
      </div>
    </div>
  );
}
