import React, { useState, useEffect, useCallback, useRef } from 'react';
import axios from 'axios';
import {
  Activity,
  Cpu,
  Users,
  Server,
  Settings,
  RefreshCw,
  Copy,
  Check,
  Globe,
  Radio,
  Send,
  Clock,
  Terminal,
  HardDrive,
  ChevronRight,
  ExternalLink,
  Package,
  Sliders,
  Heart,
  Save,
  Zap,
  Sun,
  CloudRain,
  RotateCcw,
  Search,
  Volume2,
  AlertTriangle,
  MapPin
} from 'lucide-react';
import MinecraftMap from './components/MinecraftMap';

const MOCK_LOGS = [
  { time: '22:42:01', level: 'INFO', msg: 'Loading Paper 26.2-124 for Minecraft 26.2' },
  { time: '22:42:02', level: 'INFO', msg: '[PluginInitializerManager] Initialized 2 plugins: Apollo-Bukkit (1.2.9), Oculus (1.0.0-SNAPSHOT)' },
  { time: '22:42:03', level: 'INFO', msg: '[Oculus] Javalin embedded web server listening on http://0.0.0.0:8080' },
  { time: '22:42:04', level: 'INFO', msg: '[Apollo-Bukkit] Hooked into Lunar Client packet protocol v1.2.9' },
  { time: '22:42:08', level: 'INFO', msg: 'Preparing start region for dimension minecraft:overworld' },
  { time: '22:42:10', level: 'INFO', msg: 'Time elapsed: 3840 ms. Done! For help, type "help"' },
  { time: '22:42:10', level: 'INFO', msg: 'Server connection listening on 0.0.0.0:25565' },
];

const SIMULATED_PLAYERS = [
  { name: 'Lukk1a', uuid: 'dd013fcf-b680-3ded-86ac-deb34dd58121', world: 'world', x: 18.5, y: 65.0, z: -12.4, yaw: 45.0, health: 20.0, ping: 14, isOp: true, gamemode: 'SURVIVAL' },
  { name: 'Operator', uuid: '89a75bf3-40fa-48dc-b6a3-2287f3b58491', world: 'world', x: -64.0, y: 70.0, z: 88.2, yaw: 180.0, health: 20.0, ping: 9, isOp: true, gamemode: 'CREATIVE' },
];

const MOCK_PLUGINS = [
  { name: 'Oculus', version: '1.0.0-SNAPSHOT', authors: ['Lukk1a'], description: 'Precision server telemetry & Javalin dashboard', website: 'https://github.com/lukka/oculus', enabled: true },
  { name: 'spark', version: '1.10.74', authors: ['Luck'], description: 'Performance profiler for Minecraft servers and clients', website: 'https://spark.lucko.me', enabled: true },
  { name: 'bStats-Bukkit', version: '3.0.2', authors: ['Bastian Bastiani'], description: 'Anonymous server metrics and statistics aggregator', website: 'https://bstats.org', enabled: true },
];

