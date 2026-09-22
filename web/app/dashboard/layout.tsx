"use client";

import { useEffect, useState, ReactNode } from "react";
import { useRouter, usePathname } from "next/navigation";
import Link from "next/link";
import { getToken, refreshAccessToken, clearToken, hasNode } from "@/lib/api";
import { Loader2, Server, Menu, X, LayoutDashboard, Terminal, Users, Globe, FileText, Database, ArrowRightFromLine } from "lucide-react";
import { telemetryWs } from "@/lib/ws";

function Toaster() {
  const [toasts, setToasts] = useState<Array<{id: string, message: string, type: string}>>([]);

  useEffect(() => {
    const handleToast = (e: any) => {
      const newToast = { id: Math.random().toString(), message: e.detail.message, type: e.detail.type };
      setToasts(t => [...t, newToast]);
      setTimeout(() => {
        setToasts(t => t.filter(toast => toast.id !== newToast.id));
      }, 3000);
    };
    window.addEventListener('oculus-toast', handleToast);
    return () => window.removeEventListener('oculus-toast', handleToast);
  }, []);

  return (
    <div className="fixed bottom-4 right-4 z-50 flex flex-col gap-2 pointer-events-none">
      {toasts.map(t => (
        <div key={t.id} className="bg-card border border-border rounded-md  px-4 py-3 min-w-[250px] flex items-center gap-3">
          {t.type === 'success' && <div className="w-2 h-2 rounded-full bg-emerald-500 shrink-0" />}
          {t.type === 'error' && <div className="w-2 h-2 rounded-full bg-destructive shrink-0" />}
          {t.type === 'info' && <div className="w-2 h-2 rounded-full bg-blue-500 shrink-0" />}
          <span className="text-sm font-medium text-foreground tracking-normal">{t.message}</span>
        </div>
      ))}
    </div>
  );
}

