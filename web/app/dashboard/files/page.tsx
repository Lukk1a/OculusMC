"use client";

import { toast } from "@/lib/toast";
import { useEffect, useState } from "react";
import { fetchApi, getToken } from "@/lib/api";
import { File as FileIcon, Folder, Trash, Upload, Download, ArrowLeft, RefreshCw } from "lucide-react";

export default function FilesPage() {
  const [currentPath, setCurrentPath] = useState("");
  const [files, setFiles] = useState<any[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  
  // File upload state
  const [uploading, setUploading] = useState(false);

  const loadFiles = async (path: string) => {
    setLoading(true);
    setError(null);
    try {
      const res = await fetchApi(`/api/files/list?path=${encodeURIComponent(path)}`);
      setFiles(res);
      setCurrentPath(path);
    } catch (err: any) {
      setError(err.message || "Failed to load files");
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    loadFiles("");
  }, []);

  const navigateTo = (path: string) => {
    loadFiles(path);
  };

  const navigateUp = () => {
    if (!currentPath) return;
    const parts = currentPath.split("/");
    parts.pop();
    loadFiles(parts.join("/"));
  };

  const deleteFile = async (path: string) => {
    if (!confirm(`Are you sure you want to delete ${path}?`)) return;
    try {
      await fetchApi(`/api/files/delete?path=${encodeURIComponent(path)}`, { method: "DELETE" });
      toast.success("File deleted successfully");
      loadFiles(currentPath);
    } catch (err: any) {
      toast.error("Failed to delete: " + err.message);
    }
  };

  const downloadFile = async (path: string) => {
    try {
      const token = getToken();
      const headers: Record<string, string> = {};
      if (token) {
        headers["Authorization"] = `Bearer ${token}`;
      }
      const res = await fetch(`/api/files/read?path=${encodeURIComponent(path)}`, {
        headers
      });
      if (!res.ok) {
        throw new Error(await res.text());
      }
      const blob = await res.blob();
      const url = window.URL.createObjectURL(blob);
      const a = document.createElement("a");
      a.href = url;
      a.download = path.split("/").pop() || "download";
      document.body.appendChild(a);
      a.click();
      a.remove();
      window.URL.revokeObjectURL(url);
    } catch (err: any) {
      toast.error("Failed to download: " + err.message);
    }
  };

  const handleFileUpload = async (e: React.ChangeEvent<HTMLInputElement>) => {
    if (!e.target.files || e.target.files.length === 0) return;
    
    const file = e.target.files[0];
    const targetPath = currentPath ? `${currentPath}/${file.name}` : file.name;
    
    setUploading(true);
    try {
      const token = getToken();
      const headers: Record<string, string> = {};
      if (token) {
        headers["Authorization"] = `Bearer ${token}`;
      }
      const res = await fetch(`/api/files/write?path=${encodeURIComponent(targetPath)}`, {
        method: "POST",
        headers,
        body: file // Raw body as octet-stream
      });
      
      if (!res.ok) {
        throw new Error(await res.text());
      }
      
      toast.success("File uploaded successfully");
      loadFiles(currentPath);
    } catch (err: any) {
      toast.error("Upload failed: " + err.message);
    } finally {
      setUploading(false);
      e.target.value = ""; // Reset input
    }
  };

  return (
    <div className="p-8">
      <div className="flex items-center justify-between mb-8">
        <div>
          <h1 className="text-2xl font-semibold text-vbg-text-primary tracking-tight">Files</h1>
          <p className="text-sm text-vbg-text-secondary mt-1">Manage server files and configurations</p>
        </div>
        
        <div className="flex gap-2">
          <button 
            onClick={() => loadFiles(currentPath)}
            aria-label="Refresh file list"
            className="bg-vbg-surface-secondary border border-vbg-border-default text-vbg-text-primary rounded-vbg px-3 py-1.5 text-sm font-medium hover:bg-vbg-border-strong transition-colors flex items-center gap-2"
          >
            <RefreshCw className="w-4 h-4" /> Refresh
          </button>
          
          <label className="bg-vbg-surface-contrast text-vbg-text-on-contrast rounded-vbg px-3 py-1.5 text-sm font-medium hover:bg-neutral-200 transition-colors flex items-center gap-2 cursor-pointer">
            <Upload className="w-4 h-4" /> 
            {uploading ? "Uploading..." : "Upload File"}
            <input 
              type="file" 
              className="hidden" 
              aria-label="Upload file"
              onChange={handleFileUpload} 
              disabled={uploading}
            />
          </label>
        </div>
      </div>

      <div className="bg-vbg-surface-secondary border border-vbg-border-subtle rounded-vbg  overflow-hidden">
        <div className="bg-[--color-obsidian] p-3 border-b border-vbg-border-subtle flex items-center gap-2 text-sm font-mono text-vbg-text-primary">
          <button 
            onClick={navigateUp}
            disabled={!currentPath}
            aria-label="Navigate to parent directory"
            className={`p-1 rounded hover:bg-[--color-graphite] ${!currentPath ? "opacity-50 cursor-not-allowed" : ""}`}
          >
            <ArrowLeft className="w-4 h-4" />
          </button>
          <span className="text-vbg-text-secondary">/</span>
          <span>{currentPath || ""}</span>
        </div>

        {error && (
          <div className="p-4 text-[var(--color-coral-red)] bg-red-400/10 border-b border-red-500/20 text-sm">
            {error}
          </div>
        )}

        <div className="p-0">
          {loading ? (
            <div className="p-8 text-center text-vbg-text-secondary text-sm flex items-center justify-center gap-2">
              <RefreshCw className="w-4 h-4 animate-spin" /> Loading...
            </div>
          ) : files.length === 0 ? (
            <div className="p-8 text-center text-vbg-text-secondary text-sm">
              This directory is empty.
            </div>
          ) : (
            <table className="w-full text-sm text-left">
              <thead>
                <tr className="border-b border-vbg-border-subtle text-vbg-text-secondary">
                  <th className="font-normal px-4 py-3">Name</th>
                  <th className="font-normal px-4 py-3 w-32">Size</th>
                  <th className="font-normal px-4 py-3 w-48">Modified</th>
                  <th className="font-normal px-4 py-3 w-24">Actions</th>
                </tr>
              </thead>
              <tbody>
                {files.map(file => (
                  <tr key={file.name} className="border-b border-vbg-border-subtle/50 hover:bg-[--color-obsidian]/50 transition-colors">
                    <td className="px-4 py-3">
                      {file.isDirectory ? (
                        <button 
                          onClick={() => navigateTo(file.path)}
                          className="flex items-center gap-2 text-vbg-text-primary hover:text-vbg-text-primary font-medium"
                        >
                          <Folder className="w-4 h-4 text-[var(--color-signal-teal)]" />
                          {file.name}
                        </button>
                      ) : (
                        <div className="flex items-center gap-2 text-vbg-text-primary">
                          <FileIcon className="w-4 h-4 text-vbg-text-secondary" />
                          {file.name}
                        </div>
                      )}
                    </td>
                    <td className="px-4 py-3 text-vbg-text-secondary">
                      {file.isDirectory ? "-" : (file.size / 1024).toFixed(1) + " KB"}
                    </td>
                    <td className="px-4 py-3 text-vbg-text-secondary">
                      {new Date(file.lastModified).toLocaleString()}
                    </td>
                    <td className="px-4 py-3">
                      <div className="flex items-center gap-2 text-vbg-text-secondary">
                        {!file.isDirectory && (
                          <button 
                            title="Download"
                            aria-label={`Download file ${file.name}`}
                            onClick={() => downloadFile(file.path)}
                            className="hover:text-vbg-text-primary"
                          >
                            <Download className="w-4 h-4" />
                          </button>
                        )}
                        <button 
                          title="Delete"
                          aria-label={`Delete ${file.isDirectory ? "folder" : "file"} ${file.name}`}
                          onClick={() => deleteFile(file.path)}
                          className="hover:text-[var(--color-coral-red)]"
                        >
                          <Trash className="w-4 h-4" />
                        </button>
                      </div>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          )}
        </div>
      </div>
    </div>
  );
}