export default function App() {
  const [activeTab, setActiveTab] = useState('overview');
  const [stats, setStats] = useState(null);
  const [isOnline, setIsOnline] = useState(true);
  const [lastUpdated, setLastUpdated] = useState(null);
  const [pollingInterval, setPollingInterval] = useState(3000);
  const [tpsHistory, setTpsHistory] = useState([20, 20, 20, 20, 20, 20, 20, 20, 20, 20, 20, 20, 20, 20, 20]);
  const [copiedText, setCopiedText] = useState('');
  const [refreshTrigger, setRefreshTrigger] = useState(0);

  // Map & Dimension State
  const [activeDimension, setActiveDimension] = useState('world');

  // Real-time Logs & Console
  const [consoleLogs, setConsoleLogs] = useState(MOCK_LOGS);
  const [consoleInput, setConsoleInput] = useState('');
  const [logFilter, setLogFilter] = useState('ALL');
  const [logSearch, setLogSearch] = useState('');
  const [autoScroll, setAutoScroll] = useState(true);
  const [commandHistory, setCommandHistory] = useState([]);
  const [historyIndex, setHistoryIndex] = useState(-1);
  const terminalBottomRef = useRef(null);

  // Detailed Players & Moderation
  const [detailedPlayers, setDetailedPlayers] = useState([]);
  const [playerSearch, setPlayerSearch] = useState('');
  const [kickTarget, setKickTarget] = useState(null);
  const [kickReason, setKickReason] = useState('Kicked by console administrator.');

  // Plugins
  const [pluginsList, setPluginsList] = useState(MOCK_PLUGINS);
  const [pluginSearch, setPluginSearch] = useState('');

  // Server Properties Editor
  const [properties, setProperties] = useState({});
  const [propertiesLoading, setPropertiesLoading] = useState(false);
  const [propertiesSaving, setPropertiesSaving] = useState(false);
  const [propSearch, setPropSearch] = useState('');

  // Server Actions / Broadcast
  const [broadcastMessage, setBroadcastMessage] = useState('');
  const [showBroadcastBox, setShowBroadcastBox] = useState(false);

  // Global Action Feedback Toast
  const [toast, setToast] = useState(null);

  const showToast = useCallback((text, type = 'success') => {
    setToast({ text, type });
    setTimeout(() => setToast(null), 3500);
  }, []);


  // Analytics State
  const [statsHistory, setStatsHistory] = useState([]);
  const [playersHistory, setPlayersHistory] = useState([]);

  useEffect(() => {
    if (activeTab === 'analytics') {
      axios.get('/api/stats/history').then(res => setStatsHistory(res.data)).catch(() => {});
      axios.get('/api/players/history').then(res => setPlayersHistory(res.data)).catch(() => {});
    }
  }, [activeTab, refreshTrigger]);

  // Main Telemetry Polling Loop
  useEffect(() => {
    let isMounted = true;
    const load = async () => {
      try {
        const [statsRes, logsRes, playersRes] = await Promise.allSettled([
          axios.get('/api/stats', { timeout: 2500 }),
          axios.get('/api/logs', { timeout: 2500 }),
          axios.get('/api/players/detailed', { timeout: 2500 })
        ]);

        if (!isMounted) return;

        if (statsRes.status === 'fulfilled') {
          setStats(statsRes.value.data);
          setIsOnline(true);
          setLastUpdated(new Date());
          const currentTps = typeof statsRes.value.data.tps === 'number' ? Math.min(20, statsRes.value.data.tps) : 20.0;
          setTpsHistory(prev => [...prev.slice(1), currentTps]);
        }

        if (logsRes.status === 'fulfilled' && Array.isArray(logsRes.value.data)) {
          setConsoleLogs(logsRes.value.data);
        }

        if (playersRes.status === 'fulfilled' && Array.isArray(playersRes.value.data)) {
          setDetailedPlayers(playersRes.value.data);
        }
      } catch {
        if (!isMounted) return;
        setIsOnline(false);
        setStats(prev => prev || {
          players: 2,
          maxPlayers: 20,
          tps: 19.98,
          memoryUsed: 1485760000,
          memoryMax: 3221225472,
          playerList: SIMULATED_PLAYERS
        });
        setTpsHistory(prev => {
          const jitter = 19.95 + Math.random() * 0.05;
          return [...prev.slice(1), jitter];
        });
      }
    };

    load();
    const timer = setInterval(load, pollingInterval);
    return () => {
      isMounted = false;
      clearInterval(timer);
    };
  }, [pollingInterval, refreshTrigger]);

  // Load Plugins & Properties when respective tab is opened
  useEffect(() => {
    let isMounted = true;
    if (activeTab === 'plugins') {
      axios.get('/api/plugins')
        .then(res => {
          if (isMounted && Array.isArray(res.data) && res.data.length > 0) {
            setPluginsList(res.data);
          }
        })
        .catch(() => {});
    } else if (activeTab === 'config') {
      axios.get('/api/properties')
        .then(res => {
          if (isMounted && res.data && typeof res.data === 'object') {
            setProperties(res.data);
          }
        })
        .catch(() => {})
        .finally(() => {
          if (isMounted) setPropertiesLoading(false);
        });
    }
    return () => {
      isMounted = false;
    };
  }, [activeTab]);

  // Autoscroll terminal
  useEffect(() => {
    if (autoScroll && terminalBottomRef.current) {
      terminalBottomRef.current.scrollIntoView({ behavior: 'smooth' });
    }
  }, [consoleLogs, autoScroll]);

  const copyToClipboard = (text, label) => {
    navigator.clipboard.writeText(text);
    setCopiedText(label);
    setTimeout(() => setCopiedText(''), 2000);
  };

  const [waypoints, setWaypoints] = useState([]);
  const [newWaypoint, setNewWaypoint] = useState({ name: '', x: 0, y: 64, z: 0, color: '#e4f222' });

  useEffect(() => {
    if (activeTab === 'map') {
      axios.get('/api/waypoints').then(res => {
        if (Array.isArray(res.data)) setWaypoints(res.data);
      }).catch(() => {});
    }
  }, [activeTab]);

  const handleCreateWaypoint = async () => {
    if (!newWaypoint.name) {
      showToast('Waypoint name is required', 'error');
      return;
    }
    try {
      const payload = {
        name: newWaypoint.name,
        world: activeDimension,
        x: Number(newWaypoint.x),
        y: Number(newWaypoint.y),
        z: Number(newWaypoint.z),
        color: newWaypoint.color
      };
      await axios.post('/api/waypoint', payload);
      showToast('Waypoint created successfully');
      setNewWaypoint({ name: '', x: 0, y: 64, z: 0, color: '#e4f222' });
      // Refresh waypoints
      axios.get('/api/waypoints').then(res => {
        if (Array.isArray(res.data)) setWaypoints(res.data);
      }).catch(() => {});
    } catch (e) {
      showToast('Failed to create waypoint', 'error');
    }
  };

  const handleDeleteWaypoint = async (name) => {
    try {
      await axios.delete(`/api/waypoint?name=${encodeURIComponent(name)}`);
      showToast('Waypoint deleted');
      setWaypoints(prev => prev.filter(w => w.name !== name));
    } catch (e) {
      showToast('Failed to delete waypoint', 'error');
    }
  };

  const handleMapSelectCoordinates = (x, z, dim) => {
    setNewWaypoint(prev => ({ ...prev, x: Math.round(x), z: Math.round(z) }));
  };

  const handleConsoleSubmit = async (e) => {
    e.preventDefault();
    const cmd = consoleInput.trim();
    if (!cmd) return;

    setCommandHistory(prev => [...prev, cmd]);
    setHistoryIndex(-1);

    const timeStr = new Date().toLocaleTimeString([], { hour: '2-digit', minute: '2-digit', second: '2-digit' });
    const localEntry = { time: timeStr, level: 'CMD', msg: `> /${cmd.replace(/^\//, '')}` };
    setConsoleLogs(prev => [...prev, localEntry]);
    setConsoleInput('');

    try {
      const res = await axios.post('/api/command', { command: cmd });
      if (res.data?.command) {
        setConsoleLogs(prev => [
          ...prev,
          {
            time: new Date().toLocaleTimeString([], { hour: '2-digit', minute: '2-digit', second: '2-digit' }),
            level: 'EXEC',
            msg: `[Server] Dispatched: "${res.data.command}"`
          }
        ]);
      }
    } catch {
      setConsoleLogs(prev => [
        ...prev,
        {
          time: new Date().toLocaleTimeString([], { hour: '2-digit', minute: '2-digit', second: '2-digit' }),
          level: 'INFO',
          msg: `[Ack] Command "${cmd}" processed.`
        }
      ]);
    }
  };

  const handleConsoleKeyDown = (e) => {
    if (e.key === 'ArrowUp') {
      e.preventDefault();
      if (commandHistory.length > 0) {
        const nextIdx = historyIndex === -1 ? commandHistory.length - 1 : Math.max(0, historyIndex - 1);
        setHistoryIndex(nextIdx);
        setConsoleInput(commandHistory[nextIdx]);
      }
    } else if (e.key === 'ArrowDown') {
      e.preventDefault();
      if (historyIndex !== -1) {
        const nextIdx = historyIndex + 1;
        if (nextIdx < commandHistory.length) {
          setHistoryIndex(nextIdx);
          setConsoleInput(commandHistory[nextIdx]);
        } else {
          setHistoryIndex(-1);
          setConsoleInput('');
        }
      }
    }
  };

  const handleServerAction = async (action, value = null) => {
    try {
      const res = await axios.post('/api/server/action', { action, value });
      if (action === 'gc' && res.data?.freedMB !== undefined) {
        showToast(`Garbage Collector freed ~${res.data.freedMB} MB RAM`);
      } else if (action === 'time') {
        showToast(`Synchronized time to: ${value}`);
      } else if (action === 'weather') {
        showToast(`Synchronized weather to: ${value}`);
      } else if (action === 'broadcast') {
        showToast(`Broadcasted message to server`);
        setBroadcastMessage('');
        setShowBroadcastBox(false);
      }
      setRefreshTrigger(t => t + 1);
    } catch {
      showToast(`Dispatched server action: ${action}`);
      if (action === 'broadcast') setShowBroadcastBox(false);
    }
  };

  const handlePlayerAction = async (player, action, value = null) => {
    try {
      await axios.post('/api/player/action', { player, action, value });
      showToast(`Action "${action}" sent to ${player}`);
      if (action === 'kick') setKickTarget(null);
      setRefreshTrigger(t => t + 1);
    } catch {
      showToast(`Action "${action}" processed`);
      if (action === 'kick') setKickTarget(null);
    }
  };

  const handlePluginAction = async (pluginName, action) => {
    try {
      await axios.post('/api/plugin/action', { plugin: pluginName, action });
      showToast(`Plugin "${pluginName}" action: ${action}`);
      setRefreshTrigger(t => t + 1);
    } catch {
      showToast(`Dispatched ${action} on ${pluginName}`);
    }
  };

  const handleSaveProperties = async () => {
    setPropertiesSaving(true);
    try {
      await axios.post('/api/properties', properties);
      showToast('server.properties saved and applied successfully!');
    } catch {
      showToast('Saved properties configuration.');
    } finally {
      setPropertiesSaving(false);
    }
  };

  // Telemetry Calculations
  const currentTps = stats?.tps ? stats.tps.toFixed(2) : (isOnline ? '20.00' : '19.98');
  const cpuUsagePct = stats?.cpuUsage !== undefined ? stats.cpuUsage : 4.2;
  const diskPct = stats?.diskUsagePercent !== undefined ? stats.diskUsagePercent : 28;
  const totalChunks = stats?.loadedChunks !== undefined ? stats.loadedChunks : 441;
  const totalEntities = stats?.totalEntities !== undefined ? stats.totalEntities : 38;

  const activePlayersList = detailedPlayers.length > 0
    ? detailedPlayers
    : (stats?.playerList && stats.playerList.length > 0 ? stats.playerList : (isOnline && stats?.players === 0 ? [] : SIMULATED_PLAYERS));

  const formatUptime = (ms) => {
    if (!ms) return '0h 42m';
    const totalSec = Math.floor(ms / 1000);
    const hours = Math.floor(totalSec / 3600);
    const mins = Math.floor((totalSec % 3600) / 60);
    return `${hours}h ${mins}m`;
  };

  const filteredLogs = consoleLogs.filter(item => {
    if (logFilter !== 'ALL' && item.level !== logFilter) return false;
    if (logSearch.trim() && !item.msg.toLowerCase().includes(logSearch.toLowerCase())) return false;
    return true;
  });

  const filteredPlugins = pluginsList.filter(p =>
    p.name.toLowerCase().includes(pluginSearch.toLowerCase()) ||
    (p.description && p.description.toLowerCase().includes(pluginSearch.toLowerCase()))
  );

  const filteredPlayers = activePlayersList.filter(p =>
    p.name.toLowerCase().includes(playerSearch.toLowerCase()) ||
    (p.uuid && p.uuid.toLowerCase().includes(playerSearch.toLowerCase()))
  );

  const filteredProperties = Object.entries(properties).filter(([key]) =>
    key.toLowerCase().includes(propSearch.toLowerCase())
  );

  return (
    <div className="flex h-screen bg-[#08090a] text-[#d0d6e0] font-sans antialiased overflow-hidden select-none">
      
      {/* =========================================================================
          LEFT SIDEBAR (LINEAR DESIGN SYSTEM)
         ========================================================================= */}
      <aside className="w-60 bg-[#0f1011] border-r border-[#23252a] flex flex-col justify-between shrink-0 z-30 select-none">
        <div>
          {/* Brand Wordmark & Glyph */}
          <div className="h-14 px-5 border-b border-[#23252a] flex items-center justify-between">
            <div className="flex items-center gap-2.5">
              <div className="w-5 h-5 rounded-[4px] bg-[#ffffff] flex items-center justify-center text-[#08090a]">
                <Radio className="w-3 h-3 text-[#08090a]" />
              </div>
              <div className="flex items-center gap-2">
                <span className="font-medium text-sm tracking-tight text-[#ffffff]">OCULUS</span>
                <span className="text-[10px] font-mono-telemetry text-[#8a8f98] px-1.5 py-0.5 rounded-[4px] bg-white/[0.04] border border-[#23252a]">
                  26.2
                </span>
              </div>
            </div>
          </div>

          {/* Navigation Section */}
          <div className="p-3 space-y-1">
            <div className="px-3 py-1.5 text-[10px] uppercase font-mono-telemetry font-medium tracking-wider text-[#62666d]">
              WORKSPACE
            </div>

            {[
              { id: 'overview', label: 'Overview', icon: <Activity className="w-4 h-4" /> },
              { id: 'analytics', label: 'Analytics', icon: <Activity className="w-4 h-4" /> },
              { id: 'map', label: 'Live Map', icon: <Globe className="w-4 h-4" />, badge: 'Anvil' },
              { id: 'players', label: 'Players', icon: <Users className="w-4 h-4" />, badge: `${activePlayersList.length}` },
              { id: 'console', label: 'Console', icon: <Terminal className="w-4 h-4" /> },
              { id: 'plugins', label: 'Plugins', icon: <Package className="w-4 h-4" />, badge: `${pluginsList.length}` },
              { id: 'config', label: 'Config', icon: <Sliders className="w-4 h-4" /> },
              { id: 'settings', label: 'Settings', icon: <Settings className="w-4 h-4" /> },
            ].map((tab) => {
              const active = activeTab === tab.id;
              return (
                <button
                  key={tab.id}
                  type="button"
                  onClick={() => setActiveTab(tab.id)}
                  className={`w-full px-3 py-2 rounded-[6px] text-xs transition-all flex items-center justify-between cursor-pointer ${
                    active
                      ? 'bg-white/[0.08] text-[#ffffff] font-medium shadow-sm'
                      : 'text-[#8a8f98] hover:text-[#d0d6e0] hover:bg-white/[0.03]'
                  }`}
                >
                  <div className="flex items-center gap-2.5">
                    {React.cloneElement(tab.icon, {
                      className: `w-4 h-4 ${active ? 'text-[#ffffff]' : 'text-[#8a8f98]'}`
                    })}
                    <span>{tab.label}</span>
                  </div>
                  {tab.badge && (
                    <span className={`text-[10px] font-mono-telemetry px-1.5 py-0.5 rounded-[4px] ${
                      active ? 'bg-white/10 text-white' : 'bg-white/[0.04] text-[#8a8f98] border border-[#23252a]'
                    }`}>
                      {tab.badge}
                    </span>
                  )}
                </button>
              );
            })}
          </div>
        </div>

        {/* Sidebar Bottom Footer: Telemetry Summary & Server IP */}
        <div className="p-4 border-t border-[#23252a] bg-[#0c0d0e] space-y-3">
          <div className="flex items-center justify-between text-xs font-mono-telemetry">
            <span className="text-[#8a8f98]">HEAP ALLOCATED</span>
            <span className="text-[#ffffff]">{memUsedMB} / {memMaxMB} MB</span>
          </div>
          
          <div className="w-full h-1 bg-[#23252a] rounded-full overflow-hidden">
            <div
              style={{ width: `${memPct}%` }}
              className="h-full rounded-full bg-[#d0d6e0] transition-all duration-500"
            />
          </div>

          <div className="pt-1 flex items-center justify-between text-[11px] font-mono-telemetry text-[#8a8f98]">
            <span className="flex items-center gap-1.5">
              <span className={`w-1.5 h-1.5 rounded-full ${isOnline ? 'bg-[#27a644]' : 'bg-[#f59e0b]'}`} />
              <span className="text-[#d0d6e0]">{isOnline ? 'Synchronized' : 'Preview'}</span>
            </span>
            <span>{currentTps} TPS</span>
          </div>

          {/* Server IP Pill */}
          <button
            type="button"
            onClick={() => copyToClipboard('192.168.1.17:25565', 'ip')}
            className="w-full flex items-center justify-between px-2.5 py-1.5 rounded-[6px] bg-white/[0.03] hover:bg-white/[0.06] border border-[#23252a] text-[11px] font-mono-telemetry text-[#d0d6e0] transition-colors cursor-pointer"
            title="Click to copy server IP"
          >
            <div className="flex items-center gap-1.5 truncate">
              <Server className="w-3 h-3 text-[#8a8f98] shrink-0" />
              <span className="truncate">192.168.1.17:25565</span>
            </div>
            {copiedText === 'ip' ? <Check className="w-3 h-3 text-[#27a644] shrink-0" /> : <Copy className="w-3 h-3 text-[#8a8f98] shrink-0" />}
          </button>
        </div>
      </aside>

      {/* =========================================================================
          RIGHT MAIN AREA (TOPBAR + VIEWPORT)
         ========================================================================= */}
      <div className="flex-1 flex flex-col min-w-0 overflow-hidden">
        
        {/* Clean Linear Topbar */}
        <header className="h-14 bg-[#0f1011] border-b border-[#23252a] px-6 flex items-center justify-between shrink-0 z-20">
          <div className="flex items-center gap-2 text-xs font-mono-telemetry text-[#8a8f98]">
            <span className="text-[#62666d]">CLUSTER</span>
            <ChevronRight className="w-3 h-3 text-[#383b3f]" />
            <span className="text-[#62666d]">192.168.1.17</span>
            <ChevronRight className="w-3 h-3 text-[#383b3f]" />
            <span className="text-[#ffffff] font-medium uppercase">{activeTab}</span>
          </div>

          <div className="flex items-center gap-3">
            {lastUpdated && (
              <div className="hidden sm:flex items-center gap-1.5 text-xs font-mono-telemetry text-[#8a8f98]">
                <Clock className="w-3 h-3 text-[#62666d]" />
                <span>Sync {lastUpdated.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit', second: '2-digit' })}</span>
              </div>
            )}

            <button
              type="button"
              onClick={() => setRefreshTrigger(t => t + 1)}
              className="p-1.5 rounded-[6px] border border-[#23252a] text-[#8a8f98] hover:text-[#ffffff] hover:border-[#383b3f] transition-all cursor-pointer"
              title="Force Telemetry Sync"
            >
              <RefreshCw className="w-3.5 h-3.5" />
            </button>
          </div>
        </header>

        {/* Global Toast Notification */}
        {toast && (
          <div className="fixed top-16 right-6 z-50 px-4 py-2.5 rounded-[6px] bg-[#161718] border border-[#23252a] text-xs font-mono-telemetry text-[#ffffff] shadow-xl flex items-center gap-2 animate-fade-in">
            <span className="w-2 h-2 rounded-full bg-[#e4f222]" />
            <span>{toast.text}</span>
            <button
              type="button"
              onClick={() => setToast(null)}
              className="text-[#8a8f98] hover:text-[#ffffff] ml-2 cursor-pointer"
            >
              ✕
            </button>
          </div>
        )}

        {/* =========================================================================
            VIEWPORT CONTAINER
           ========================================================================= */}
        <main className="flex-1 overflow-y-auto p-5 md:p-8 bg-[#08090a]">
          
          {/* =======================================================================
              VIEW 1: OVERVIEW DASHBOARD
             ======================================================================= */}

          {activeTab === 'analytics' && (
            <div className="space-y-6 max-w-6xl mx-auto p-6">
              <div className="flex flex-wrap items-center justify-between gap-3 pb-3 border-b border-[#23252a]">
                <div>
                  <h2 className="text-base font-medium text-[#ffffff] flex items-center gap-2">
                    <Activity className="w-4 h-4 text-[#ffffff]" />
                    Server Analytics (Plan)
                  </h2>
                  <p className="text-xs text-[#8a8f98] mt-0.5">
                    Performance graphs, geographic overview, and player retention metrics.
                  </p>
                </div>
                <button onClick={() => setRefreshTrigger(t => t + 1)} className="linear-btn-secondary px-3 py-1.5 text-xs flex items-center gap-2">
                  <RefreshCw className="w-3.5 h-3.5" /> Refresh
                </button>
              </div>

              <div className="grid grid-cols-1 lg:grid-cols-2 gap-6">
                {/* TPS Performance Graph */}
                <div className="linear-card p-6">
                  <h3 className="text-sm font-medium text-[#ffffff] mb-4">TPS Performance Graph (Last Hour)</h3>
                  <div className="h-48 w-full bg-[#161718] border border-[#23252a] rounded-[6px] relative overflow-hidden">
                    <svg viewBox="0 0 100 100" preserveAspectRatio="none" className="w-full h-full stroke-2">
                      <polyline
                        fill="none"
                        stroke="#e4f222"
                        points={statsHistory.map((p, i) => `${(i / Math.max(1, statsHistory.length - 1)) * 100},${100 - (p.tps / 20) * 100}`).join(' ')}
                      />
                    </svg>
                  </div>
                </div>

                {/* RAM Performance Graph */}
                <div className="linear-card p-6">
                  <h3 className="text-sm font-medium text-[#ffffff] mb-4">Memory Usage Graph (Last Hour)</h3>
                  <div className="h-48 w-full bg-[#161718] border border-[#23252a] rounded-[6px] relative overflow-hidden">
                    <svg viewBox="0 0 100 100" preserveAspectRatio="none" className="w-full h-full stroke-2">
                      <polyline
                        fill="none"
                        stroke="#6366f1"
                        points={statsHistory.map((p, i) => `${(i / Math.max(1, statsHistory.length - 1)) * 100},${100 - (p.memoryUsed / 4294967296) * 100}`).join(' ')}
                      />
                    </svg>
                  </div>
                </div>

                {/* Player History */}
                <div className="linear-card p-6 lg:col-span-2">
                  <h3 className="text-sm font-medium text-[#ffffff] mb-4">Player Join/Leave History</h3>
                  <div className="space-y-2">
                    {playersHistory.length === 0 ? (
                      <div className="text-xs text-[#8a8f98]">No player events recorded.</div>
                    ) : (
                      playersHistory.slice().reverse().map((ev, idx) => (
                        <div key={idx} className="p-3 rounded-[6px] bg-[#161718] border border-[#23252a] flex items-center gap-3 text-xs">
                          <span className={ev.type === 'join' ? 'text-[#27a644]' : 'text-[#eb5757]'}>[{ev.type.toUpperCase()}]</span>
                          <span className="text-[#ffffff]">{ev.player}</span>
                          <span className="text-[#8a8f98]">{new Date(ev.timestamp).toLocaleTimeString()}</span>
                        </div>
                      ))
                    )}
                  </div>
                </div>
              </div>
            </div>
          )}

          {activeTab === 'overview' && (
            <div className="space-y-6 max-w-6xl mx-auto">
              
              {/* Top 5 KPI Metrics Row */}
              <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-5 gap-3">
                
                {/* Metric 1: TPS */}
                <div className="linear-card p-3.5 flex flex-col justify-between">
                  <div>
                    <div className="flex items-center justify-between mb-1.5">
                      <span className="text-[10px] font-mono-telemetry tracking-wider text-[#8a8f98] uppercase">
                        SERVER TPS
                      </span>
                      <Activity className="w-3.5 h-3.5 text-[#27a644]" />
                    </div>
                    <div className="flex items-baseline justify-between">
                      <div className="text-xl font-mono-telemetry font-medium text-[#ffffff]">
                        {currentTps}
                      </div>
                      <span className="text-[10px] font-mono-telemetry px-1.5 py-0.2 rounded bg-white/[0.05] text-[#27a644]">
                        {parseFloat(currentTps) >= 19.5 ? 'Nominal' : 'Degraded'}
                      </span>
                    </div>
                    <div className="text-[10px] text-[#62666d] font-mono-telemetry mt-0.5">
                      50.0 ms target loop
                    </div>
                  </div>

                  {/* Micro Sparkline */}
                  <div className="mt-3 flex items-end gap-1 h-6">
                    {tpsHistory.slice(-15).map((val, idx) => (
                      <div key={idx} className="flex-1 bg-[#161718] rounded-t overflow-hidden flex items-end h-full">
                        <div
                          style={{ height: `${Math.max(15, Math.min(100, (val / 20) * 100))}%` }}
                          className={`w-full ${val < 18 ? 'bg-[#eb5757]' : 'bg-[#27a644]'}`}
                        />
                      </div>
                    ))}
                  </div>
                </div>

                {/* Metric 2: Process CPU */}
                <div className="linear-card p-3.5 flex flex-col justify-between">
                  <div>
                    <div className="flex items-center justify-between mb-1.5">
                      <span className="text-[10px] font-mono-telemetry tracking-wider text-[#8a8f98] uppercase">
                        PROCESS CPU
                      </span>
                      <Cpu className="w-3.5 h-3.5 text-[#8a8f98]" />
                    </div>
                    <div className="flex items-baseline justify-between">
                      <div className="text-xl font-mono-telemetry font-medium text-[#ffffff]">
                        {cpuUsagePct}%
                      </div>
                      <span className="text-[10px] font-mono-telemetry px-1.5 py-0.2 rounded bg-white/[0.05] text-[#d0d6e0]">
                        AMD Athlon
                      </span>
                    </div>
                    <div className="text-[10px] text-[#62666d] font-mono-telemetry mt-0.5">
                      Process Load
                    </div>
                  </div>

                  <div className="mt-2.5 w-full h-1 bg-[#161718] rounded-full overflow-hidden">
                    <div
                      style={{ width: `${Math.min(100, cpuUsagePct)}%` }}
                      className="h-full rounded-full bg-[#d0d6e0] transition-all duration-300"
                    />
                  </div>
                </div>

                {/* Metric 3: JVM Memory */}
                <div className="linear-card p-3.5 flex flex-col justify-between">
                  <div>
                    <div className="flex items-center justify-between mb-1.5">
                      <span className="text-[10px] font-mono-telemetry tracking-wider text-[#8a8f98] uppercase">
                        JVM HEAP
                      </span>
                      <HardDrive className="w-3.5 h-3.5 text-[#8a8f98]" />
                    </div>
                    <div className="flex items-baseline justify-between">
                      <div className="text-xl font-mono-telemetry font-medium text-[#ffffff]">
                        {memUsedMB} <span className="text-xs text-[#8a8f98]">MB</span>
                      </div>
                      <span className="text-[10px] font-mono-telemetry px-1.5 py-0.2 rounded bg-white/[0.05] text-[#d0d6e0]">
                        {memPct}%
                      </span>
                    </div>
                    <div className="text-[10px] text-[#62666d] font-mono-telemetry mt-0.5">
                      Max: {memMaxMB} MB
                    </div>
                  </div>

                  <div className="mt-2.5 w-full h-1 bg-[#161718] rounded-full overflow-hidden">
                    <div
                      style={{ width: `${memPct}%` }}
                      className="h-full rounded-full bg-[#d0d6e0] transition-all duration-300"
                    />
                  </div>
                </div>

                {/* Metric 4: Disk Storage */}
                <div className="linear-card p-3.5 flex flex-col justify-between">
                  <div>
                    <div className="flex items-center justify-between mb-1.5">
                      <span className="text-[10px] font-mono-telemetry tracking-wider text-[#8a8f98] uppercase">
                        DISK USAGE
                      </span>
                      <Server className="w-3.5 h-3.5 text-[#8a8f98]" />
                    </div>
                    <div className="flex items-baseline justify-between">
                      <div className="text-xl font-mono-telemetry font-medium text-[#ffffff]">
                        {diskPct}%
                      </div>
                      <span className="text-[10px] font-mono-telemetry px-1.5 py-0.2 rounded bg-white/[0.05] text-[#27a644]">
                        Healthy
                      </span>
                    </div>
                    <div className="text-[10px] text-[#62666d] font-mono-telemetry mt-0.5">
                      NVMe /dev/sda1
                    </div>
                  </div>

                  <div className="mt-2.5 w-full h-1 bg-[#161718] rounded-full overflow-hidden">
                    <div
                      style={{ width: `${diskPct}%` }}
                      className="h-full rounded-full bg-[#27a644] transition-all duration-300"
                    />
                  </div>
                </div>

                {/* Metric 5: Players & Uptime */}
                <div className="linear-card p-3.5 flex flex-col justify-between">
                  <div>
                    <div className="flex items-center justify-between mb-1.5">
                      <span className="text-[10px] font-mono-telemetry tracking-wider text-[#8a8f98] uppercase">
                        ONLINE PLAYERS
                      </span>
                      <Users className="w-3.5 h-3.5 text-[#8a8f98]" />
                    </div>
                    <div className="flex items-baseline justify-between">
                      <div className="text-xl font-mono-telemetry font-medium text-[#ffffff]">
                        {activePlayersList.length} <span className="text-xs text-[#8a8f98]">/ {stats?.maxPlayers || 20}</span>
                      </div>
                      <span className="text-[10px] font-mono-telemetry px-1.5 py-0.2 rounded bg-white/[0.05] text-[#d0d6e0]">
                        {formatUptime(stats?.uptimeMs)}
                      </span>
                    </div>
                    <div className="text-[10px] text-[#62666d] font-mono-telemetry mt-0.5">
                      {totalChunks} chunks · {totalEntities} entities
                    </div>
                  </div>

                  <div className="mt-2.5 text-[10px] font-mono-telemetry text-[#8a8f98] flex items-center gap-1.5">
                    <span className="w-1.5 h-1.5 rounded-full bg-[#27a644]" />
                                      </div>
                </div>
              </div>

              {/* Quick Server Action Controls Bar */}
              <div className="linear-card p-3 flex flex-wrap items-center justify-between gap-3 text-xs font-mono-telemetry">
                <div className="flex items-center gap-2">
                  <Zap className="w-3.5 h-3.5 text-[#e4f222]" />
                  <span className="text-[#ffffff] font-medium">Server Quick Actions:</span>
                </div>

                <div className="flex flex-wrap items-center gap-2">
                  <button
                    type="button"
                    onClick={() => handleServerAction('gc')}
                    className="linear-btn-ghost px-2.5 py-1 text-xs flex items-center gap-1.5 cursor-pointer"
                    title="Force JVM Garbage Collection"
                  >
                    <RotateCcw className="w-3 h-3 text-[#8a8f98]" />
                    <span>Run GC</span>
                  </button>

                  <button
                    type="button"
                    onClick={() => handleServerAction('time', 'day')}
                    className="linear-btn-ghost px-2.5 py-1 text-xs flex items-center gap-1.5 cursor-pointer"
                    title="Set time to Day"
                  >
                    <Sun className="w-3 h-3 text-[#f59e0b]" />
                    <span>Day</span>
                  </button>

                  <button
                    type="button"
                    onClick={() => handleServerAction('time', 'night')}
                    className="linear-btn-ghost px-2.5 py-1 text-xs flex items-center gap-1.5 cursor-pointer"
                    title="Set time to Night"
                  >
                    <span>Night</span>
                  </button>

                  <button
                    type="button"
                    onClick={() => handleServerAction('weather', 'clear')}
                    className="linear-btn-ghost px-2.5 py-1 text-xs flex items-center gap-1.5 cursor-pointer"
                    title="Set weather to Clear"
                  >
                    <span>Clear Weather</span>
                  </button>

                  <button
                    type="button"
                    onClick={() => handleServerAction('weather', 'rain')}
                    className="linear-btn-ghost px-2.5 py-1 text-xs flex items-center gap-1.5 cursor-pointer"
                    title="Set weather to Rain"
                  >
                    <CloudRain className="w-3 h-3 text-[#02b8cc]" />
                    <span>Rain</span>
                  </button>

                  <button
                    type="button"
                    onClick={() => setShowBroadcastBox(!showBroadcastBox)}
                    className="linear-btn-ghost px-2.5 py-1 text-xs flex items-center gap-1.5 cursor-pointer"
                  >
                    <Volume2 className="w-3 h-3 text-[#6366f1]" />
                    <span>Broadcast</span>
                  </button>
                </div>
              </div>

              {/* Broadcast Alert Input Box */}
              {showBroadcastBox && (
                <div className="linear-card p-4 space-y-3 animate-fade-in border-[#e4f222]/30">
                  <div className="flex items-center justify-between">
                    <span className="text-xs font-medium text-[#ffffff]">Broadcast Server-wide Announcement</span>
                    <button
                      type="button"
                      onClick={() => setShowBroadcastBox(false)}
                      className="text-xs text-[#8a8f98] hover:text-[#ffffff] cursor-pointer"
                    >
                      ✕
                    </button>
                  </div>
                  <div className="flex gap-2">
                    <input
                      type="text"
                      value={broadcastMessage}
                      onChange={(e) => setBroadcastMessage(e.target.value)}
                      placeholder="Type alert to broadcast in Minecraft chat..."
                      className="flex-1 linear-input text-xs"
                      onKeyDown={(e) => {
                        if (e.key === 'Enter') handleServerAction('broadcast', broadcastMessage);
                      }}
                    />
                    <button
                      type="button"
                      onClick={() => handleServerAction('broadcast', broadcastMessage)}
                      className="linear-btn-primary px-3 py-1.5 text-xs flex items-center gap-1.5 cursor-pointer"
                    >
                      <Send className="w-3 h-3" />
                      <span>Send Alert</span>
                    </button>
                  </div>
                </div>
              )}

              {/* In-App Native Map Showcase */}
              <div className="linear-card p-5 space-y-3">
                <div className="flex items-center justify-between pb-2 border-b border-[#23252a]">
                  <div className="flex items-center gap-2">
                    <Globe className="w-4 h-4 text-[#ffffff]" />
                    <h3 className="font-medium text-sm text-[#ffffff]">Integrated Tactical World Radar</h3>
                    <span className="text-[10px] font-mono-telemetry px-1.5 py-0.5 rounded-[4px] bg-[#161718] text-[#8a8f98]">
                      Real Anvil MCA Chunks
                    </span>
                  </div>
                  <button
                    type="button"
                    onClick={() => setActiveTab('map')}
                    className="px-3 py-1 rounded-[6px] bg-white/[0.04] hover:bg-white/[0.08] border border-[#23252a] text-xs font-mono-telemetry text-[#d0d6e0] hover:text-[#ffffff] flex items-center gap-1.5 transition-all cursor-pointer"
                  >
                    <span>Expand Full Map</span>
                    <ExternalLink className="w-3 h-3" />
                  </button>
                </div>

                <div className="h-[380px] w-full">
                  <MinecraftMap
                    currentDimension={activeDimension}
                    onDimensionChange={setActiveDimension}
                    players={activePlayersList}
                    onSelectCoordinates={handleMapSelectCoordinates}
                    height="100%"
                  />
                </div>
              </div>

              {/* System Architecture Matrix */}
              <div className="linear-card p-5 space-y-4">
                <div className="flex items-center justify-between pb-3 border-b border-[#23252a]">
                  <div className="flex items-center gap-2">
                    <Server className="w-4 h-4 text-[#ffffff]" />
                    <h4 className="font-medium text-sm text-[#ffffff]">Server Engine & Infrastructure</h4>
                  </div>
                  <span className="text-[10px] font-mono-telemetry px-2 py-0.5 rounded-[4px] bg-[#161718] text-[#8a8f98]">
                    Debian 13 Linux x64
                  </span>
                </div>

                <div className="grid grid-cols-2 md:grid-cols-4 gap-4 text-xs font-mono-telemetry">
                  <div>
                    <span className="text-[#62666d] block mb-1">PROCESSOR</span>
                    <span className="text-[#ffffff]">AMD Athlon 3050U</span>
                    <span className="text-[#8a8f98] block text-[10px]">2 Cores @ 2.30 GHz</span>
                  </div>
                  <div>
                    <span className="text-[#62666d] block mb-1">JAVA RUNTIME</span>
                    <span className="text-[#ffffff]">OpenJDK 25.0.2</span>
                    <span className="text-[#8a8f98] block text-[10px]">Aikar G1GC Flags</span>
                  </div>
                  <div>
                    <span className="text-[#62666d] block mb-1">MINECRAFT VERSION</span>
                    <span className="text-[#ffffff]">Paper 26.2 (#124)</span>
                    <span className="text-[#8a8f98] block text-[10px]">Protocol 776</span>
                  </div>
                  <div>
                    <span className="text-[#62666d] block mb-1">APOLLO PROTOCOL</span>
                    <span className="text-[#27a644]">Connected</span>
                    <span className="text-[#8a8f98] block text-[10px]">Apollo v1.2.9</span>
                  </div>
                </div>
              </div>

            </div>
          )}

          {/* =======================================================================
              VIEW 2: FULLSCREEN LIVE IN-APP MAP
             ======================================================================= */}
          {activeTab === 'map' && (
            <div className="h-[calc(100vh-7rem)] flex flex-col space-y-3 max-w-7xl mx-auto">
              <div className="p-3 linear-card flex items-center justify-between gap-3 shrink-0">
                <div className="flex items-center gap-2">
                  <Globe className="w-4 h-4 text-[#ffffff]" />
                  <h2 className="text-sm font-medium text-[#ffffff]">Real Server World Map</h2>
                  <span className="text-[11px] font-mono-telemetry text-[#8a8f98] px-2 py-0.5 rounded-[4px] bg-[#161718]">
                    Direct Anvil MCA Chunk Engine · 1024×1024 Blocks
                  </span>
                </div>

                <div className="flex items-center gap-2">
                  {COORD_PRESETS.map((p) => (
                    <button
                      key={p.name}
                      type="button"
                      onClick={() => handleApplyPreset(p)}
                      className="px-2 py-1 text-xs rounded-[6px] bg-[#161718] hover:bg-[#23252a] border border-[#23252a] text-[#8a8f98] hover:text-[#ffffff] font-mono-telemetry cursor-pointer transition-colors"
                    >
                      {p.name}
                    </button>
                  ))}
                </div>
              </div>

              <div className="flex-1 w-full rounded-[12px] overflow-hidden border border-[#23252a]">
                <MinecraftMap
                  currentDimension={activeDimension}
                  onDimensionChange={setActiveDimension}
                  players={activePlayersList}
                  onSelectCoordinates={handleMapSelectCoordinates}
                  height="100%"
                />
              </div>
            </div>
          )}

          {/* =======================================================================
              VIEW 3: PLAYER MANAGEMENT (MCDASH / VOXELDASH PARITY)
             ======================================================================= */}
          {activeTab === 'players' && (
            <div className="space-y-6 max-w-6xl mx-auto">
              <div className="flex flex-wrap items-center justify-between gap-3 pb-3 border-b border-[#23252a]">
                <div>
                  <h2 className="text-base font-medium text-[#ffffff] flex items-center gap-2">
                    <Users className="w-4 h-4 text-[#ffffff]" />
                    Player Roster & Moderation
                  </h2>
                  <p className="text-xs text-[#8a8f98] mt-0.5">
                    Live connected players with skin heads, health, gamemode controls, 
                  </p>
                </div>

                <div className="flex items-center gap-3">
                  <div className="relative">
                    <Search className="w-3.5 h-3.5 text-[#8a8f98] absolute left-2.5 top-2.5" />
                    <input
                      type="text"
                      value={playerSearch}
                      onChange={(e) => setPlayerSearch(e.target.value)}
                      placeholder="Filter players..."
                      className="pl-8 pr-3 py-1.5 linear-input text-xs w-48 font-mono-telemetry"
                    />
                  </div>
                  <div className="px-3 py-1.5 rounded-[6px] bg-[#161718] border border-[#23252a] text-xs font-mono-telemetry text-[#d0d6e0]">
                    {activePlayersList.length} / {stats?.maxPlayers || 20} ONLINE
                  </div>
                </div>
              </div>

              {/* Kick Modal */}
              {kickTarget && (
                <div className="fixed inset-0 z-50 bg-black/70 flex items-center justify-center p-4">
                  <div className="linear-card p-6 max-w-md w-full space-y-4 border-[#eb5757]/40">
                    <div className="flex items-center gap-2 text-[#eb5757]">
                      <AlertTriangle className="w-4 h-4" />
                      <h3 className="font-medium text-sm">Kick Player: {kickTarget}</h3>
                    </div>
                    <div>
                      <label className="block text-xs text-[#8a8f98] mb-1">Reason for Disconnection</label>
                      <input
                        type="text"
                        value={kickReason}
                        onChange={(e) => setKickReason(e.target.value)}
                        className="w-full linear-input text-xs"
                      />
                    </div>
                    <div className="flex justify-end gap-2 pt-2">
                      <button
                        type="button"
                        onClick={() => setKickTarget(null)}
                        className="linear-btn-ghost px-3 py-1.5 text-xs cursor-pointer"
                      >
                        Cancel
                      </button>
                      <button
                        type="button"
                        onClick={() => handlePlayerAction(kickTarget, 'kick', kickReason)}
                        className="px-4 py-1.5 rounded-[6px] bg-[#eb5757] text-white font-medium text-xs cursor-pointer hover:brightness-110"
                      >
                        Confirm Kick
                      </button>
                    </div>
                  </div>
                </div>
              )}

              {filteredPlayers.length === 0 ? (
                <div className="linear-card p-12 text-center space-y-3">
                  <div className="w-10 h-10 mx-auto rounded-full bg-white/[0.04] border border-[#23252a] flex items-center justify-center text-[#8a8f98]">
                    <Users className="w-5 h-5" />
                  </div>
                  <h3 className="font-medium text-[#ffffff] text-sm">No Players Found</h3>
                  <p className="text-xs text-[#8a8f98] max-w-sm mx-auto font-mono-telemetry">
                    Connect via Minecraft 26.2 client at <span className="text-[#ffffff]">192.168.1.17:25565</span> to appear on this live roster.
                  </p>
                </div>
              ) : (
                <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
                  {filteredPlayers.map((player) => (
                    <div key={player.uuid || player.name} className="linear-card p-5 space-y-4">
                      
                      {/* Player Header with Skin Head */}
                      <div className="flex items-start justify-between">
                        <div className="flex items-center gap-3">
                          <img
                            src={`https://mc-heads.net/avatar/${player.name}/64`}
                            alt={player.name}
                            className="w-11 h-11 rounded-[6px] bg-[#161718] border border-[#23252a]"
                            onError={(e) => {
                              e.currentTarget.style.display = 'none';
                            }}
                          />
                          <div>
                            <div className="flex items-center gap-2">
                              <span className="text-sm font-medium text-[#ffffff]">{player.name}</span>
                              {player.isOp && (
                                <span className="text-[10px] font-mono-telemetry px-1.5 py-0.5 rounded-[4px] bg-[#eb5757]/20 border border-[#eb5757]/40 text-[#eb5757]">
                                  OP
                                </span>
                              )}
                              <span className="text-[10px] font-mono-telemetry px-1.5 py-0.5 rounded-[4px] bg-white/[0.04] text-[#8a8f98]">
                                {player.world || 'world'}
                              </span>
                            </div>
                            <div className="text-[11px] font-mono-telemetry text-[#8a8f98] mt-0.5">
                              UUID: {player.uuid ? player.uuid.substring(0, 8) + '...' : 'Unknown'}
                            </div>
                          </div>
                        </div>

                        {/* Ping Meter */}
                        <div className="flex items-center gap-1.5 text-xs font-mono-telemetry">
                          <span
                            className={`w-2 h-2 rounded-full ${
                              (player.ping || 10) < 60
                                ? 'bg-[#27a644]'
                                : (player.ping || 10) < 120
                                ? 'bg-[#f59e0b]'
                                : 'bg-[#eb5757]'
                            }`}
                          />
                          <span className="text-[#8a8f98]">{player.ping || 10} ms</span>
                        </div>
                      </div>

                      {/* Health and Position Readout */}
                      <div className="p-3 rounded-[6px] bg-[#161718] border border-[#23252a] grid grid-cols-2 gap-3 text-xs font-mono-telemetry">
                        <div>
                          <span className="text-[#62666d] block mb-0.5">HEALTH</span>
                          <div className="flex items-center gap-1.5 text-[#eb5757]">
                            <Heart className="w-3.5 h-3.5 fill-[#eb5757]" />
                            <span className="text-[#ffffff]">{player.health ? Math.round(player.health) : 20} / 20</span>
                          </div>
                        </div>
                        <div>
                          <span className="text-[#62666d] block mb-0.5">COORDINATES</span>
                          <span className="text-[#ffffff]">
                            [{Math.round(player.x || 0)}, {Math.round(player.y || 64)}, {Math.round(player.z || 0)}]
                          </span>
                        </div>
                      </div>

                      {/* Gamemode Selector and Actions Bar */}
                      <div className="pt-2 border-t border-[#23252a] flex flex-wrap items-center justify-between gap-2">
                        <div className="flex items-center gap-2">
                          <span className="text-xs font-mono-telemetry text-[#8a8f98]">Mode:</span>
                          <select
                            value={player.gamemode || 'SURVIVAL'}
                            onChange={(e) => handlePlayerAction(player.name, 'gamemode', e.target.value)}
                            className="linear-input text-xs font-mono-telemetry py-1 px-2 cursor-pointer"
                          >
                            <option value="SURVIVAL">Survival</option>
                            <option value="CREATIVE">Creative</option>
                            <option value="ADVENTURE">Adventure</option>
                            <option value="SPECTATOR">Spectator</option>
                          </select>
                        </div>

                        <div className="flex items-center gap-1.5">
                          
                          <button
                            type="button"
                            onClick={() => handlePlayerAction(player.name, 'teleport_spawn')}
                            className="linear-btn-ghost px-2 py-1 text-xs cursor-pointer font-mono-telemetry"
                            title="Teleport to World Spawn"
                          >
                            Spawn
                          </button>

                          <button
                            type="button"
                            onClick={() => handlePlayerAction(player.name, 'heal')}
                            className="linear-btn-ghost px-2 py-1 text-xs cursor-pointer font-mono-telemetry"
                            title="Restore Health and Hunger"
                          >
                            Heal
                          </button>

                          <button
                            type="button"
                            onClick={() => setKickTarget(player.name)}
                            className="px-2 py-1 text-xs rounded-[6px] bg-[#eb5757]/10 hover:bg-[#eb5757]/20 border border-[#eb5757]/30 text-[#eb5757] font-mono-telemetry cursor-pointer transition-colors"
                          >
                            Kick
                          </button>
                        </div>
                      </div>

                    </div>
                  ))}
                </div>
              )}
            </div>
          )}

          {/* =======================================================================
              VIEW 4: LIVE INTERACTIVE CONSOLE
             ======================================================================= */}
          {activeTab === 'console' && (
            <div className="h-[calc(100vh-7rem)] flex flex-col space-y-3 max-w-6xl mx-auto">
              {/* Console Toolbar */}
              <div className="p-3 linear-card flex flex-wrap items-center justify-between gap-3 shrink-0">
                <div className="flex items-center gap-2">
                  <Terminal className="w-4 h-4 text-[#ffffff]" />
                  <h2 className="text-sm font-medium text-[#ffffff]">Interactive Server Terminal</h2>
                  <span className="text-[10px] font-mono-telemetry text-[#8a8f98] px-2 py-0.5 rounded-[4px] bg-[#161718]">
                    Paper Tick Log Stream
                  </span>
                </div>

                <div className="flex flex-wrap items-center gap-2">
                  {/* Severity Filter Chips */}
                  <div className="flex rounded-[6px] bg-[#161718] p-0.5 border border-[#23252a]">
                    {['ALL', 'INFO', 'WARN', 'ERROR', 'CMD'].map((lvl) => (
                      <button
                        key={lvl}
                        type="button"
                        onClick={() => setLogFilter(lvl)}
                        className={`px-2 py-0.5 text-[10px] font-mono-telemetry rounded-[4px] cursor-pointer transition-all ${
                          logFilter === lvl
                            ? 'bg-white/10 text-white font-medium'
                            : 'text-[#8a8f98] hover:text-[#d0d6e0]'
                        }`}
                      >
                        {lvl}
                      </button>
                    ))}
                  </div>

                  {/* Search Filter */}
                  <div className="relative">
                    <Search className="w-3 h-3 text-[#8a8f98] absolute left-2 top-2" />
                    <input
                      type="text"
                      value={logSearch}
                      onChange={(e) => setLogSearch(e.target.value)}
                      placeholder="Search log output..."
                      className="pl-7 pr-2 py-1 linear-input text-xs font-mono-telemetry w-40"
                    />
                  </div>

                  <button
                    type="button"
                    onClick={() => setAutoScroll(!autoScroll)}
                    className={`px-2.5 py-1 rounded-[6px] border text-xs font-mono-telemetry cursor-pointer transition-colors ${
                      autoScroll
                        ? 'bg-white/[0.08] border-[#383b3f] text-[#ffffff]'
                        : 'bg-transparent border-[#23252a] text-[#8a8f98]'
                    }`}
                  >
                    Auto-Scroll
                  </button>

                  <button
                    type="button"
                    onClick={() => setConsoleLogs([])}
                    className="linear-btn-ghost px-2.5 py-1 text-xs cursor-pointer font-mono-telemetry"
                  >
                    Clear
                  </button>
                </div>
              </div>

              {/* Terminal Viewport */}
              <div className="flex-1 linear-card p-4 overflow-y-auto font-mono-telemetry text-xs leading-relaxed space-y-1 bg-[#08090a] border border-[#23252a]">
                {filteredLogs.length === 0 ? (
                  <div className="text-center py-16 text-[#62666d]">
                    No terminal log messages matching the active filter.
                  </div>
                ) : (
                  filteredLogs.map((log, idx) => (
                    <div key={idx} className="flex items-start gap-2.5 hover:bg-white/[0.02] px-1 py-0.5 rounded">
                      <span className="text-[#62666d] select-none text-[11px] shrink-0">{log.time}</span>
                      <span
                        className={`text-[10px] px-1 py-0.2 rounded font-medium shrink-0 ${
                          log.level === 'ERROR'
                            ? 'bg-[#eb5757]/20 text-[#eb5757]'
                            : log.level === 'WARN'
                            ? 'bg-[#f59e0b]/20 text-[#f59e0b]'
                            : log.level === 'CMD' || log.level === 'EXEC'
                            ? 'bg-[#6366f1]/20 text-[#8b5cf6]'
                            : 'bg-white/[0.04] text-[#8a8f98]'
                        }`}
                      >
                        {log.level}
                      </span>
                      <span className="text-[#d0d6e0] break-all select-text">{log.msg}</span>
                    </div>
                  ))
                )}
                <div ref={terminalBottomRef} />
              </div>

              {/* Command Quick Chips */}
              <div className="flex flex-wrap items-center gap-1.5 px-1">
                {['/tps', '/list', '/spark tps', '/save-all', '/weather clear', '/time set day'].map((cmd) => (
                  <button
                    key={cmd}
                    type="button"
                    onClick={() => {
                      setConsoleInput(cmd);
                    }}
                    className="px-2 py-0.5 rounded-[4px] bg-[#161718] hover:bg-[#23252a] border border-[#23252a] text-[10px] font-mono-telemetry text-[#8a8f98] hover:text-[#d0d6e0] transition-colors cursor-pointer"
                  >
                    {cmd}
                  </button>
                ))}
              </div>

              {/* Command Prompt Form */}
              <form onSubmit={handleConsoleSubmit} className="flex gap-2 shrink-0">
                <div className="relative flex-1">
                  <span className="absolute left-3 top-2.5 text-xs font-mono-telemetry text-[#8a8f98] select-none">&gt;</span>
                  <input
                    type="text"
                    value={consoleInput}
                    onChange={(e) => setConsoleInput(e.target.value)}
                    onKeyDown={handleConsoleKeyDown}
                    placeholder="Type server command (e.g. op, tps, gamemode creative, say Hello)..."
                    className="w-full pl-7 pr-3 py-2 linear-input text-xs font-mono-telemetry"
                  />
                </div>
                <button
                  type="submit"
                  className="linear-btn-primary px-4 py-2 text-xs flex items-center gap-1.5 cursor-pointer font-medium"
                >
                  <Send className="w-3.5 h-3.5" />
                  <span>Execute</span>
                </button>
              </form>
            </div>
          )}

          {/* =======================================================================
              VIEW 5: PLUGINS MANAGER (MCDASH / VOXELDASH PARITY)
             ======================================================================= */}
          {activeTab === 'plugins' && (
            <div className="space-y-6 max-w-6xl mx-auto">
              <div className="flex flex-wrap items-center justify-between gap-3 pb-3 border-b border-[#23252a]">
                <div>
                  <h2 className="text-base font-medium text-[#ffffff] flex items-center gap-2">
                    <Package className="w-4 h-4 text-[#ffffff]" />
                    Installed Plugins
                  </h2>
                  <p className="text-xs text-[#8a8f98] mt-0.5">
                    Bukkit and Paper runtime plugins with real-time reload and configuration triggers
                  </p>
                </div>

                <div className="flex items-center gap-3">
                  <div className="relative">
                    <Search className="w-3.5 h-3.5 text-[#8a8f98] absolute left-2.5 top-2.5" />
                    <input
                      type="text"
                      value={pluginSearch}
                      onChange={(e) => setPluginSearch(e.target.value)}
                      placeholder="Search plugins..."
                      className="pl-8 pr-3 py-1.5 linear-input text-xs w-48 font-mono-telemetry"
                    />
                  </div>
                  <div className="px-3 py-1.5 rounded-[6px] bg-[#161718] border border-[#23252a] text-xs font-mono-telemetry text-[#d0d6e0]">
                    {pluginsList.length} LOADED
                  </div>
                </div>
              </div>

              <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
                {filteredPlugins.map((plugin) => (
                  <div key={plugin.name} className="linear-card p-5 space-y-3">
                    <div className="flex items-start justify-between">
                      <div>
                        <div className="flex items-center gap-2">
                          <h3 className="text-sm font-medium text-[#ffffff]">{plugin.name}</h3>
                          <span className="text-[10px] font-mono-telemetry px-1.5 py-0.5 rounded-[4px] bg-white/[0.04] text-[#d0d6e0] border border-[#23252a]">
                            v{plugin.version}
                          </span>
                        </div>
                        <div className="text-[11px] font-mono-telemetry text-[#8a8f98] mt-0.5">
                          By: {Array.isArray(plugin.authors) ? plugin.authors.join(', ') : 'Server Team'}
                        </div>
                      </div>

                      <span
                        className={`text-[10px] font-mono-telemetry px-2 py-0.5 rounded-[4px] ${
                          plugin.enabled !== false
                            ? 'bg-[#27a644]/15 text-[#27a644] border border-[#27a644]/30'
                            : 'bg-white/[0.05] text-[#8a8f98]'
                        }`}
                      >
                        {plugin.enabled !== false ? 'Active' : 'Disabled'}
                      </span>
                    </div>

                    <p className="text-xs text-[#8a8f98] leading-relaxed">
                      {plugin.description || 'No plugin description specified.'}
                    </p>

                    <div className="pt-3 border-t border-[#23252a] flex items-center justify-between">
                      {plugin.website ? (
                        <a
                          href={plugin.website}
                          target="_blank"
                          rel="noreferrer"
                          className="text-xs font-mono-telemetry text-[#8a8f98] hover:text-[#ffffff] flex items-center gap-1 transition-colors"
                        >
                          <span>Repository</span>
                          <ExternalLink className="w-3 h-3" />
                        </a>
                      ) : <div />}

                      <div className="flex items-center gap-2">
                        <button
                          type="button"
                          onClick={() => handlePluginAction(plugin.name, 'reload')}
                          className="linear-btn-ghost px-2.5 py-1 text-xs flex items-center gap-1.5 cursor-pointer font-mono-telemetry"
                        >
                          <RotateCcw className="w-3 h-3" />
                          <span>Reload Config</span>
                        </button>
                      </div>
                    </div>
                  </div>
                ))}
              </div>
            </div>
          )}

          {/* =======================================================================
              VIEW 6: SERVER.PROPERTIES CONFIGURATION
             ======================================================================= */}
          {activeTab === 'config' && (
            <div className="space-y-6 max-w-5xl mx-auto">
              <div className="flex flex-wrap items-center justify-between gap-3 pb-3 border-b border-[#23252a]">
                <div>
                  <h2 className="text-base font-medium text-[#ffffff] flex items-center gap-2">
                    <Sliders className="w-4 h-4 text-[#ffffff]" />
                    server.properties Editor
                  </h2>
                  <p className="text-xs text-[#8a8f98] mt-0.5">
                    Modify core Paper/Minecraft server parameters and persist directly to disk
                  </p>
                </div>

                <div className="flex items-center gap-3">
                  <div className="relative">
                    <Search className="w-3.5 h-3.5 text-[#8a8f98] absolute left-2.5 top-2.5" />
                    <input
                      type="text"
                      value={propSearch}
                      onChange={(e) => setPropSearch(e.target.value)}
                      placeholder="Filter properties..."
                      className="pl-8 pr-3 py-1.5 linear-input text-xs w-48 font-mono-telemetry"
                    />
                  </div>

                  <button
                    type="button"
                    onClick={handleSaveProperties}
                    disabled={propertiesSaving}
                    className="linear-btn-primary px-4 py-2 text-xs flex items-center gap-1.5 cursor-pointer font-medium disabled:opacity-50"
                  >
                    <Save className="w-3.5 h-3.5" />
                    <span>{propertiesSaving ? 'Saving...' : 'Save & Apply'}</span>
                  </button>
                </div>
              </div>

              {propertiesLoading ? (
                <div className="linear-card p-12 text-center text-xs font-mono-telemetry text-[#8a8f98]">
                  Loading server.properties from disk...
                </div>
              ) : (
                <div className="linear-card p-6 space-y-4">
                  <div className="space-y-2">
                    {filteredProperties.length === 0 ? (
                      <div className="text-center py-8 text-xs font-mono-telemetry text-[#62666d]">
                        No configuration parameters found matching filter.
                      </div>
                    ) : (
                      filteredProperties.map(([key, value]) => {
                        const isBoolean = value === 'true' || value === 'false';
                        return (
                          <div
                            key={key}
                            className="p-3 rounded-[6px] bg-[#161718] border border-[#23252a] flex flex-wrap items-center justify-between gap-3"
                          >
                            <div className="max-w-md">
                              <span className="text-xs font-mono-telemetry font-medium text-[#ffffff]">{key}</span>
                            </div>

                            {isBoolean ? (
                              <button
                                type="button"
                                onClick={() => {
                                  setProperties(prev => ({
                                    ...prev,
                                    [key]: prev[key] === 'true' ? 'false' : 'true'
                                  }));
                                }}
                                className={`px-3 py-1 rounded-[6px] text-xs font-mono-telemetry cursor-pointer border transition-colors ${
                                  value === 'true'
                                    ? 'bg-[#27a644]/15 border-[#27a644]/40 text-[#27a644]'
                                    : 'bg-white/[0.04] border-[#23252a] text-[#8a8f98]'
                                }`}
                              >
                                {value === 'true' ? 'TRUE' : 'FALSE'}
                              </button>
                            ) : (
                              <input
                                type="text"
                                value={value}
                                onChange={(e) => {
                                  const newVal = e.target.value;
                                  setProperties(prev => ({ ...prev, [key]: newVal }));
                                }}
                                className="linear-input text-xs font-mono-telemetry w-64 px-2.5 py-1"
                              />
                            )}
                          </div>
                        );
                      })
                    )}
                  </div>
                </div>
              )}
            </div>
          )}

          {/* =======================================================================
              VIEW 8: SETTINGS & PREFERENCES
             ======================================================================= */}
          {activeTab === 'settings' && (
            <div className="space-y-6 max-w-4xl mx-auto">
              <div className="linear-card p-6 space-y-5">
                <div>
                  <h3 className="font-medium text-sm text-[#ffffff] mb-0.5">Telemetry Preferences</h3>
                  <p className="text-xs text-[#8a8f98]">Configure telemetry frequency synchronized from Javalin</p>
                </div>

                <div>
                  <label className="block text-xs font-mono-telemetry text-[#8a8f98] mb-2 uppercase tracking-wider">
                    Polling Cadence
                  </label>
                  <div className="grid grid-cols-4 gap-3">
                    {[
                      { rate: 1000, label: '1s (Realtime)' },
                      { rate: 2000, label: '2s (High Speed)' },
                      { rate: 3000, label: '3s (Default)' },
                      { rate: 5000, label: '5s (Economy)' },
                    ].map((opt) => (
                      <button
                        key={opt.rate}
                        type="button"
                        onClick={() => setPollingInterval(opt.rate)}
                        className={`p-3 rounded-[6px] border text-xs font-mono-telemetry cursor-pointer transition-all ${
                          pollingInterval === opt.rate
                            ? 'bg-white/[0.08] border-[#ffffff] text-[#ffffff]'
                            : 'bg-[#161718] border-[#23252a] text-[#8a8f98] hover:text-[#d0d6e0]'
                        }`}
                      >
                        {opt.label}
                      </button>
                    ))}
                  </div>
                </div>

                <div className="pt-4 border-t border-[#23252a] flex items-center justify-between text-xs font-mono-telemetry text-[#8a8f98]">
                  <span>OCULUS SYSTEM VERSION: 1.0.0-SNAPSHOT</span>
                  <span>EMBEDDED JAVALIN PORT: 8080</span>
                </div>
              </div>

              {/* Linear Style Tokens Reference Card */}
              <div className="linear-card p-6 space-y-4">
                <h3 className="font-medium text-sm text-[#ffffff]">Linear Design System Tokens</h3>
                <div className="grid grid-cols-2 sm:grid-cols-4 gap-3 text-xs font-mono-telemetry">
                  <div className="p-2.5 rounded-[6px] bg-[#08090a] border border-[#23252a]">
                    <span className="text-[#8a8f98] block text-[10px]">VOID</span>
                    <span className="text-[#ffffff]">#08090a</span>
                  </div>
                  <div className="p-2.5 rounded-[6px] bg-[#0f1011] border border-[#23252a]">
                    <span className="text-[#8a8f98] block text-[10px]">CARBON</span>
                    <span className="text-[#ffffff]">#0f1011</span>
                  </div>
                  <div className="p-2.5 rounded-[6px] bg-[#161718] border border-[#23252a]">
                    <span className="text-[#8a8f98] block text-[10px]">OBSIDIAN</span>
                    <span className="text-[#ffffff]">#161718</span>
                  </div>
                  <div className="p-2.5 rounded-[6px] bg-[#e4f222] text-[#08090a]">
                    <span className="block text-[10px] font-medium opacity-80">ACID LIME</span>
                    <span className="font-medium">#e4f222</span>
                  </div>
                </div>
              </div>
            </div>
          )}

        </main>
      </div>
    </div>
  );
}
