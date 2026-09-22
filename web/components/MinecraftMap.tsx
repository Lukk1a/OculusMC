"use client";

import React, { useRef, useState, useEffect, useCallback, useMemo } from 'react';
import {
  ZoomIn,
  ZoomOut,
  Crosshair,
  Layers,
  Ruler,
  RefreshCw,
  Copy,
  Check,
  Users,
  Maximize2,
  Minimize2,
  Navigation,
  MapPin,
  Trash2
} from 'lucide-react';
import { getFreshToken } from '@/lib/api';

export interface PlayerCoord {
  name: string;
  uuid?: string;
  world?: string;
  x: number;
  y: number;
  z: number;
  yaw?: number;
  health?: number;
  ping?: number;
}

export interface Waypoint {
  name: string;
  world: string;
  x: number;
  y: number;
  z: number;
  color?: string;
}

export interface MinecraftMapProps {
  currentDimension?: string;
  onDimensionChange?: (dim: string) => void;
  players?: PlayerCoord[];
  waypoints?: Waypoint[];
  onSelectCoordinates?: (x: number, z: number, dim: string) => void;
  onRequestCreateWaypoint?: (target: { x: number; z: number; world: string; y?: number }) => void;
  onDeleteWaypoint?: (name: string) => void;
  onToggleWaypointDrawer?: () => void;
  waypointCount?: number;
  height?: string | number;
}

const DIMENSIONS = [
  { id: 'world', label: 'Overworld', color: '#27a644' },
  { id: 'world_nether', label: 'Nether', color: '#eb5757' },
  { id: 'world_the_end', label: 'The End', color: '#8b5cf6' },
];

const DIMENSION_PRESETS: Record<string, Array<{ name: string; x: number; z: number }>> = {
  world: [
    { name: 'Spawn (0, 0)', x: 0, z: 0 },
    { name: 'World Border +X', x: 5000, z: 0 },
    { name: 'World Border -X', x: -5000, z: 0 },
  ],
  world_nether: [
    { name: 'Nether Hub (0, 0)', x: 0, z: 0 },
    { name: 'Fortress Sector', x: -240, z: 180 },
    { name: 'Bastion Remnant', x: 320, z: -150 },
  ],
  world_the_end: [
    { name: 'Central Island', x: 0, z: 0 },
    { name: 'End Gateway', x: 96, z: -55 },
    { name: 'End City', x: 1280, z: -940 },
  ],
};

function getEstimatedBiome(x: number, z: number, dim: string): string {
  if (dim === 'world_the_end') {
    const dist = Math.sqrt(x * x + z * z);
    if (dist < 92) return 'The End (Central Island)';
    if (dist < 920) return 'The Void';
    return 'End Highlands';
  }
  if (dim === 'world_nether') {
    return 'Nether Wastes';
  }
  const dist = Math.sqrt(x * x + z * z);
  if (dist < 150) return 'Plains (Spawn)';
  if (x > 200) return 'Forest';
  if (z > 200) return 'River & Ocean';
  return 'Windswept Hills';
}

