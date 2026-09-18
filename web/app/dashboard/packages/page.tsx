"use client";

import { toast } from "@/lib/toast";
import { useState } from "react";
import { fetchApi } from "@/lib/api";
import { Search, Download, Package, ExternalLink, Loader2 } from "lucide-react";

export default function PackagesPage() {
  const [query, setQuery] = useState("");
  const [results, setResults] = useState<any[]>([]);
  const [loading, setLoading] = useState(false);
  const [installing, setInstalling] = useState<string | null>(null);

  const searchModrinth = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!query) return;
    
    setLoading(true);
    try {
      // Direct call to Modrinth API (CORS allowed)
      const res = await fetch(`https://api.modrinth.com/v2/search?query=${encodeURIComponent(query)}&facets=[["categories:bukkit","categories:spigot","categories:paper","categories:purpur"]]&limit=10`);
      const data = await res.json();
      setResults(data.hits || []);
    } catch (err: any) {
      alert("Search failed: " + err.message);
    } finally {
      setLoading(false);
    }
  };

  const installPlugin = async (projectId: string, title: string) => {
    setInstalling(projectId);
    try {
      // 1. Get latest version for this project
      const versionsRes = await fetch(`https://api.modrinth.com/v2/project/${projectId}/version`);
      const versions = await versionsRes.json();
      
      if (!versions || versions.length === 0) {
        throw new Error("No versions found for this plugin");
      }
      
      const latest = versions[0];
      const file = latest.files.find((f: any) => f.primary) || latest.files[0];
      
      if (!file) throw new Error("No downloadable files found");
      
      // 2. Call our backend to download it
      await fetchApi(`/api/packages/install?url=${encodeURIComponent(file.url)}&filename=${encodeURIComponent(file.filename)}`, {
        method: "POST"
      });
      
      toast.error(`${title} installed successfully! Restart the server to load it.`);
    } catch (err: any) {
      alert("Install failed: " + err.message);
    } finally {
      setInstalling(null);
    }
  };

  return (
    <div className="space-y-6">
      <div className="mb-8">
        <h1 className="text-2xl font-semibold text-vbg-text-primary tracking-tight">Packages</h1>
        <p className="text-sm text-vbg-text-secondary mt-1">Search and install plugins from Modrinth</p>
      </div>

      <div className="bg-vbg-surface-secondary border border-vbg-border-subtle rounded-vbg  p-6 mb-8">
        <form onSubmit={searchModrinth} className="flex gap-4">
          <div className="relative flex-1">
            <Search className="w-5 h-5 absolute left-3 top-1/2 -translate-y-1/2 text-vbg-text-secondary" />
            <input 
              type="text" 
              value={query}
              onChange={(e) => setQuery(e.target.value)}
              placeholder="Search for plugins (e.g. EssentialsX, WorldEdit)..."
              className="bg-vbg-surface-primary border border-vbg-border-subtle rounded-vbg px-3 py-2 text-sm text-vbg-text-primary focus:outline-none focus:border-vbg-border-strong transition-colors w-full pl-10 py-3 text-base"
            />
          </div>
          <button type="submit" className="bg-vbg-surface-contrast text-vbg-text-on-contrast rounded-vbg px-3 py-1.5 text-sm font-medium hover:bg-neutral-200 transition-colors px-8" disabled={loading}>
            {loading ? <Loader2 className="w-5 h-5 animate-spin" /> : "Search"}
          </button>
        </form>
      </div>

      {results.length > 0 && (
        <div className="space-y-4">
          <h2 className="text-sm font-semibold text-vbg-text-secondary uppercase tracking-wider mb-4">Results</h2>
          
          <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
            {results.map((plugin) => (
              <div key={plugin.project_id} className="bg-vbg-surface-secondary border border-vbg-border-subtle rounded-vbg  p-5 flex flex-col">
                <div className="flex gap-4 mb-4">
                  <div className="w-12 h-12 rounded bg-[--color-obsidian] border border-vbg-border-subtle overflow-hidden shrink-0 flex items-center justify-center">
                    {plugin.icon_url ? (
                      <img src={plugin.icon_url} alt={plugin.title} className="w-full h-full object-cover" />
                    ) : (
                      <Package className="w-6 h-6 text-vbg-text-secondary" />
                    )}
                  </div>
                  <div>
                    <h3 className="text-lg font-medium text-vbg-text-primary leading-tight">{plugin.title}</h3>
                    <p className="text-xs text-vbg-text-secondary mt-1">by {plugin.author}</p>
                  </div>
                </div>
                
                <p className="text-sm text-vbg-text-secondary flex-1 mb-4 line-clamp-2">
                  {plugin.description}
                </p>
                
                <div className="flex items-center justify-between mt-auto pt-4 border-t border-vbg-border-subtle">
                  <a 
                    href={`https://modrinth.com/plugin/${plugin.slug}`} 
                    target="_blank" 
                    rel="noopener noreferrer"
                    className="text-xs text-[var(--color-signal-teal)] hover:text-[var(--color-signal-teal)] flex items-center gap-1"
                  >
                    View on Modrinth <ExternalLink className="w-3 h-3" />
                  </a>
                  
                  <button 
                    onClick={() => installPlugin(plugin.project_id, plugin.title)}
                    disabled={installing === plugin.project_id}
                    className="bg-vbg-surface-secondary border border-vbg-border-default text-vbg-text-primary rounded-vbg px-3 py-1.5 text-sm font-medium hover:bg-vbg-border-strong transition-colors text-xs flex items-center gap-2"
                  >
                    {installing === plugin.project_id ? (
                      <><Loader2 className="w-3 h-3 animate-spin" /> Installing...</>
                    ) : (
                      <><Download className="w-3 h-3" /> Install</>
                    )}
                  </button>
                </div>
              </div>
            ))}
          </div>
        </div>
      )}
      
      {!loading && query && results.length === 0 && (
        <div className="text-center p-12 text-vbg-text-secondary">
          No plugins found matching "{query}"
        </div>
      )}
    </div>
  );
}
