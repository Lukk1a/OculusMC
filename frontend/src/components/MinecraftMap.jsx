import React, { useRef, useEffect, useState, useCallback, useMemo } from 'react';
import {
  ZoomIn,
  ZoomOut,
  Crosshair,
  Ruler,
  Layers,
  Copy,
  Check,
  Users,
  Maximize2,
  Minimize2,
  Navigation,
  RefreshCw
} from 'lucide-react';

// Dimension metadata
const DIMENSIONS = [
  { id: 'world', label: 'Overworld', color: '#27a644' },
  { id: 'world_nether', label: 'The Nether', color: '#eb5757' },
  { id: 'world_the_end', label: 'The End', color: '#8b5cf6' },
];

// Presets per dimension for quick jump navigation
const DIMENSION_PRESETS = {
  world: [
    { name: 'Spawn (0, 0)', x: 0, z: 0 },
    { name: 'Stronghold', x: -840, z: 1200 },
    { name: 'Outpost', x: 1450, z: 320 },
    { name: 'Monument', x: -1200, z: -750 },
  ],
  world_nether: [
    { name: 'Portal (0, 0)', x: 0, z: 0 },
    { name: 'Fortress', x: 120, z: -280 },
    { name: 'Bastion', x: -350, z: 410 },
  ],
  world_the_end: [
    { name: 'Central Island', x: 0, z: 0 },
    { name: 'End Gateway', x: 96, z: -55 },
    { name: 'End City', x: 1280, z: -940 },
  ]
};

