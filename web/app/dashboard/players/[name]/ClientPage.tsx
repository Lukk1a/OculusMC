"use client";

import { toast } from "@/lib/toast";
import { useState, useEffect, useRef } from "react";
import { useParams, useRouter } from "next/navigation";
import { fetchApi, hasNode } from "@/lib/api";
import { SkinViewer, WalkingAnimation } from "skinview3d";

export default function PlayerDetailsPage() {
  const params = useParams();
  const name = params.name as string;
  const router = useRouter();

  const [activeTab, setActiveTab] = useState<"inventory" | "enderchest" | "pdc">("inventory");
  const [inventory, setInventory] = useState<any>({});
  const [enderChest, setEnderChest] = useState<any>({});
  const [pdc, setPdc] = useState<Record<string, string>>({});
  
  const [loading, setLoading] = useState(true);
  const skinContainer = useRef<HTMLDivElement>(null);
  const viewerRef = useRef<SkinViewer | null>(null);

  // New item state
  const [editSlot, setEditSlot] = useState<{target: string, slot: number} | null>(null);
  const [newItemType, setNewItemType] = useState("DIAMOND");
  const [newItemAmount, setNewItemAmount] = useState(1);

  // PDC write state
  const [newPdcKey, setNewPdcKey] = useState("");
  const [newPdcValue, setNewPdcValue] = useState("");

  const loadData = async () => {
    try {
      const [invData, pdcData] = await Promise.all([
        fetchApi(`/api/players/${name}/inventory`),
        fetchApi(`/api/players/${name}/pdc`)
      ]);
      
      if (invData) {
        setInventory(invData.inventory || {});
        setEnderChest(invData.enderChest || {});
      }
      if (pdcData) {
        setPdc(pdcData || {});
      }
    } catch (e) {
      console.error(e);
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    loadData();
    const interval = setInterval(loadData, 5000);
    return () => clearInterval(interval);
  }, [name]);

  useEffect(() => {
    if (skinContainer.current && !viewerRef.current) {
      const viewer = new SkinViewer({
        canvas: document.createElement("canvas"),
        width: 300,
        height: 400,
        skin: `https://minotar.net/skin/${name}`
      });
      skinContainer.current.appendChild(viewer.canvas);
      viewer.animation = new WalkingAnimation();
      viewerRef.current = viewer;
    }
    
    return () => {
      if (viewerRef.current) {
        viewerRef.current.dispose();
        viewerRef.current = null;
      }
      if (skinContainer.current) {
        skinContainer.current.innerHTML = "";
      }
    };
  }, [name]);

  const handleEditSlot = async (action: "clear" | "set") => {
    if (!editSlot) return;
    try {
      await fetchApi(`/api/players/${name}/inventory/edit`, {
        method: "POST",
        body: JSON.stringify({
          target: editSlot.target,
          slot: editSlot.slot,
          action,
          type: action === "set" ? newItemType : undefined,
          amount: action === "set" ? newItemAmount : undefined
        })
      });
      setEditSlot(null);
      loadData();
    } catch (e) {
      console.error(e);
      toast.error("Failed to update slot");
    }
  };

  const handleWritePdc = async (e: React.FormEvent) => {
    e.preventDefault();
    try {
      await fetchApi(`/api/players/${name}/pdc`, {
        method: "POST",
        body: JSON.stringify({ key: newPdcKey, value: newPdcValue })
      });
      setNewPdcKey("");
      setNewPdcValue("");
      loadData();
    } catch (e) {
      console.error(e);
      toast.error("Failed to write PDC tag");
    }
  };

  const handleDeletePdc = async (key: string) => {
    if (!confirm(`Delete tag ${key}?`)) return;
    try {
      await fetchApi(`/api/players/${name}/pdc`, {
        method: "DELETE",
        body: JSON.stringify({ key })
      });
      loadData();
    } catch (e) {
      console.error(e);
      toast.error("Failed to delete PDC tag");
    }
  };

  const handleExportYaml = () => {
    window.open(`/api/players/${name}/export-gui`, "_blank");
  };

  const renderGrid = (size: number, data: any, targetName: string) => {
    const slots = [];
    const canEdit = hasNode('dashboard.players.edit_inventory');
    for (let i = 0; i < size; i++) {
      const item = data[i];
      const slotLabel = item ? `Slot ${i}: ${item.amount}x ${item.type}` : `Slot ${i}: Empty`;
      const handleSlotActivate = () => {
        if (canEdit) {
          setEditSlot({ target: targetName, slot: i });
        }
      };

      slots.push(
        <button 
          key={i} 
          type="button"
          tabIndex={0}
          aria-label={slotLabel}
          aria-disabled={!canEdit}
          onClick={handleSlotActivate}
          onKeyDown={(e) => {
            if (e.key === 'Enter' || e.key === ' ') {
              e.preventDefault();
              handleSlotActivate();
            }
          }}
          className={`w-12 h-12 bg-vbg-surface-primary border border-vbg-border-subtle flex items-center justify-center relative transition rounded focus-visible:outline-none focus-visible: focus-visible:ring-[var(--color-acid-lime)] ${canEdit ? 'cursor-pointer hover:border-blue-500' : 'cursor-not-allowed opacity-90'}`}
        >
          {item ? (
            <div className="text-center" title={`${item.amount}x ${item.type}`}>
              <div className="text-xs text-[var(--color-signal-teal)] font-bold truncate max-w-[44px]">{item.type.substring(0, 5)}</div>
              {item.amount > 1 && <div className="absolute bottom-0 right-1 text-[10px] text-vbg-text-primary font-bold">{item.amount}</div>}
            </div>
          ) : (
            <div className="text-[10px] text-[var(--color-smoke)]">{i}</div>
          )}
        </button>
      );
    }
    return <div className="grid grid-cols-9 gap-1 bg-vbg-surface-secondary p-2 rounded-lg inline-block" role="group" aria-label={`${targetName} grid`}>{slots}</div>;
  };

  return (
    <div className="space-y-6">
      <div className="flex items-center gap-4">
        <button onClick={() => router.back()} className="text-vbg-text-secondary hover:text-vbg-text-primary">← Back</button>
        <h1 className="text-3xl font-bold tracking-tight text-vbg-text-primary">{name}'s Profile</h1>
      </div>

      <div className="grid grid-cols-1 md:grid-cols-3 gap-6">
        <div className="bg-vbg-surface-secondary p-6 rounded-lg  border border-vbg-border-subtle flex flex-col items-center">
          <div ref={skinContainer} className="bg-vbg-surface-primary rounded-lg overflow-hidden border border-vbg-border-subtle mb-4" />
          <h2 className="text-xl font-bold text-vbg-text-primary mb-2">{name}</h2>
          <button 
            onClick={handleExportYaml}
            className="w-full py-2 bg-[var(--color-lavender)] hover:bg-[var(--color-lavender)]/80 text-vbg-text-primary rounded transition text-sm font-medium mt-4"
          >
            Export Layout to YAML
          </button>
        </div>

        <div className="md:col-span-2 bg-vbg-surface-secondary rounded-lg  border border-vbg-border-subtle overflow-hidden">
          <div className="flex border-b border-vbg-border-subtle">
            <button 
              className={`px-6 py-3 font-medium text-sm transition ${activeTab === 'inventory' ? 'border-b-2 border-blue-500 text-[var(--color-signal-teal)] bg-vbg-surface-primary/50' : 'text-vbg-text-secondary hover:text-vbg-text-primary'}`}
              onClick={() => setActiveTab('inventory')}
            >
              Inventory
            </button>
            <button 
              className={`px-6 py-3 font-medium text-sm transition ${activeTab === 'enderchest' ? 'border-b-2 border-blue-500 text-[var(--color-signal-teal)] bg-vbg-surface-primary/50' : 'text-vbg-text-secondary hover:text-vbg-text-primary'}`}
              onClick={() => setActiveTab('enderchest')}
            >
              Ender Chest
            </button>
            <button 
              className={`px-6 py-3 font-medium text-sm transition ${activeTab === 'pdc' ? 'border-b-2 border-blue-500 text-[var(--color-signal-teal)] bg-vbg-surface-primary/50' : 'text-vbg-text-secondary hover:text-vbg-text-primary'}`}
              onClick={() => setActiveTab('pdc')}
            >
              PDC Tags
            </button>
          </div>

          <div className="p-6">
            {loading && <div className="text-vbg-text-secondary">Loading...</div>}
            {!loading && activeTab === 'inventory' && renderGrid(41, inventory, "inventory")}
            {!loading && activeTab === 'enderchest' && renderGrid(27, enderChest, "enderchest")}
            
            {!loading && activeTab === 'pdc' && (
              <div className="space-y-6">
                {Object.keys(pdc).length === 0 ? (
                  <div className="text-vbg-text-secondary">No PDC tags found.</div>
                ) : (
                  <div className="space-y-2">
                    {Object.entries(pdc).map(([key, val]) => (
                      <div key={key} className="flex justify-between items-center p-3 bg-vbg-surface-primary border border-vbg-border-subtle rounded">
                        <div>
                          <div className="text-sm font-mono text-[var(--color-signal-teal)]">{key}</div>
                          <div className="text-sm text-vbg-text-primary">{val}</div>
                        </div>
                        {hasNode('dashboard.players.edit_pdc') && (
                          <button 
                            onClick={() => handleDeletePdc(key)} 
                            aria-label={`Delete PDC tag ${key}`}
                            className="text-[var(--color-coral-red)] hover:text-[var(--color-coral-red)] text-xs font-medium"
                          >
                            Delete
                          </button>
                        )}
                      </div>
                    ))}
                  </div>
                )}
                
                {hasNode('dashboard.players.edit_pdc') && (
                  <form onSubmit={handleWritePdc} className="p-4 bg-vbg-surface-primary border border-vbg-border-subtle rounded space-y-4 mt-6">
                    <h3 className="font-semibold text-vbg-text-primary">Add / Update Tag</h3>
                    <div className="flex gap-4">
                      <input 
                        type="text" 
                        placeholder="namespace:key" 
                        aria-label="PDC namespace and key"
                        value={newPdcKey}
                        onChange={e => setNewPdcKey(e.target.value)}
                        className="flex-1 px-3 py-2 bg-vbg-surface-secondary border border-vbg-border-subtle rounded text-vbg-text-primary" 
                        required 
                      />
                      <input 
                        type="text" 
                        placeholder="Value" 
                        aria-label="PDC tag value"
                        value={newPdcValue}
                        onChange={e => setNewPdcValue(e.target.value)}
                        className="flex-1 px-3 py-2 bg-vbg-surface-secondary border border-vbg-border-subtle rounded text-vbg-text-primary" 
                        required 
                      />
                      <button type="submit" aria-label="Save PDC tag" className="px-4 py-2 bg-green-500 hover:bg-green-500/80 text-vbg-text-primary rounded">Save</button>
                    </div>
                  </form>
                )}
              </div>
            )}
          </div>
        </div>
      </div>

      {editSlot && (
        <div className="fixed inset-0 bg-black/80 flex items-center justify-center p-4 z-50">
          <div className="bg-vbg-surface-secondary rounded-lg  border border-vbg-border-subtle p-6 w-full max-w-md" role="dialog" aria-modal="true" aria-labelledby="edit-slot-title">
            <h2 id="edit-slot-title" className="text-xl font-bold text-vbg-text-primary mb-4">Edit Slot {editSlot.slot} ({editSlot.target})</h2>
            
            <div className="space-y-4">
              <div>
                <label htmlFor="slot-item-type" className="block text-sm text-vbg-text-secondary mb-1">Item Type</label>
                <input 
                  id="slot-item-type"
                  type="text" 
                  aria-label="Item Type"
                  value={newItemType}
                  onChange={e => setNewItemType(e.target.value.toUpperCase())}
                  className="w-full px-3 py-2 bg-vbg-surface-primary border border-vbg-border-subtle rounded text-vbg-text-primary"
                  placeholder="DIAMOND"
                />
              </div>
              <div>
                <label htmlFor="slot-item-amount" className="block text-sm text-vbg-text-secondary mb-1">Amount</label>
                <input 
                  id="slot-item-amount"
                  type="number" 
                  aria-label="Amount"
                  min="1" 
                  max="64"
                  value={newItemAmount}
                  onChange={e => setNewItemAmount(parseInt(e.target.value) || 1)}
                  className="w-full px-3 py-2 bg-vbg-surface-primary border border-vbg-border-subtle rounded text-vbg-text-primary"
                />
              </div>
              
              <div className="flex justify-between pt-4 border-t border-vbg-border-subtle">
                <button 
                  onClick={() => handleEditSlot("clear")}
                  className="px-4 py-2 bg-red-500/20 text-[var(--color-coral-red)] hover:bg-red-500 hover:text-vbg-text-primary rounded transition"
                >
                  Clear Slot
                </button>
                <div className="flex gap-2">
                  <button 
                    onClick={() => setEditSlot(null)}
                    className="px-4 py-2 text-vbg-text-secondary hover:text-vbg-text-primary"
                  >
                    Cancel
                  </button>
                  <button 
                    onClick={() => handleEditSlot("set")}
                    className="px-4 py-2 bg-teal-500 hover:bg-teal-500/80 text-vbg-text-primary rounded transition"
                  >
                    Set Item
                  </button>
                </div>
              </div>
            </div>
          </div>
        </div>
      )}
    </div>
  );
}
