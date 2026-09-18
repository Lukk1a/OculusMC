"use client";

import { useEffect, useRef } from "react";
import { SkinViewer, IdleAnimation } from "skinview3d";

export function PlayerSkin3D({ name, width = 32, height = 48 }: { name: string, width?: number, height?: number }) {
  const container = useRef<HTMLDivElement>(null);
  const viewerRef = useRef<SkinViewer | null>(null);

  useEffect(() => {
    if (container.current && !viewerRef.current) {
      const viewer = new SkinViewer({
        canvas: document.createElement("canvas"),
        width,
        height,
        skin: `https://minotar.net/skin/${name}`,
      });
      container.current.appendChild(viewer.canvas);
      viewer.animation = new IdleAnimation();
      viewerRef.current = viewer;
    }
    
    return () => {
      if (viewerRef.current) {
        viewerRef.current.dispose();
        viewerRef.current = null;
      }
      if (container.current) {
        container.current.innerHTML = "";
      }
    };
  }, [name, width, height]);

  return <div ref={container} className="inline-block" style={{ width, height }} />;
}