// Biome classifier for HUD readout
function getEstimatedBiome(x, z, dim) {
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
  onSelectCoordinates,
  height = '100%'
}) {
  const canvasRef = useRef(null);
  const containerRef = useRef(null);

  // Active Dimension
  const [internalDimension, setInternalDimension] = useState(currentDimension);
  const dimension = onDimensionChange ? currentDimension : internalDimension;

  const handleSetDimension = (dim) => {
    if (onDimensionChange) {
      onDimensionChange(dim);
    } else {
      setInternalDimension(dim);
    }
  };

  // Real Server World Map Image Pipeline
  const realMapImgRef = useRef(null);
  const [realMapLoaded, setRealMapLoaded] = useState(false);
  const [realMapLoading, setRealMapLoading] = useState(false);
  const [refreshKey, setRefreshKey] = useState(0);

  // Camera viewport: x, z in world coords, zoom in pixels per block
  const [camera, setCamera] = useState({ x: 0, z: 0, zoom: 0.45 });
  const [isDragging, setIsDragging] = useState(false);
  const [dragStart, setDragStart] = useState({ x: 0, y: 0 });
  const [cursorWorld, setCursorWorld] = useState({ x: 0, z: 0 });
  const [selectedPoint, setSelectedPoint] = useState(null);
  const [selectedPlayer, setSelectedPlayer] = useState(null);
  const [copiedNotification, setCopiedNotification] = useState('');
  const [isFullscreen, setIsFullscreen] = useState(false);

  // High-frequency cursor position stored in ref to prevent 60Hz React state thrashing
  const cursorRef = useRef({ x: 0, z: 0 });
  const throttleTimerRef = useRef(null);

  // Measure / Ruler Tool State
  const [isMeasuring, setIsMeasuring] = useState(false);
  const [measureA, setMeasureA] = useState(null);
  const [measureB, setMeasureB] = useState(null);

  // Layer Visibility
  const [layers, setLayers] = useState({
    grid: true,
    chunks: true,
    terrain: true,
    players: true,
  });
  const [showLayerMenu, setShowLayerMenu] = useState(false);


  // Fetch Actual Minecraft Map from Server
  useEffect(() => {
    let isMounted = true;
    const img = new Image();
    img.crossOrigin = 'anonymous';
    img.src = `/api/map/overview?world=${dimension}&t=${refreshKey}`;
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
    return () => {
      isMounted = false;
    };
  }, [dimension, refreshKey]);

  const handleSyncRealWorld = () => {
    setRealMapLoading(true);
    setRefreshKey(k => k + 1);
  };

  // Coordinate transformations
  const screenToWorld = useCallback((screenX, screenY, width, height) => {
    const worldX = (screenX - width / 2) / camera.zoom + camera.x;
    const worldZ = (screenY - height / 2) / camera.zoom + camera.z;
    return { x: Math.round(worldX), z: Math.round(worldZ) };
  }, [camera]);

  const worldToScreen = useCallback((worldX, worldZ, width, height) => {
    const screenX = width / 2 + (worldX - camera.x) * camera.zoom;
    const screenY = height / 2 + (worldZ - camera.z) * camera.zoom;
    return { x: screenX, y: screenY };
  }, [camera]);

  const handleZoom = useCallback((direction) => {
    const factor = direction === 'in' ? 1.4 : 0.7;
    setCamera(prev => ({
      ...prev,
      zoom: Math.min(Math.max(prev.zoom * factor, 0.04), 8.0)
    }));
  }, []);

  const handleRecenter = useCallback((targetX = 0, targetZ = 0) => {
    setCamera(prev => ({
      ...prev,
      x: targetX,
      z: targetZ
    }));
  }, []);

  // Sync fullscreen state with document escape event
  useEffect(() => {
    const handleFsChange = () => {
      setIsFullscreen(!!document.fullscreenElement);
    };
    document.addEventListener('fullscreenchange', handleFsChange);
    return () => document.removeEventListener('fullscreenchange', handleFsChange);
  }, []);

  // Keyboard navigation shortcuts
  useEffect(() => {
    const handleKeyDown = (e) => {
      if (['INPUT', 'SELECT', 'TEXTAREA'].includes(e.target?.tagName)) return;
      if (e.key === '+' || e.key === '=') {
        handleZoom('in');
      } else if (e.key === '-' || e.key === '_') {
        handleZoom('out');
      } else if (e.key === '0') {
        handleRecenter(0, 0);
      } else if (e.key === 'Escape') {
        setSelectedPoint(null);
        setSelectedPlayer(null);
        setIsMeasuring(false);
        setMeasureA(null);
        setMeasureB(null);
      }
    };
    window.addEventListener('keydown', handleKeyDown);
    return () => window.removeEventListener('keydown', handleKeyDown);
  }, [handleZoom, handleRecenter]);

  // Mouse drag handling
  const handleMouseDown = (e) => {
    if (e.button !== 0) return;
    setIsDragging(true);
    setDragStart({ x: e.clientX, y: e.clientY });
  };

  const handleMouseMove = (e) => {
    const canvas = canvasRef.current;
    if (!canvas) return;
    const rect = canvas.getBoundingClientRect();
    const mouseX = e.clientX - rect.left;
    const mouseY = e.clientY - rect.top;

    if (isDragging) {
      const dx = (e.clientX - dragStart.x) / camera.zoom;
      const dz = (e.clientY - dragStart.y) / camera.zoom;
      setCamera(prev => ({
        ...prev,
        x: prev.x - dx,
        z: prev.z - dz,
      }));
      setDragStart({ x: e.clientX, y: e.clientY });
    }

    const world = screenToWorld(mouseX, mouseY, rect.width, rect.height);
    cursorRef.current = world;

    if (!throttleTimerRef.current) {
      throttleTimerRef.current = setTimeout(() => {
        setCursorWorld(cursorRef.current);
        throttleTimerRef.current = null;
      }, 50);
    }
  };

  const handleMouseUp = (e) => {
    setIsDragging(false);
    const canvas = canvasRef.current;
    if (!canvas) return;
    const rect = canvas.getBoundingClientRect();
    const mouseX = e.clientX - rect.left;
    const mouseY = e.clientY - rect.top;

    // Distinguish simple click from dragging
    const moved = Math.hypot(e.clientX - dragStart.x, e.clientY - dragStart.y);
    if (moved > 4) return;

    const clickedWorld = screenToWorld(mouseX, mouseY, rect.width, rect.height);

    // If measure tool is active
    if (isMeasuring) {
      if (!measureA) {
        setMeasureA(clickedWorld);
      } else if (!measureB) {
        setMeasureB(clickedWorld);
      } else {
        setMeasureA(clickedWorld);
        setMeasureB(null);
      }
      return;
    }

    const hitRadius = 18 / camera.zoom;


    // Check hit on player
    const hitPlayer = (players || []).find(p => {
      const matchDim = (p.world === dimension) || (!p.world && dimension === 'world');
      if (!matchDim) return false;
      return Math.hypot(p.x - clickedWorld.x, p.z - clickedWorld.z) <= Math.max(16, hitRadius);
    });

    if (hitPlayer) {
      setSelectedPlayer(hitPlayer);
      setSelectedPoint({ x: hitPlayer.x, z: hitPlayer.z });
      if (onSelectCoordinates) onSelectCoordinates(hitPlayer.x, hitPlayer.z, dimension, hitPlayer.name);
      return;
    }

    // Default: select coordinate point
    setSelectedPlayer(null);
    setSelectedPoint(clickedWorld);
    if (onSelectCoordinates) onSelectCoordinates(clickedWorld.x, clickedWorld.z, dimension);
  };

  // Cursor-anchored wheel zoom
  const handleWheel = (e) => {
    e.preventDefault();
    const canvas = canvasRef.current;
    if (!canvas) return;
    const rect = canvas.getBoundingClientRect();
    const mouseX = e.clientX - rect.left;
    const mouseY = e.clientY - rect.top;

    const zoomFactor = e.deltaY < 0 ? 1.25 : 0.8;
    const newZoom = Math.min(Math.max(camera.zoom * zoomFactor, 0.04), 8.0);

    const worldAnchorX = (mouseX - rect.width / 2) / camera.zoom + camera.x;
    const worldAnchorZ = (mouseY - rect.height / 2) / camera.zoom + camera.z;

    const newCamX = worldAnchorX - (mouseX - rect.width / 2) / newZoom;
    const newCamZ = worldAnchorZ - (mouseY - rect.height / 2) / newZoom;

    setCamera({
      x: newCamX,
      z: newCamZ,
      zoom: newZoom,
    });
  };

  const toggleFullscreen = () => {
    if (!containerRef.current) return;
    if (!document.fullscreenElement) {
      containerRef.current.requestFullscreen?.().catch(() => {});
      setIsFullscreen(true);
    } else {
      document.exitFullscreen?.().catch(() => {});
      setIsFullscreen(false);
    }
  };

  const copyCoordinates = (x, z, label) => {
    const text = `/tp @s ${x} 64 ${z}`;
    navigator.clipboard.writeText(text);
    setCopiedNotification(label || 'tp');
    setTimeout(() => setCopiedNotification(''), 2200);
  };


  // Measured vector distance calculation
  const measureStats = useMemo(() => {
    if (!measureA || !measureB) return null;
    const dx = measureB.x - measureA.x;
    const dz = measureB.z - measureA.z;
    const distance = Math.hypot(dx, dz);
    const sprintSec = Math.round(distance / 5.6);
    const elytraSec = Math.round(distance / 30.0);
    return {
      distance: Math.round(distance),
      dx,
      dz,
      sprintSec,
      elytraSec,
    };
  }, [measureA, measureB]);

  // High-performance Canvas Rendering Pipeline
  useEffect(() => {
    const canvas = canvasRef.current;
    if (!canvas) return;
    const ctx = canvas.getContext('2d');
    if (!ctx) return;

    let animId;

    const render = () => {
      const dpr = window.devicePixelRatio || 1;
      const width = canvas.clientWidth;
      const height = canvas.clientHeight;

      if (width === 0 || height === 0) {
        animId = requestAnimationFrame(render);
        return;
      }

      if (canvas.width !== width * dpr || canvas.height !== height * dpr) {
        canvas.width = width * dpr;
        canvas.height = height * dpr;
      }

      ctx.save();
      ctx.scale(dpr, dpr);

      // Void Canvas Background (#08090a)
      ctx.fillStyle = '#08090a';
      ctx.fillRect(0, 0, width, height);

      const zoom = camera.zoom;
      const halfW = width / 2;
      const halfH = height / 2;

      const minWorldX = camera.x - halfW / zoom;
      const maxWorldX = camera.x + halfW / zoom;
      const minWorldZ = camera.z - halfH / zoom;
      const maxWorldZ = camera.z + halfH / zoom;

      // 1. Real Server World Map Image (1024x1024 blocks: [-512, -512] to [512, 512])
      if (layers.terrain) {
        if (realMapImgRef.current && realMapLoaded) {
          const topLeft = worldToScreen(-512, -512, width, height);
          const drawSize = 1024 * zoom;

          ctx.save();
          ctx.imageSmoothingEnabled = zoom < 0.8;
          ctx.drawImage(realMapImgRef.current, topLeft.x, topLeft.y, drawSize, drawSize);

          // Hairline sector boundary
          ctx.strokeStyle = 'rgba(228, 242, 34, 0.35)';
          ctx.lineWidth = 1;
          ctx.strokeRect(topLeft.x, topLeft.y, drawSize, drawSize);

          ctx.font = '9px "JetBrains Mono", monospace';
          ctx.fillStyle = '#e4f222';
          ctx.fillText('ANVIL SECTOR [-512..+512]', topLeft.x + 6, topLeft.y + 14);
          ctx.restore();
        } else {
          // Placeholder sector surface while loading
          const center = worldToScreen(0, 0, width, height);
          ctx.save();
          ctx.fillStyle = dimension === 'world_nether' ? '#140808' : (dimension === 'world_the_end' ? '#0b0a14' : '#0a0d11');
          ctx.fillRect(center.x - 512 * zoom, center.y - 512 * zoom, 1024 * zoom, 1024 * zoom);
          ctx.restore();
        }

        // World Spawn Perimeter (19x19 chunks = 304 blocks)
        if (dimension === 'world') {
          const spawnTopLeft = worldToScreen(-152, -152, width, height);
          const spawnSize = 304 * zoom;
          ctx.strokeStyle = 'rgba(39, 166, 68, 0.4)';
          ctx.lineWidth = 1;
          ctx.setLineDash([4, 4]);
          ctx.strokeRect(spawnTopLeft.x, spawnTopLeft.y, spawnSize, spawnSize);
          ctx.setLineDash([]);
          ctx.font = '9px "JetBrains Mono", monospace';
          ctx.fillStyle = '#27a644';
          ctx.fillText('SPAWN CHUNKS [304b]', spawnTopLeft.x + 4, spawnTopLeft.y + 12);
        }
      }

      // 2. High-Precision Tactical Grid & Chunk Overlay
      if (layers.grid) {
        // Chunk Grid (16b) at close zoom
        if (layers.chunks && zoom > 0.45) {
          const chunkStep = 16;
          const startChunkX = Math.floor(minWorldX / chunkStep) * chunkStep;
          const endChunkX = Math.ceil(maxWorldX / chunkStep) * chunkStep;
          const startChunkZ = Math.floor(minWorldZ / chunkStep) * chunkStep;
          const endChunkZ = Math.ceil(maxWorldZ / chunkStep) * chunkStep;

          ctx.strokeStyle = 'rgba(35, 37, 42, 0.4)';
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

        // World Origin Axes (0, 0)
        const center = worldToScreen(0, 0, width, height);
        ctx.strokeStyle = 'rgba(208, 214, 224, 0.25)';
        ctx.lineWidth = 1;
        ctx.beginPath();
        ctx.moveTo(0, center.y);
        ctx.lineTo(width, center.y);
        ctx.moveTo(center.x, 0);
        ctx.lineTo(center.x, height);
        ctx.stroke();

        // Origin Reticle
        ctx.fillStyle = '#ffffff';
        ctx.beginPath();
        ctx.arc(center.x, center.y, 2.5, 0, Math.PI * 2);
        ctx.fill();

        ctx.strokeStyle = '#e4f222';
        ctx.lineWidth = 1;
        ctx.beginPath();
        ctx.arc(center.x, center.y, 8, 0, Math.PI * 2);
        ctx.stroke();

        ctx.fillStyle = '#d0d6e0';
        ctx.fillText('ORIGIN [0, 0]', center.x + 12, center.y - 8);
      }

      // 4. Live Players Tracking with Yaw Cones
      if (layers.players && players && players.length > 0) {
        players.forEach(p => {
          const matchDim = (p.world === dimension) || (!p.world && dimension === 'world');
          if (!matchDim) return;

          const s = worldToScreen(p.x, p.z, width, height);
          const yawRad = ((p.yaw || 0) + 90) * (Math.PI / 180);

          // Radar Sight Cone
          ctx.save();
          ctx.fillStyle = 'rgba(39, 166, 68, 0.15)';
          ctx.beginPath();
          ctx.moveTo(s.x, s.y);
          ctx.arc(s.x, s.y, 28 * Math.min(1.5, Math.max(0.7, zoom)), yawRad - 0.5, yawRad + 0.5);
          ctx.closePath();
          ctx.fill();
          ctx.restore();

          // Outer Ring
          ctx.save();
          ctx.strokeStyle = '#27a644';
          ctx.lineWidth = 1.5;
          ctx.beginPath();
          ctx.arc(s.x, s.y, 6.5, 0, Math.PI * 2);
          ctx.stroke();

          // Center Dot
          ctx.fillStyle = '#ffffff';
          ctx.beginPath();
          ctx.arc(s.x, s.y, 3, 0, Math.PI * 2);
          ctx.fill();
          ctx.restore();

          // Player Name Tag
          ctx.font = '500 11px "Inter", sans-serif';
          ctx.fillStyle = '#ffffff';
          ctx.fillText(p.name, s.x + 12, s.y - 2);

          ctx.font = '10px "JetBrains Mono", monospace';
          ctx.fillStyle = '#27a644';
          ctx.fillText(`${p.ping ?? 12}ms`, s.x + 12, s.y + 11);
        });
      }

      // 5. Measure Vector Line Tool
      if (isMeasuring && measureA) {
        const p1 = worldToScreen(measureA.x, measureA.z, width, height);
        const targetWorld = measureB || cursorRef.current;
        const p2 = worldToScreen(targetWorld.x, targetWorld.z, width, height);

        ctx.save();
        ctx.strokeStyle = '#e4f222';
        ctx.lineWidth = 1.5;
        ctx.setLineDash([5, 4]);

        ctx.beginPath();
        ctx.moveTo(p1.x, p1.y);
        ctx.lineTo(p2.x, p2.y);
        ctx.stroke();

        ctx.setLineDash([]);
        ctx.fillStyle = '#e4f222';
        ctx.beginPath();
        ctx.arc(p1.x, p1.y, 4, 0, Math.PI * 2);
        ctx.fill();

        ctx.fillStyle = '#ffffff';
        ctx.beginPath();
        ctx.arc(p2.x, p2.y, 4, 0, Math.PI * 2);
        ctx.fill();

        const midX = (p1.x + p2.x) / 2;
        const midY = (p1.y + p2.y) / 2;
        const dist = Math.round(Math.hypot(targetWorld.x - measureA.x, targetWorld.z - measureA.z));

        ctx.fillStyle = 'rgba(15, 16, 17, 0.9)';
        ctx.strokeStyle = '#23252a';
        ctx.lineWidth = 1;
        const tagText = `${dist} blocks`;
        ctx.font = '11px "JetBrains Mono", monospace';
        const textWidth = ctx.measureText(tagText).width;

        ctx.fillRect(midX - textWidth / 2 - 6, midY - 14, textWidth + 12, 20);
        ctx.strokeRect(midX - textWidth / 2 - 6, midY - 14, textWidth + 12, 20);

        ctx.fillStyle = '#ffffff';
        ctx.fillText(tagText, midX - textWidth / 2, midY);
        ctx.restore();
      }

      // 6. Selected Point Reticle
      if (selectedPoint) {
        const sp = worldToScreen(selectedPoint.x, selectedPoint.z, width, height);
        ctx.strokeStyle = '#e4f222';
        ctx.lineWidth = 1;

        const bSize = 10;
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
  }, [camera, dimension, layers, players, selectedPoint, isMeasuring, measureA, measureB, worldToScreen, realMapLoaded]);

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
        <div className="hidden md:flex items-center gap-3 text-xs font-mono-telemetry text-[#8a8f98]">
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
                <div className="px-2 py-1 text-[10px] font-mono-telemetry text-[#62666d] uppercase tracking-wider">
                  Layers
                </div>
                {Object.keys(layers).map((k) => (
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
        <canvas
          ref={canvasRef}
          onMouseDown={handleMouseDown}
          onMouseMove={handleMouseMove}
          onMouseUp={handleMouseUp}
          onWheel={handleWheel}
          className="w-full h-full block"
        />

        {/* Cardinal Directions */}
        <div className="absolute top-2 left-1/2 -translate-x-1/2 px-2 py-0.5 rounded-[4px] bg-[#08090a]/80 border border-[#23252a] text-[10px] font-mono-telemetry text-[#8a8f98] pointer-events-none">
          NORTH (-Z)
        </div>
        <div className="absolute bottom-2 left-1/2 -translate-x-1/2 px-2 py-0.5 rounded-[4px] bg-[#08090a]/80 border border-[#23252a] text-[10px] font-mono-telemetry text-[#8a8f98] pointer-events-none">
          SOUTH (+Z)
        </div>
        <div className="absolute right-2 top-1/2 -translate-y-1/2 px-1 py-1.5 rounded-[4px] bg-[#08090a]/80 border border-[#23252a] text-[10px] font-mono-telemetry text-[#8a8f98] pointer-events-none">
          E
        </div>
        <div className="absolute left-2 top-1/2 -translate-y-1/2 px-1 py-1.5 rounded-[4px] bg-[#08090a]/80 border border-[#23252a] text-[10px] font-mono-telemetry text-[#8a8f98] pointer-events-none">
          W
        </div>

        {/* Top-Left Biome & Real Map Badge */}
        <div className="absolute top-2.5 left-2.5 flex flex-col gap-1.5 pointer-events-none">
          <div className="px-2.5 py-1 rounded-[6px] bg-[#0f1011]/90 border border-[#23252a] backdrop-blur-md text-xs font-mono-telemetry text-[#ffffff] flex items-center gap-2">
            <span className="w-1.5 h-1.5 rounded-full bg-[#27a644]" />
            <span className="uppercase text-[11px] font-medium">{dimension}</span>
            <span className="text-[#62666d]">·</span>
            <span className="text-[#8a8f98] text-[11px]">{getEstimatedBiome(cursorWorld.x, cursorWorld.z, dimension)}</span>
          </div>

          <div className="px-2 py-0.5 rounded-[4px] bg-[#161718]/90 border border-[#27a644]/40 text-[10px] font-mono-telemetry text-[#27a644] flex items-center gap-1.5 self-start">
            <span className="w-1.5 h-1.5 rounded-full bg-[#27a644] animate-pulse" />
            <span>REAL ANVIL SECTOR MAP</span>
          </div>
        </div>

        {/* Bottom-Left Scale Bar */}
        <div className="absolute bottom-2.5 left-2.5 bg-[#0f1011]/90 border border-[#23252a] rounded-[6px] p-2 backdrop-blur-md flex flex-col gap-1 text-[10px] font-mono-telemetry text-[#8a8f98] pointer-events-none">
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
        <div className="absolute top-2.5 right-2.5 hidden sm:flex items-center gap-1 bg-[#0f1011]/90 border border-[#23252a] rounded-[6px] p-1 backdrop-blur-md z-10 text-xs font-mono-telemetry">
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
          <div className="absolute top-12 left-1/2 -translate-x-1/2 bg-[#0f1011]/95 border border-[#23252a] rounded-[6px] p-2.5 shadow-2xl backdrop-blur-md flex items-center gap-4 z-20 font-mono-telemetry text-xs">
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
        {(selectedPoint || selectedPlayer) && (
          <div className="absolute bottom-16 left-1/2 -translate-x-1/2 bg-[#0f1011]/95 border border-[#23252a] rounded-[12px] p-4 shadow-2xl backdrop-blur-md flex flex-col gap-3 z-30 w-80 max-w-[calc(100%-2rem)]">
            <div className="flex items-center justify-between pb-2 border-b border-[#23252a]">
              <div className="flex items-center gap-2">
                <Navigation className="w-3.5 h-3.5 text-[#e4f222]" />
                <span className="text-xs font-medium text-[#ffffff]">
                  {selectedPlayer ? `Player: ${selectedPlayer.name}` : 'Acquired Coordinates'}
                </span>
              </div>
              <button
                type="button"
                onClick={() => {
                  setSelectedPoint(null);
                  setSelectedPlayer(null);
                }}
                className="text-xs text-[#8a8f98] hover:text-[#ffffff] cursor-pointer"
              >
                ✕
              </button>
            </div>

            <div className="flex items-center justify-between text-xs font-mono-telemetry">
              <span className="text-[#8a8f98]">TARGET XYZ:</span>
              <span className="text-[#ffffff] font-medium">
                [{selectedPoint?.x ?? 0}, 64, {selectedPoint?.z ?? 0}]
              </span>
            </div>



            {/* If clicked on existing Player */}
            {selectedPlayer && (
              <div className="flex items-center justify-between gap-2 pt-1">
                <button
                  type="button"
                  onClick={() => copyCoordinates(selectedPlayer.x, selectedPlayer.z, 'player')}
                  className="flex-1 linear-btn-ghost py-1.5 text-xs flex items-center justify-center gap-1.5 cursor-pointer"
                >
                  {copiedNotification === 'player' ? <Check className="w-3 h-3 text-[#27a644]" /> : <Copy className="w-3 h-3" />}
                  <span>{copiedNotification === 'player' ? 'Copied' : 'Copy /tp'}</span>
                </button>

                <button
                  type="button"
                  onClick={() => {
                    handleRecenter(selectedPlayer.x, selectedPlayer.z);
                  }}
                  className="linear-btn-primary px-3 py-1.5 text-xs cursor-pointer"
                >
                  Track
                </button>
              </div>
            )}

          </div>
        )}

      </div>
    </div>
  );
}