export default function MinecraftMap({
  currentDimension = 'world',
  onDimensionChange,
  players = [],
  waypoints = [],
  onSelectCoordinates,
  onRequestCreateWaypoint,
  onDeleteWaypoint,
  onToggleWaypointDrawer,
  waypointCount = 0,
  height = '100%'
}: MinecraftMapProps) {
  const canvasRef = useRef<HTMLCanvasElement | null>(null);
  const containerRef = useRef<HTMLDivElement | null>(null);

  // Active Dimension
  const [internalDimension, setInternalDimension] = useState(currentDimension);
  const dimension = onDimensionChange ? currentDimension : internalDimension;

  const handleSetDimension = (dim: string) => {
    if (onDimensionChange) {
      onDimensionChange(dim);
    } else {
      setInternalDimension(dim);
    }
  };

  // Real Server World Map Image Pipeline
  const realMapImgRef = useRef<HTMLImageElement | null>(null);
  const [realMapLoaded, setRealMapLoaded] = useState(false);
  const [realMapLoading, setRealMapLoading] = useState(false);
  const [refreshKey, setRefreshKey] = useState(0);

  // Camera viewport: x, z in world coords, zoom in pixels per block
  const [camera, setCamera] = useState({ x: 0, z: 0, zoom: 0.45 });
  const [isDragging, setIsDragging] = useState(false);
  const [dragStart, setDragStart] = useState({ x: 0, y: 0 });
  const [cursorWorld, setCursorWorld] = useState({ x: 0, z: 0 });
  const [selectedPoint, setSelectedPoint] = useState<{ x: number; z: number } | null>(null);
  const [selectedPlayer, setSelectedPlayer] = useState<PlayerCoord | null>(null);
  const [selectedWaypoint, setSelectedWaypoint] = useState<Waypoint | null>(null);
  const [contextMenu, setContextMenu] = useState<{ x: number; y: number; worldX: number; worldZ: number } | null>(null);
  const [copiedNotification, setCopiedNotification] = useState('');
  const [isFullscreen, setIsFullscreen] = useState(false);

  // High-frequency cursor position stored in ref to prevent state thrashing
  const cursorRef = useRef({ x: 0, z: 0 });
  const throttleTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null);

  // Measure / Ruler Tool State
  const [isMeasuring, setIsMeasuring] = useState(false);
  const [measureA, setMeasureA] = useState<{ x: number; z: number } | null>(null);
  const [measureB, setMeasureB] = useState<{ x: number; z: number } | null>(null);

  // Layer Visibility
  const [layers, setLayers] = useState({
    grid: true,
    chunks: true,
    terrain: true,
    players: true,
    waypoints: true,
  });
  const [showLayerMenu, setShowLayerMenu] = useState(false);

  // Close context menu on outside click
  useEffect(() => {
    const handleGlobalClick = () => setContextMenu(null);
    window.addEventListener('click', handleGlobalClick);
    return () => window.removeEventListener('click', handleGlobalClick);
  }, []);

  // Fetch Actual Minecraft Map from Server
  useEffect(() => {
    let isMounted = true;
    let currentObjectUrl: string | null = null;

    const loadRealWorldMap = async () => {
      setRealMapLoading(true);
      try {
        const token = await getFreshToken();
        const headers: Record<string, string> = {};
        if (token) {
          headers['Authorization'] = `Bearer ${token}`;
        }
        const queryParams = new URLSearchParams({
          world: dimension,
          t: String(refreshKey),
        });
        if (token) {
          queryParams.set('token', token);
        }

        const res = await fetch(`/api/map/overview?${queryParams.toString()}`, {
          headers,
          credentials: 'same-origin',
        });

        if (!res.ok) {
          throw new Error(`Failed to load map: ${res.status} ${res.statusText}`);
        }

        const blob = await res.blob();
        if (!isMounted) return;

        const url = URL.createObjectURL(blob);
        currentObjectUrl = url;

        const img = new Image();
        img.onload = () => {
          if (isMounted) {
            realMapImgRef.current = img;
            setRealMapLoaded(true);
            setRealMapLoading(false);
          }
        };
        img.onerror = () => {
          if (isMounted) {
            setRealMapLoading(false);
          }
        };
        img.src = url;
      } catch (err) {
        console.error('Map overview load error:', err);
        if (isMounted) {
          setRealMapLoading(false);
        }
      }
    };

    loadRealWorldMap();

    return () => {
      isMounted = false;
      if (currentObjectUrl) {
        URL.revokeObjectURL(currentObjectUrl);
      }
    };
  }, [dimension, refreshKey]);

  const handleSyncRealWorld = () => {
    setRealMapLoading(true);
    setRefreshKey((k) => k + 1);
  };

  // Convert Screen Coordinates to World Coordinates
  const screenToWorld = useCallback(
    (screenX: number, screenY: number) => {
      const canvas = canvasRef.current;
      if (!canvas) return { x: 0, z: 0 };
      const rect = canvas.getBoundingClientRect();
      const cx = rect.width / 2;
      const cy = rect.height / 2;
      const worldX = Math.round(camera.x + (screenX - cx) / camera.zoom);
      const worldZ = Math.round(camera.z + (screenY - cy) / camera.zoom);
      return { x: worldX, z: worldZ };
    },
    [camera]
  );

  // Convert World Coordinates to Screen Coordinates
  const worldToScreen = useCallback(
    (worldX: number, worldZ: number) => {
      const canvas = canvasRef.current;
      if (!canvas) return { x: 0, y: 0 };
      const cx = canvas.width / (2 * (window.devicePixelRatio || 1));
      const cy = canvas.height / (2 * (window.devicePixelRatio || 1));
      return {
        x: cx + (worldX - camera.x) * camera.zoom,
        y: cy + (worldZ - camera.z) * camera.zoom,
      };
    },
    [camera]
  );

  // Zoom Handling
  const handleZoom = (direction: 'in' | 'out', factor = 1.25) => {
    setCamera((prev) => {
      const newZoom = direction === 'in' ? prev.zoom * factor : prev.zoom / factor;
      return { ...prev, zoom: Math.max(0.04, Math.min(6.0, newZoom)) };
    });
  };

  const handleWheel = (e: React.WheelEvent<HTMLCanvasElement>) => {
    e.preventDefault();
    const factor = e.deltaY < 0 ? 1.15 : 0.87;
    handleZoom(e.deltaY < 0 ? 'in' : 'out', factor);
  };

  // Mouse Drag / Pan Handling
  const handleMouseDown = (e: React.MouseEvent<HTMLCanvasElement>) => {
    if (e.button === 2) {
      // Right click opens tactical context menu
      return;
    }
    if (e.button === 0) {
      setIsDragging(true);
      setDragStart({ x: e.clientX, y: e.clientY });
    }
  };

  const handleMouseMove = (e: React.MouseEvent<HTMLCanvasElement>) => {
    const canvas = canvasRef.current;
    if (!canvas) return;
    const rect = canvas.getBoundingClientRect();
    const mouseX = e.clientX - rect.left;
    const mouseY = e.clientY - rect.top;

    const coords = screenToWorld(mouseX, mouseY);
    cursorRef.current = coords;

    if (!throttleTimerRef.current) {
      throttleTimerRef.current = setTimeout(() => {
        setCursorWorld(cursorRef.current);
        throttleTimerRef.current = null;
      }, 50);
    }

    if (isDragging) {
      const dx = (e.clientX - dragStart.x) / camera.zoom;
      const dz = (e.clientY - dragStart.y) / camera.zoom;
      setCamera((prev) => ({
        ...prev,
        x: prev.x - dx,
        z: prev.z - dz,
      }));
      setDragStart({ x: e.clientX, y: e.clientY });
    }
  };

  const handleMouseUp = (e: React.MouseEvent<HTMLCanvasElement>) => {
    if (e.button === 2) return;
    setIsDragging(false);

    const canvas = canvasRef.current;
    if (!canvas) return;
    const rect = canvas.getBoundingClientRect();
    const clickX = e.clientX - rect.left;
    const clickY = e.clientY - rect.top;
    const worldPoint = screenToWorld(clickX, clickY);

    // Measuring Tool logic
    if (isMeasuring) {
      if (!measureA) {
        setMeasureA(worldPoint);
      } else if (!measureB) {
        setMeasureB(worldPoint);
      } else {
        setMeasureA(worldPoint);
        setMeasureB(null);
      }
      return;
    }

    // Check if clicked near an existing Waypoint (within 16px screen radius)
    let clickedWaypoint: Waypoint | null = null;
    for (const wp of waypoints) {
      if (wp.world && wp.world !== dimension) continue;
      const wpScreen = worldToScreen(wp.x, wp.z);
      const dist = Math.hypot(clickX - wpScreen.x, clickY - wpScreen.y);
      if (dist < 16) {
        clickedWaypoint = wp;
        break;
      }
    }

    if (clickedWaypoint) {
      setSelectedWaypoint(clickedWaypoint);
      setSelectedPlayer(null);
      setSelectedPoint(null);
      return;
    }

    // Check if clicked near a Player (within 16px screen radius)
    let clickedPlayer: PlayerCoord | null = null;
    for (const p of players) {
      if (p.world && p.world !== dimension) continue;
      const pScreen = worldToScreen(p.x, p.z);
      const dist = Math.hypot(clickX - pScreen.x, clickY - pScreen.y);
      if (dist < 16) {
        clickedPlayer = p;
        break;
      }
    }

    if (clickedPlayer) {
      setSelectedPlayer(clickedPlayer);
      setSelectedWaypoint(null);
      setSelectedPoint(null);
    } else {
      setSelectedPoint(worldPoint);
      setSelectedPlayer(null);
      setSelectedWaypoint(null);
      if (onSelectCoordinates) {
        onSelectCoordinates(worldPoint.x, worldPoint.z, dimension);
      }
    }
  };

  // Right-Click Tactical Context Menu
  const handleContextMenu = (e: React.MouseEvent<HTMLCanvasElement>) => {
    e.preventDefault();
    const canvas = canvasRef.current;
    if (!canvas) return;
    const rect = canvas.getBoundingClientRect();
    const screenX = e.clientX - rect.left;
    const screenY = e.clientY - rect.top;
    const worldCoords = screenToWorld(screenX, screenY);

    setContextMenu({
      x: screenX,
      y: screenY,
      worldX: worldCoords.x,
      worldZ: worldCoords.z,
    });
  };

  const handleRecenter = (x = 0, z = 0) => {
    setCamera((prev) => ({ ...prev, x, z }));
  };

  const copyCoordinates = (x: number, z: number, label: string) => {
    const text = `/tp @s ${x} ~ ${z}`;
    navigator.clipboard.writeText(text);
    setCopiedNotification(label);
    setTimeout(() => setCopiedNotification(''), 2000);
  };

  const toggleFullscreen = () => {
    if (!containerRef.current) return;
    if (!isFullscreen) {
      if (containerRef.current.requestFullscreen) {
        containerRef.current.requestFullscreen();
      }
      setIsFullscreen(true);
    } else {
      if (document.exitFullscreen) {
        document.exitFullscreen();
      }
      setIsFullscreen(false);
    }
  };

  // Measure distance calculation
  const measureStats = useMemo(() => {
    if (!measureA || !measureB) return null;
    const dx = measureB.x - measureA.x;
    const dz = measureB.z - measureA.z;
    const dist = Math.round(Math.hypot(dx, dz));
    const sprintSec = (dist / 5.6).toFixed(1);
    const elytraSec = (dist / 30.0).toFixed(1);
    return { distance: dist, dx, dz, sprintSec, elytraSec };
  }, [measureA, measureB]);

  // Main Canvas Rendering Engine Loop
  useEffect(() => {
    const canvas = canvasRef.current;
    if (!canvas) return;
    const ctx = canvas.getContext('2d');
    if (!ctx) return;

    let animId: number;

    const render = () => {
      const dpr = window.devicePixelRatio || 1;
      const width = canvas.clientWidth;
      const height = canvas.clientHeight;

      if (canvas.width !== width * dpr || canvas.height !== height * dpr) {
        canvas.width = width * dpr;
        canvas.height = height * dpr;
      }

      ctx.save();
      ctx.scale(dpr, dpr);

      // 1. Void Substrate Background
      ctx.fillStyle = '#08090a';
      ctx.fillRect(0, 0, width, height);

      const zoom = camera.zoom;
      const minWorldX = camera.x - width / (2 * zoom);
      const maxWorldX = camera.x + width / (2 * zoom);
      const minWorldZ = camera.z - height / (2 * zoom);
      const maxWorldZ = camera.z + height / (2 * zoom);

      // 2. Real MCA Chunk Engine Image Layer & Tactical Topography Fallback
      if (layers.terrain) {
        // Procedural tactical radar terrain cells for backdrop and areas outside core sector
        const step = Math.max(16, Math.floor(32 / zoom));
        const startX = Math.floor(minWorldX / step) * step;
        const endX = Math.ceil(maxWorldX / step) * step;
        const startZ = Math.floor(minWorldZ / step) * step;
        const endZ = Math.ceil(maxWorldZ / step) * step;

        for (let wx = startX; wx < endX; wx += step) {
          for (let wz = startZ; wz < endZ; wz += step) {
            const inMca = realMapLoaded && wx >= -512 && wx < 512 && wz >= -512 && wz < 512;
            if (inMca) continue;

            const biome = getEstimatedBiome(wx, wz, dimension);
            let color = '#0f140e';
            if (dimension === 'world_nether') color = '#1a0b0b';
            else if (dimension === 'world_the_end') color = '#120d1c';
            else if (biome.includes('River') || biome.includes('Ocean')) color = '#0a1624';
            else if (biome.includes('Hills')) color = '#141a12';
            else if (biome.includes('Forest')) color = '#0d1f10';

            const sp = worldToScreen(wx, wz);
            ctx.fillStyle = color;
            ctx.fillRect(sp.x, sp.y, step * zoom, step * zoom);
          }
        }

        // Draw Real MCA Chunk Engine Image Layer
        if (realMapLoaded && realMapImgRef.current) {
          const img = realMapImgRef.current;
          const imgOriginX = -512;
          const imgOriginZ = -512;
          const imgWidthBlocks = 1024;
          const imgHeightBlocks = 1024;

          const screenLeft = width / 2 + (imgOriginX - camera.x) * zoom;
          const screenTop = height / 2 + (imgOriginZ - camera.z) * zoom;
          const screenWidth = imgWidthBlocks * zoom;
          const screenHeight = imgHeightBlocks * zoom;

          ctx.imageSmoothingEnabled = zoom < 1.0;
          ctx.drawImage(img, screenLeft, screenTop, screenWidth, screenHeight);

          // Highlight MCA boundary border
          ctx.strokeStyle = '#27a644';
          ctx.lineWidth = 1;
          ctx.strokeRect(screenLeft, screenTop, screenWidth, screenHeight);

          ctx.font = '9px "JetBrains Mono", monospace';
          ctx.fillStyle = '#27a644';
          ctx.fillText('ANVIL REGION MCA (1024×1024b)', screenLeft + 6, screenTop + 14);
        }
      }

      // 3. Grid Lines & Sector Visualizers
      if (layers.grid) {
        // Chunk Grid (16b)
        if (layers.chunks && zoom > 0.8) {
          const chunkStep = 16;
          const startChunkX = Math.floor(minWorldX / chunkStep) * chunkStep;
          const endChunkX = Math.ceil(maxWorldX / chunkStep) * chunkStep;
          const startChunkZ = Math.floor(minWorldZ / chunkStep) * chunkStep;
          const endChunkZ = Math.ceil(maxWorldZ / chunkStep) * chunkStep;

          ctx.strokeStyle = 'rgba(255, 255, 255, 0.04)';
          ctx.lineWidth = 0.5;
          ctx.beginPath();
          for (let wx = startChunkX; wx <= endChunkX; wx += chunkStep) {
            const sx = width / 2 + (wx - camera.x) * zoom;
            ctx.moveTo(sx, 0);
            ctx.lineTo(sx, height);
          }
          for (let wz = startChunkZ; wz <= endChunkZ; wz += chunkStep) {
            const sy = height / 2 + (wz - camera.z) * zoom;
            ctx.moveTo(0, sy);
            ctx.lineTo(width, sy);
          }
          ctx.stroke();
        }

        // Sub-Sector Grid (100b)
        if (zoom > 0.16) {
          const subStep = 100;
          const startSubX = Math.floor(minWorldX / subStep) * subStep;
          const endSubX = Math.ceil(maxWorldX / subStep) * subStep;
          const startSubZ = Math.floor(minWorldZ / subStep) * subStep;
          const endSubZ = Math.ceil(maxWorldZ / subStep) * subStep;

          ctx.strokeStyle = 'rgba(35, 37, 42, 0.65)';
          ctx.lineWidth = 0.75;
          ctx.beginPath();
          for (let wx = startSubX; wx <= endSubX; wx += subStep) {
            if (wx % 500 === 0) continue;
            const sx = width / 2 + (wx - camera.x) * zoom;
            ctx.moveTo(sx, 0);
            ctx.lineTo(sx, height);
          }
          for (let wz = startSubZ; wz <= endSubZ; wz += subStep) {
            if (wz % 500 === 0) continue;
            const sy = height / 2 + (wz - camera.z) * zoom;
            ctx.moveTo(0, sy);
            ctx.lineTo(width, sy);
          }
          ctx.stroke();
        }

        // Major Sector Grid (500b)
        const sectorStep = 500;
        const startSecX = Math.floor(minWorldX / sectorStep) * sectorStep;
        const endSecX = Math.ceil(maxWorldX / sectorStep) * sectorStep;
        const startSecZ = Math.floor(minWorldZ / sectorStep) * sectorStep;
        const endSecZ = Math.ceil(maxWorldZ / sectorStep) * sectorStep;

        ctx.strokeStyle = '#23252a';
        ctx.lineWidth = 1;
        ctx.beginPath();
        for (let wx = startSecX; wx <= endSecX; wx += sectorStep) {
          const sx = width / 2 + (wx - camera.x) * zoom;
          ctx.moveTo(sx, 0);
          ctx.lineTo(sx, height);
        }
        for (let wz = startSecZ; wz <= endSecZ; wz += sectorStep) {
          const sy = height / 2 + (wz - camera.z) * zoom;
          ctx.moveTo(0, sy);
          ctx.lineTo(width, sy);
        }
        ctx.stroke();

        // Sector coordinate labels
        ctx.font = '10px "JetBrains Mono", monospace';
        ctx.fillStyle = '#62666d';
        for (let wx = startSecX; wx <= endSecX; wx += sectorStep) {
          const sx = width / 2 + (wx - camera.x) * zoom;
          if (wx !== 0) ctx.fillText(`X: ${wx}`, sx + 4, 16);
        }
        for (let wz = startSecZ; wz <= endSecZ; wz += sectorStep) {
          const sy = height / 2 + (wz - camera.z) * zoom;
          if (wz !== 0) ctx.fillText(`Z: ${wz}`, 6, sy - 4);
        }

        // Origin Crosshair (0, 0)
        const originScreen = worldToScreen(0, 0);
        ctx.strokeStyle = 'rgba(228, 242, 34, 0.45)';
        ctx.lineWidth = 1.25;
        ctx.beginPath();
        ctx.moveTo(originScreen.x - 14, originScreen.y);
        ctx.lineTo(originScreen.x + 14, originScreen.y);
        ctx.moveTo(originScreen.x, originScreen.y - 14);
        ctx.lineTo(originScreen.x, originScreen.y + 14);
        ctx.stroke();

        ctx.font = '10px "JetBrains Mono", monospace';
        ctx.fillStyle = '#e4f222';
        ctx.fillText('ORIGIN [0, 0]', originScreen.x + 6, originScreen.y - 6);
      }

      // 4. Tactical Waypoints Layer
      if (layers.waypoints && waypoints && waypoints.length > 0) {
        const time = Date.now() / 1000;
        waypoints.forEach((wp) => {
          if (wp.world && wp.world !== dimension) return;
          const pos = worldToScreen(wp.x, wp.z);
          const beaconColor = wp.color || '#e4f222';

          // Vertical Light Beam Glow Effect
          const gradient = ctx.createLinearGradient(pos.x, pos.y, pos.x, pos.y - 60);
          gradient.addColorStop(0, beaconColor);
          gradient.addColorStop(1, 'rgba(0, 0, 0, 0)');
          ctx.strokeStyle = gradient;
          ctx.lineWidth = 2.5;
          ctx.beginPath();
          ctx.moveTo(pos.x, pos.y);
          ctx.lineTo(pos.x, pos.y - 60);
          ctx.stroke();

          // Expanding sonar pulse ring
          const pulse = (time * 1.5) % 1;
          ctx.strokeStyle = beaconColor;
          ctx.globalAlpha = 1 - pulse;
          ctx.lineWidth = 1;
          ctx.beginPath();
          ctx.arc(pos.x, pos.y, 6 + pulse * 18, 0, Math.PI * 2);
          ctx.stroke();
          ctx.globalAlpha = 1.0;

          // Diamond Beacon Icon
          ctx.fillStyle = beaconColor;
          ctx.beginPath();
          ctx.moveTo(pos.x, pos.y - 6);
          ctx.lineTo(pos.x + 5, pos.y);
          ctx.lineTo(pos.x, pos.y + 6);
          ctx.lineTo(pos.x - 5, pos.y);
          ctx.closePath();
          ctx.fill();

          ctx.strokeStyle = '#08090a';
          ctx.lineWidth = 1;
          ctx.stroke();

          // Text Name Pill
          ctx.font = '10px "Inter", -apple-system, sans-serif';
          const text = wp.name;
          const textWidth = ctx.measureText(text).width;
          const pad = 4;

          ctx.fillStyle = 'rgba(15, 16, 17, 0.9)';
          ctx.strokeStyle = '#23252a';
          ctx.lineWidth = 1;
          ctx.fillRect(pos.x - textWidth / 2 - pad, pos.y - 20, textWidth + pad * 2, 14);
          ctx.strokeRect(pos.x - textWidth / 2 - pad, pos.y - 20, textWidth + pad * 2, 14);

          ctx.fillStyle = '#ffffff';
          ctx.textAlign = 'center';
          ctx.fillText(text, pos.x, pos.y - 9);
          ctx.textAlign = 'left';
        });
      }

      // 5. Live Players Markers Layer
      if (layers.players && players && players.length > 0) {
        players.forEach((player) => {
          if (player.world && player.world !== dimension) return;
          const pos = worldToScreen(player.x, player.z);

          // Player Radar Dot & Glow
          ctx.shadowColor = '#e4f222';
          ctx.shadowBlur = 10;
          ctx.fillStyle = '#e4f222';
          ctx.beginPath();
          ctx.arc(pos.x, pos.y, 4.5, 0, Math.PI * 2);
          ctx.fill();
          ctx.shadowBlur = 0;

          // Heading Yaw Pointer
          if (player.yaw !== undefined) {
            const rad = ((player.yaw + 90) * Math.PI) / 180;
            const arrowLen = 12;
            const ax = pos.x + Math.cos(rad) * arrowLen;
            const ay = pos.y + Math.sin(rad) * arrowLen;

            ctx.strokeStyle = '#e4f222';
            ctx.lineWidth = 1.75;
            ctx.beginPath();
            ctx.moveTo(pos.x, pos.y);
            ctx.lineTo(ax, ay);
            ctx.stroke();
          }

          // Player Label Pill
          ctx.font = '11px "Inter", -apple-system, sans-serif';
          const name = player.name;
          const textWidth = ctx.measureText(name).width;
          const pad = 5;

          ctx.fillStyle = 'rgba(15, 16, 17, 0.92)';
          ctx.strokeStyle = '#23252a';
          ctx.lineWidth = 1;
          ctx.fillRect(pos.x - textWidth / 2 - pad, pos.y + 8, textWidth + pad * 2, 16);
          ctx.strokeRect(pos.x - textWidth / 2 - pad, pos.y + 8, textWidth + pad * 2, 16);

          ctx.fillStyle = '#ffffff';
          ctx.textAlign = 'center';
          ctx.fillText(name, pos.x, pos.y + 20);
          ctx.textAlign = 'left';
        });
      }

      // 6. Measurement Vector Line
      if (isMeasuring && measureA) {
        const p1 = worldToScreen(measureA.x, measureA.z);
        const p2 = measureB ? worldToScreen(measureB.x, measureB.z) : worldToScreen(cursorRef.current.x, cursorRef.current.z);

        ctx.strokeStyle = '#27a644';
        ctx.lineWidth = 1.5;
        ctx.setLineDash([4, 4]);
        ctx.beginPath();
        ctx.moveTo(p1.x, p1.y);
        ctx.lineTo(p2.x, p2.y);
        ctx.stroke();
        ctx.setLineDash([]);

        ctx.fillStyle = '#27a644';
        ctx.beginPath();
        ctx.arc(p1.x, p1.y, 4, 0, Math.PI * 2);
        ctx.arc(p2.x, p2.y, 4, 0, Math.PI * 2);
        ctx.fill();
      }

      // 7. Click Reticle / Selection Box
      if (selectedPoint) {
        const sp = worldToScreen(selectedPoint.x, selectedPoint.z);
        const bSize = 10;
        ctx.strokeStyle = '#e4f222';
        ctx.lineWidth = 1.25;

        ctx.beginPath();
        ctx.moveTo(sp.x - bSize, sp.y - 3);
        ctx.lineTo(sp.x - bSize, sp.y - bSize);
        ctx.lineTo(sp.x - 3, sp.y - bSize);

        ctx.moveTo(sp.x + 3, sp.y - bSize);
        ctx.lineTo(sp.x + bSize, sp.y - bSize);
        ctx.lineTo(sp.x + bSize, sp.y - 3);

        ctx.moveTo(sp.x - bSize, sp.y + 3);
        ctx.lineTo(sp.x - bSize, sp.y + bSize);
        ctx.lineTo(sp.x - 3, sp.y + bSize);

        ctx.moveTo(sp.x + 3, sp.y + bSize);
        ctx.lineTo(sp.x + bSize, sp.y + bSize);
        ctx.lineTo(sp.x + bSize, sp.y + 3);
        ctx.stroke();

        ctx.fillStyle = '#e4f222';
        ctx.beginPath();
        ctx.arc(sp.x, sp.y, 2, 0, Math.PI * 2);
        ctx.fill();
      }

      ctx.restore();
      animId = requestAnimationFrame(render);
    };

    render();
    return () => cancelAnimationFrame(animId);
  }, [camera, dimension, layers, players, waypoints, selectedPoint, isMeasuring, measureA, measureB, worldToScreen, realMapLoaded]);

  // Scale bar calculation
  const scaleBarBlocks = useMemo(() => {
    const targetPixelWidth = 100;
    const rawBlocks = targetPixelWidth / camera.zoom;
    if (rawBlocks > 2000) return 5000;
    if (rawBlocks > 800) return 1000;
    if (rawBlocks > 400) return 500;
    if (rawBlocks > 150) return 200;
    if (rawBlocks > 70) return 100;
    if (rawBlocks > 30) return 50;
    return 16;
  }, [camera.zoom]);

  const scaleBarPixels = scaleBarBlocks * camera.zoom;

  return (
    <div
      ref={containerRef}
      style={{ height }}
      className="relative w-full rounded-[12px] overflow-hidden bg-[#08090a] border border-[#23252a] flex flex-col select-none font-sans shadow-[rgb(35,37,42)_0px_0px_0px_1px_inset]"
    >
      {/* Top Command Toolbar */}
      <div className="h-12 bg-[#0f1011] border-b border-[#23252a] px-3.5 flex items-center justify-between gap-3 shrink-0 z-10">
        
        {/* Dimension Switcher Tabs */}
        <div className="flex items-center gap-1 p-0.5 bg-[#161718] border border-[#23252a] rounded-[6px]">
          {DIMENSIONS.map((dim) => {
            const active = dimension === dim.id;
            return (
              <button
                key={dim.id}
                type="button"
                onClick={() => handleSetDimension(dim.id)}
                className={`px-2.5 py-1 rounded-[4px] text-xs transition-all cursor-pointer flex items-center gap-1.5 ${
                  active
                    ? 'bg-[#23252a] text-[#ffffff] font-medium'
                    : 'text-[#8a8f98] hover:text-[#d0d6e0] hover:bg-white/[0.02]'
                }`}
              >
                <span
                  className="w-1.5 h-1.5 rounded-full"
                  style={{ backgroundColor: dim.color }}
                />
                <span>{dim.label}</span>
              </button>
            );
          })}
        </div>

        {/* Reticle Coordinates Readout */}
        <div className="hidden md:flex items-center gap-3 text-xs font-mono text-[#8a8f98]">
          <div className="flex items-center gap-1.5">
            <span className="text-[#62666d]">RETICLE:</span>
            <span className="text-[#ffffff]">X {cursorWorld.x}</span>
            <span className="text-[#ffffff]">Z {cursorWorld.z}</span>
          </div>

          <div className="h-3 w-[1px] bg-[#23252a]" />

          <div className="flex items-center gap-1.5">
            <span className="text-[#62666d]">CHUNK:</span>
            <span className="text-[#d0d6e0]">
              [{Math.floor(cursorWorld.x / 16)}, {Math.floor(cursorWorld.z / 16)}]
            </span>
          </div>

          <div className="h-3 w-[1px] bg-[#23252a]" />

          <div className="flex items-center gap-1.5">
            <span className="text-[#62666d]">REGION:</span>
            <span className="text-[#d0d6e0]">
              r.{Math.floor(cursorWorld.x / 512)}.{Math.floor(cursorWorld.z / 512)}.mca
            </span>
          </div>
        </div>

        {/* Action Controls */}
        <div className="flex items-center gap-1.5">
          {/* Waypoints Drawer Toggle */}
          {onToggleWaypointDrawer && (
            <button
              type="button"
              onClick={onToggleWaypointDrawer}
              className="px-2.5 py-1 rounded-[6px] border border-[#23252a] text-xs text-[#d0d6e0] hover:border-[#383b3f] hover:text-[#ffffff] flex items-center gap-1.5 cursor-pointer transition-all bg-[#161718]"
              title="Toggle Tactical Waypoints Drawer"
            >
              <MapPin className="w-3.5 h-3.5 text-[#e4f222]" />
              <span className="font-medium">Waypoints</span>
              {waypointCount > 0 && (
                <span className="text-[10px] font-mono px-1.5 py-0.5 rounded-full bg-white/[0.08] text-[#ffffff]">
                  {waypointCount}
                </span>
              )}
            </button>
          )}

          {/* Real Map Sync Button */}
          <button
            type="button"
            onClick={handleSyncRealWorld}
            className="px-2.5 py-1 rounded-[6px] border border-[#23252a] text-xs text-[#d0d6e0] hover:border-[#383b3f] hover:text-[#ffffff] flex items-center gap-1.5 cursor-pointer transition-all"
            title="Re-read actual Minecraft chunk files from server"
          >
            <RefreshCw className={`w-3.5 h-3.5 ${realMapLoading ? 'animate-spin text-[#e4f222]' : 'text-[#8a8f98]'}`} />
            <span className="hidden sm:inline">Sync Map</span>
          </button>

          {/* Ruler Toggle Button */}
          <button
            type="button"
            onClick={() => {
              setIsMeasuring(!isMeasuring);
              setMeasureA(null);
              setMeasureB(null);
            }}
            className={`px-2.5 py-1 rounded-[6px] border text-xs flex items-center gap-1.5 cursor-pointer transition-all ${
              isMeasuring
                ? 'bg-[#23252a] text-[#ffffff] border-[#383b3f] font-medium'
                : 'bg-transparent border-[#23252a] text-[#d0d6e0] hover:border-[#383b3f]'
            }`}
            title="Measure Vector Distance (Click Point A, then Point B)"
          >
            <Ruler className="w-3.5 h-3.5" />
            <span className="hidden sm:inline">Ruler</span>
          </button>

          {/* Layer Menu Dropdown */}
          <div className="relative">
            <button
              type="button"
              onClick={() => setShowLayerMenu(!showLayerMenu)}
              className="p-1.5 rounded-[6px] border border-[#23252a] text-[#d0d6e0] hover:border-[#383b3f] hover:text-[#ffffff] transition-all cursor-pointer"
              title="Toggle Map Layers"
            >
              <Layers className="w-3.5 h-3.5" />
            </button>

            {showLayerMenu && (
              <div className="absolute right-0 mt-2 w-44 bg-[#0f1011] border border-[#23252a] rounded-[6px] p-2 shadow-2xl z-50 space-y-1 text-xs">
                <div className="px-2 py-1 text-[10px] font-mono text-[#62666d] uppercase tracking-wider">
                  Layers
                </div>
                {(Object.keys(layers) as Array<keyof typeof layers>).map((k) => (
                  <label
                    key={k}
                    className="flex items-center justify-between px-2 py-1 rounded hover:bg-white/[0.03] cursor-pointer text-[#d0d6e0]"
                  >
                    <span className="capitalize">{k}</span>
                    <input
                      type="checkbox"
                      checked={layers[k]}
                      onChange={(e) => setLayers(prev => ({ ...prev, [k]: e.target.checked }))}
                      className="accent-[#e4f222] cursor-pointer"
                    />
                  </label>
                ))}
              </div>
            )}
          </div>

          {/* Fullscreen Toggle */}
          <button
            type="button"
            onClick={toggleFullscreen}
            className="p-1.5 rounded-[6px] border border-[#23252a] text-[#d0d6e0] hover:border-[#383b3f] hover:text-[#ffffff] transition-all cursor-pointer"
            title="Toggle Fullscreen"
          >
            {isFullscreen ? <Minimize2 className="w-3.5 h-3.5" /> : <Maximize2 className="w-3.5 h-3.5" />}
          </button>
        </div>

      </div>

      {/* Canvas Viewport */}
      <div className="relative flex-1 min-h-0 w-full overflow-hidden cursor-crosshair">
        {realMapLoading && (
          <div className="absolute top-3 left-1/2 -translate-x-1/2 z-20 px-3 py-1.5 rounded-full bg-[#0f1011]/90 backdrop-blur-md border border-[#23252a] text-xs font-mono text-[#e4f222] flex items-center gap-2 shadow-2xl pointer-events-none">
            <RefreshCw className="w-3.5 h-3.5 animate-spin text-[#e4f222]" />
            <span>Synchronizing Anvil MCA Chunks...</span>
          </div>
        )}

        <canvas
          ref={canvasRef}
          onMouseDown={handleMouseDown}
          onMouseMove={handleMouseMove}
          onMouseUp={handleMouseUp}
          onContextMenu={handleContextMenu}
          onWheel={handleWheel}
          className="w-full h-full block"
        />

        {/* Right-Click Tactical Context Menu */}
        {contextMenu && (
          <div
            style={{ left: `${contextMenu.x}px`, top: `${contextMenu.y}px` }}
            className="absolute z-50 bg-[#0f1011] border border-[#23252a] rounded-[8px] p-1.5 shadow-2xl backdrop-blur-md w-60 flex flex-col gap-1 text-xs select-none"
            onClick={(e) => e.stopPropagation()}
            onContextMenu={(e) => e.preventDefault()}
          >
            <div className="px-2 py-1 border-b border-[#23252a] flex items-center justify-between text-[10px] font-mono text-[#8a8f98] uppercase">
              <span>COORDINATES</span>
              <span className="text-[#ffffff]">[{contextMenu.worldX}, {contextMenu.worldZ}]</span>
            </div>

            <button
              type="button"
              onClick={() => {
                const target = { x: contextMenu.worldX, z: contextMenu.worldZ, world: dimension };
                setContextMenu(null);
                if (onRequestCreateWaypoint) {
                  onRequestCreateWaypoint(target);
                }
              }}
              className="flex items-center gap-2 px-2.5 py-1.5 rounded-[4px] hover:bg-white/[0.06] text-[#ffffff] text-left transition-colors cursor-pointer"
            >
              <MapPin className="w-3.5 h-3.5 text-[#e4f222]" />
              <span className="font-medium">Place Tactical Waypoint...</span>
            </button>

            <button
              type="button"
              onClick={() => {
                copyCoordinates(contextMenu.worldX, contextMenu.worldZ, 'menu');
                setContextMenu(null);
              }}
              className="flex items-center gap-2 px-2.5 py-1.5 rounded-[4px] hover:bg-white/[0.06] text-[#d0d6e0] text-left transition-colors cursor-pointer"
            >
              <Copy className="w-3.5 h-3.5 text-[#8a8f98]" />
              <span>Copy /tp Command</span>
            </button>

            <button
              type="button"
              onClick={() => {
                handleRecenter(contextMenu.worldX, contextMenu.worldZ);
                setContextMenu(null);
              }}
              className="flex items-center gap-2 px-2.5 py-1.5 rounded-[4px] hover:bg-white/[0.06] text-[#d0d6e0] text-left transition-colors cursor-pointer"
            >
              <Crosshair className="w-3.5 h-3.5 text-[#8a8f98]" />
              <span>Center Camera Here</span>
            </button>
          </div>
        )}

        {/* Cardinal Directions */}
        <div className="absolute top-2 left-1/2 -translate-x-1/2 px-2 py-0.5 rounded-[4px] bg-[#08090a]/80 border border-[#23252a] text-[10px] font-mono text-[#8a8f98] pointer-events-none">
          NORTH (-Z)
        </div>
        <div className="absolute bottom-2 left-1/2 -translate-x-1/2 px-2 py-0.5 rounded-[4px] bg-[#08090a]/80 border border-[#23252a] text-[10px] font-mono text-[#8a8f98] pointer-events-none">
          SOUTH (+Z)
        </div>
        <div className="absolute right-2 top-1/2 -translate-y-1/2 px-1 py-1.5 rounded-[4px] bg-[#08090a]/80 border border-[#23252a] text-[10px] font-mono text-[#8a8f98] pointer-events-none">
          E
        </div>
        <div className="absolute left-2 top-1/2 -translate-y-1/2 px-1 py-1.5 rounded-[4px] bg-[#08090a]/80 border border-[#23252a] text-[10px] font-mono text-[#8a8f98] pointer-events-none">
          W
        </div>

        {/* Top-Left Biome & Real Map Badge */}
        <div className="absolute top-2.5 left-2.5 flex flex-col gap-1.5 pointer-events-none">
          <div className="px-2.5 py-1 rounded-[6px] bg-[#0f1011]/90 border border-[#23252a] backdrop-blur-md text-xs font-mono text-[#ffffff] flex items-center gap-2">
            <span className="w-1.5 h-1.5 rounded-full bg-[#27a644]" />
            <span className="uppercase text-[11px] font-medium">{dimension}</span>
            <span className="text-[#62666d]">·</span>
            <span className="text-[#8a8f98] text-[11px]">{getEstimatedBiome(cursorWorld.x, cursorWorld.z, dimension)}</span>
          </div>

          <div className="px-2 py-0.5 rounded-[4px] bg-[#161718]/90 border border-[#27a644]/40 text-[10px] font-mono text-[#27a644] flex items-center gap-1.5 self-start">
            <span className="w-1.5 h-1.5 rounded-full bg-[#27a644] animate-pulse" />
            <span>REAL ANVIL SECTOR MAP</span>
          </div>
        </div>

        {/* Bottom-Left Scale Bar */}
        <div className="absolute bottom-2.5 left-2.5 bg-[#0f1011]/90 border border-[#23252a] rounded-[6px] p-2 backdrop-blur-md flex flex-col gap-1 text-[10px] font-mono text-[#8a8f98] pointer-events-none">
          <div className="flex items-center justify-between gap-4">
            <span>{(camera.zoom * 100).toFixed(0)}%</span>
            <span>{scaleBarBlocks} BLOCKS</span>
          </div>
          <div className="h-0.5 bg-[#23252a] rounded-full overflow-hidden w-24">
            <div
              style={{ width: `${Math.min(96, scaleBarPixels)}px` }}
              className="h-full bg-[#d0d6e0]"
            />
          </div>
        </div>

        {/* Bottom-Right Navigation Dock */}
        <div className="absolute bottom-2.5 right-2.5 flex flex-col gap-1 bg-[#0f1011]/90 border border-[#23252a] rounded-[6px] p-1 backdrop-blur-md z-10">
          <button
            type="button"
            onClick={() => handleZoom('in')}
            className="p-1.5 rounded-[4px] hover:bg-[#161718] text-[#d0d6e0] hover:text-[#ffffff] transition-colors cursor-pointer"
            title="Zoom In (+)"
          >
            <ZoomIn className="w-3.5 h-3.5" />
          </button>
          <button
            type="button"
            onClick={() => handleZoom('out')}
            className="p-1.5 rounded-[4px] hover:bg-[#161718] text-[#d0d6e0] hover:text-[#ffffff] transition-colors cursor-pointer"
            title="Zoom Out (-)"
          >
            <ZoomOut className="w-3.5 h-3.5" />
          </button>

          <div className="h-[1px] bg-[#23252a] my-0.5" />

          <button
            type="button"
            onClick={() => handleRecenter(0, 0)}
            className="p-1.5 rounded-[4px] hover:bg-[#161718] text-[#d0d6e0] hover:text-[#ffffff] transition-colors cursor-pointer"
            title="Recenter Origin (0, 0)"
          >
            <Crosshair className="w-3.5 h-3.5" />
          </button>

          {players && players.length > 0 && (
            <button
              type="button"
              onClick={() => {
                const target = players[0];
                handleRecenter(target.x, target.z);
                setSelectedPlayer(target);
              }}
              className="p-1.5 rounded-[4px] hover:bg-[#161718] text-[#d0d6e0] hover:text-[#ffffff] transition-colors cursor-pointer"
              title={`Focus Player (${players[0].name})`}
            >
              <Users className="w-3.5 h-3.5" />
            </button>
          )}
        </div>

        {/* Top-Right Quick Jump */}
        <div className="absolute top-2.5 right-2.5 hidden sm:flex items-center gap-1 bg-[#0f1011]/90 border border-[#23252a] rounded-[6px] p-1 backdrop-blur-md z-10 text-xs font-mono">
          <span className="text-[10px] text-[#62666d] px-1 uppercase tracking-wider">JUMP:</span>
          {(DIMENSION_PRESETS[dimension] || []).map((preset) => (
            <button
              key={preset.name}
              type="button"
              onClick={() => {
                handleRecenter(preset.x, preset.z);
                setSelectedPoint({ x: preset.x, z: preset.z });
              }}
              className="px-2 py-0.5 rounded-[4px] hover:bg-[#161718] text-[#8a8f98] hover:text-[#ffffff] transition-all cursor-pointer text-[11px]"
            >
              {preset.name}
            </button>
          ))}
        </div>

        {/* Measure Tool Readout HUD */}
        {isMeasuring && (
          <div className="absolute top-12 left-1/2 -translate-x-1/2 bg-[#0f1011]/95 border border-[#23252a] rounded-[6px] p-2.5 shadow-2xl backdrop-blur-md flex items-center gap-4 z-20 font-mono text-xs">
            <div className="flex items-center gap-2">
              <span className="w-1.5 h-1.5 rounded-full bg-[#27a644]" />
              <span className="text-[#ffffff] text-xs">Ruler</span>
            </div>

            {measureStats ? (
              <div className="flex items-center gap-4 text-[#8a8f98] text-[11px]">
                <div>
                  <span className="text-[#62666d]">DIST: </span>
                  <span className="text-[#ffffff] font-medium">{measureStats.distance}b</span>
                </div>
                <div>
                  <span className="text-[#62666d]">DX: </span>
                  <span className="text-[#d0d6e0]">{measureStats.dx}</span>
                </div>
                <div>
                  <span className="text-[#62666d]">DZ: </span>
                  <span className="text-[#d0d6e0]">{measureStats.dz}</span>
                </div>
                <div>
                  <span className="text-[#62666d]">SPRINT: </span>
                  <span className="text-[#d0d6e0]">~{measureStats.sprintSec}s</span>
                </div>
                <div>
                  <span className="text-[#62666d]">ELYTRA: </span>
                  <span className="text-[#d0d6e0]">~{measureStats.elytraSec}s</span>
                </div>
              </div>
            ) : (
              <span className="text-xs text-[#8a8f98]">Click on map to select initial point</span>
            )}

            <button
              type="button"
              onClick={() => {
                setIsMeasuring(false);
                setMeasureA(null);
                setMeasureB(null);
              }}
              className="text-xs text-[#8a8f98] hover:text-[#ffffff] ml-2 cursor-pointer"
            >
              ✕
            </button>
          </div>
        )}

        {/* Selected Target Floating Inspector Box */}
        {(selectedPoint || selectedPlayer || selectedWaypoint) && (
          <div className="absolute bottom-16 left-1/2 -translate-x-1/2 bg-[#0f1011]/95 border border-[#23252a] rounded-[12px] p-4 shadow-2xl backdrop-blur-md flex flex-col gap-3 z-30 w-84 max-w-[calc(100%-2rem)]">
            <div className="flex items-center justify-between pb-2 border-b border-[#23252a]">
              <div className="flex items-center gap-2">
                {selectedWaypoint ? (
                  <div className="w-2.5 h-2.5 rounded-full" style={{ backgroundColor: selectedWaypoint.color || '#e4f222' }} />
                ) : (
                  <Navigation className="w-3.5 h-3.5 text-[#e4f222]" />
                )}
                <span className="text-xs font-medium text-[#ffffff]">
                  {selectedWaypoint ? `Waypoint: ${selectedWaypoint.name}` : (selectedPlayer ? `Player: ${selectedPlayer.name}` : 'Acquired Coordinates')}
                </span>
              </div>
              <button
                type="button"
                onClick={() => {
                  setSelectedPoint(null);
                  setSelectedPlayer(null);
                  setSelectedWaypoint(null);
                }}
                className="text-xs text-[#8a8f98] hover:text-[#ffffff] cursor-pointer"
              >
                ✕
              </button>
            </div>

            <div className="flex items-center justify-between text-xs font-mono">
              <span className="text-[#8a8f98]">TARGET XYZ:</span>
              <span className="text-[#ffffff] font-medium">
                [{selectedWaypoint?.x ?? selectedPoint?.x ?? 0}, {selectedWaypoint?.y ?? 64}, {selectedWaypoint?.z ?? selectedPoint?.z ?? 0}]
              </span>
            </div>

            {/* If clicked on existing Waypoint */}
            {selectedWaypoint && (
              <div className="flex items-center justify-between gap-2 pt-1">
                <button
                  type="button"
                  onClick={() => copyCoordinates(selectedWaypoint.x, selectedWaypoint.z, 'wp')}
                  className="flex-1 px-3 py-1.5 rounded-[6px] border border-[#23252a] text-xs text-[#d0d6e0] hover:text-[#ffffff] hover:border-[#383b3f] flex items-center justify-center gap-1.5 cursor-pointer transition-all bg-[#161718]"
                >
                  {copiedNotification === 'wp' ? <Check className="w-3 h-3 text-[#27a644]" /> : <Copy className="w-3 h-3" />}
                  <span>{copiedNotification === 'wp' ? 'Copied' : 'Copy /tp'}</span>
                </button>

                <button
                  type="button"
                  onClick={() => handleRecenter(selectedWaypoint.x, selectedWaypoint.z)}
                  className="px-3 py-1.5 rounded-[6px] border border-[#23252a] text-xs text-[#d0d6e0] hover:text-[#ffffff] hover:border-[#383b3f] cursor-pointer transition-all bg-[#161718]"
                >
                  Center
                </button>

                {onDeleteWaypoint && (
                  <button
                    type="button"
                    onClick={() => {
                      onDeleteWaypoint(selectedWaypoint.name);
                      setSelectedWaypoint(null);
                      setSelectedPoint(null);
                    }}
                    className="p-1.5 rounded-[6px] border border-[#eb5757]/40 text-[#eb5757] hover:bg-[#eb5757]/10 transition-all cursor-pointer"
                    title="Delete Waypoint"
                  >
                    <Trash2 className="w-3.5 h-3.5" />
                  </button>
                )}
              </div>
            )}

            {/* If clicked on existing Player */}
            {selectedPlayer && (
              <div className="flex items-center justify-between gap-2 pt-1">
                <button
                  type="button"
                  onClick={() => copyCoordinates(selectedPlayer.x, selectedPlayer.z, 'player')}
                  className="flex-1 px-3 py-1.5 rounded-[6px] border border-[#23252a] text-xs text-[#d0d6e0] hover:text-[#ffffff] hover:border-[#383b3f] flex items-center justify-center gap-1.5 cursor-pointer transition-all bg-[#161718]"
                >
                  {copiedNotification === 'player' ? <Check className="w-3 h-3 text-[#27a644]" /> : <Copy className="w-3 h-3" />}
                  <span>{copiedNotification === 'player' ? 'Copied' : 'Copy /tp'}</span>
                </button>

                <button
                  type="button"
                  onClick={() => {
                    handleRecenter(selectedPlayer.x, selectedPlayer.z);
                  }}
                  className="px-3 py-1.5 rounded-[6px] bg-[#e4f222] text-[#08090a] font-medium text-xs cursor-pointer hover:brightness-110 transition-all"
                >
                  Track
                </button>
              </div>
            )}

            {/* If clicked on ground coordinate */}
            {!selectedPlayer && !selectedWaypoint && selectedPoint && onRequestCreateWaypoint && (
              <div className="flex items-center justify-between gap-2 pt-1">
                <button
                  type="button"
                  onClick={() => copyCoordinates(selectedPoint.x, selectedPoint.z, 'pt')}
                  className="flex-1 px-3 py-1.5 rounded-[6px] border border-[#23252a] text-xs text-[#d0d6e0] hover:text-[#ffffff] hover:border-[#383b3f] flex items-center justify-center gap-1.5 cursor-pointer transition-all bg-[#161718]"
                >
                  {copiedNotification === 'pt' ? <Check className="w-3 h-3 text-[#27a644]" /> : <Copy className="w-3 h-3" />}
                  <span>{copiedNotification === 'pt' ? 'Copied' : 'Copy /tp'}</span>
                </button>

                <button
                  type="button"
                  onClick={() => {
                    onRequestCreateWaypoint({ x: selectedPoint.x, z: selectedPoint.z, world: dimension });
                  }}
                  className="px-3 py-1.5 rounded-[6px] bg-[#e4f222] text-[#08090a] font-medium text-xs cursor-pointer hover:brightness-110 transition-all flex items-center gap-1.5"
                >
                  <MapPin className="w-3 h-3" />
                  <span>Plot Waypoint</span>
                </button>
              </div>
            )}

          </div>
        )}

      </div>
    </div>
  );
}