export default function DashboardLayout({
  children,
}: {
  children: ReactNode;
}) {
  const router = useRouter();
  const pathname = usePathname();
  const [isAuthenticated, setIsAuthenticated] = useState(false);
  const [isLoading, setIsLoading] = useState(true);
  
  const [ping, setPing] = useState("14ms");
  const [onlineCount, setOnlineCount] = useState(0);
  const [maxPlayers, setMaxPlayers] = useState(20);
  const [isOnline, setIsOnline] = useState(true);
  
  const [isMobileMenuOpen, setIsMobileMenuOpen] = useState(false);

  useEffect(() => {
    async function checkAuth() {
      let token = getToken();
      if (!token) {
        token = await refreshAccessToken();
      }

      if (!token) {
        router.push(`/login?returnUrl=${encodeURIComponent(pathname)}`);
      } else {
        setIsAuthenticated(true);
      }
      setIsLoading(false);
    }

    checkAuth();
    
    // Telemetry websocket for ping/online count (simulated or real if backend is connected)
    telemetryWs.connect();
    const unsubscribe = telemetryWs.subscribe((data) => {
      if (data.type === 'status') {
        setPing(`${data.ping || 14}ms`);
        setOnlineCount(data.online || 0);
        setMaxPlayers(data.maxPlayers || 20);
        setIsOnline(data.isOnline ?? true);
      }
    });

    return () => {
      unsubscribe();
      telemetryWs.disconnect();
    };
  }, [router, pathname]);

  // Close mobile menu on navigate
  useEffect(() => {
    setIsMobileMenuOpen(false);
  }, [pathname]);

  if (isLoading) {
    return (
      <div className="min-h-screen bg-background flex items-center justify-center">
        <Loader2 className="w-8 h-8 text-muted-foreground animate-spin" />
      </div>
    );
  }

  if (!isAuthenticated) {
    return null;
  }

  const allNavItems = [
    { label: 'Overview',  href: '/dashboard',         icon: LayoutDashboard, requiredNode: 'dashboard.view' },
    { label: 'Console',   href: '/dashboard/console', icon: Terminal,        requiredNode: 'dashboard.console.read' },
    { label: 'Players',   href: '/dashboard/players', icon: Users,           requiredNode: 'dashboard.players.view' },
    { label: 'Live Map',  href: '/dashboard/map',     icon: Globe,           requiredNode: 'dashboard.view' },
    { label: 'Files',     href: '/dashboard/files',   icon: FileText,        requiredNode: 'dashboard.files.read' },
    { label: 'Worlds',    href: '/dashboard/worlds',  icon: Globe,           requiredNode: 'dashboard.worlds.edit' },
    { label: 'Backups',   href: '/dashboard/backups', icon: Database,        requiredNode: 'dashboard.backups.manage' },
    { label: 'Packages',  href: '/dashboard/packages',icon: FileText,        requiredNode: 'dashboard.packages.install' },
  ];

  const navItems = allNavItems.filter(item => hasNode(item.requiredNode));

  const currentNavItem = allNavItems.find(item => pathname === item.href || pathname.startsWith(item.href + '/'));
  if (currentNavItem && !hasNode(currentNavItem.requiredNode)) {
    if (pathname !== '/dashboard') {
      router.push('/dashboard');
      return null;
    } else {
      return (
        <div className="min-h-screen bg-background flex items-center justify-center">
          <p className="text-destructive font-medium">Access Denied. You lack the dashboard.view node.</p>
        </div>
      );
    }
  }

  const handleLogout = async () => {
    try {
      await fetch('/api/auth/logout', { method: 'POST', credentials: 'same-origin' });
    } catch (ignored) {}
    clearToken();
    window.location.href = '/login';
  };

  return (
    <div className="min-h-[100dvh] bg-background text-foreground flex flex-col md:grid md:grid-cols-[14rem_1fr] md:grid-rows-[3.5rem_1fr]">
      {/* Mobile Header */}
      <div className="md:hidden flex items-center justify-between px-4 h-14 border-b border-border bg-card shrink-0">
        <div className="flex items-center gap-2">
          <span className="text-sm font-semibold tracking-tight text-foreground">Oculus</span>
        </div>
        <button onClick={() => setIsMobileMenuOpen(!isMobileMenuOpen)} className="p-2 -mr-2 text-muted-foreground">
          {isMobileMenuOpen ? <X className="w-5 h-5" /> : <Menu className="w-5 h-5" />}
        </button>
      </div>

      {/* Sidebar Overlay for Mobile */}
      {isMobileMenuOpen && (
        <div className="md:hidden fixed inset-0 z-40 bg-black/50" onClick={() => setIsMobileMenuOpen(false)} />
      )}

      {/* Sidebar */}
      <aside
        className={`${isMobileMenuOpen ? 'flex' : 'hidden'} md:flex fixed inset-y-0 left-0 z-50 w-[14rem] md:static md:w-auto md:row-span-2 flex-col border-r border-border bg-card transition-transform`}
      >
        <div className="hidden md:flex h-14 items-center px-6 gap-2 shrink-0 border-b border-border">
          <span className="text-sm font-semibold tracking-tight text-foreground">Oculus</span>
        </div>

        <nav className="flex-1 px-3 py-4 space-y-1 overflow-y-auto">
          {navItems.map(({ label, href, icon: Icon }) => {
            const isActive = href === '/dashboard'
              ? pathname === '/dashboard' || pathname === '/dashboard/'
              : pathname === href || pathname.startsWith(href + '/');
            return (
              <Link 
                key={label} 
                href={href} 
                className={`flex items-center gap-3 px-3 py-2 text-sm rounded-md transition-colors ${
                  isActive 
                    ? 'bg-secondary text-foreground font-medium' 
                    : 'text-muted-foreground hover:text-foreground hover:bg-secondary/50'
                }`}
              >
                <Icon className="w-4 h-4 opacity-80" />
                {label}
              </Link>
            );
          })}
        </nav>
        
        {/* User profile / Logout at the bottom */}
        <div className="p-4 border-t border-border mt-auto">
          <button 
            className="flex items-center gap-3 w-full px-3 py-2 text-left text-sm rounded-md text-muted-foreground hover:text-foreground hover:bg-secondary/50 transition-colors" 
            onClick={handleLogout}
          >
            <ArrowRightFromLine className="w-4 h-4 opacity-80" />
            Sign out
          </button>
        </div>
      </aside>

      {/* Top Header (Desktop) */}
      <header className="hidden md:flex items-center justify-between px-6 border-b border-border bg-background shrink-0">
        <div className="flex items-center gap-6">
          <div className="flex items-center gap-2">
            <Server className="w-4 h-4 text-muted-foreground" />
            <span className="text-sm font-medium text-foreground tracking-tight">Main Server</span>
          </div>
          <div className="w-[1px] h-4 bg-border"></div>
          
          {/* Server Status: Monochrome/Meaningful color, JetBrains Mono values */}
          <div className="flex items-center gap-2">
            <span className={`w-2 h-2 rounded-full ${isOnline ? 'bg-emerald-500' : 'bg-destructive'}`}></span>
            <span className="text-sm font-mono text-muted-foreground">
              {isOnline ? 'Online' : 'Offline'}
            </span>
          </div>
          
          <div className="w-[1px] h-4 bg-border"></div>
          
          <div className="flex items-center gap-2">
             <span className="text-sm text-muted-foreground">Ping:</span>
             <span className="text-sm font-mono text-foreground">{ping}</span>
          </div>

          <div className="w-[1px] h-4 bg-border"></div>

          <div className="flex items-center gap-2">
             <span className="text-sm text-muted-foreground">Players:</span>
             <span className="text-sm font-mono text-foreground">{onlineCount}/{maxPlayers}</span>
          </div>
        </div>
      </header>

      {/* Main content */}
      <main className="flex-1 flex flex-col overflow-auto bg-background relative">
        <div className="flex-1 p-6 md:p-8 max-w-[1600px] mx-auto w-full">
          {children}
        </div>
        <Toaster />
      </main>
    </div>
  );
}
