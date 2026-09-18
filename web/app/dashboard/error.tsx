"use client";

import { useEffect } from "react";
import { AlertTriangle, RefreshCw, LayoutDashboard } from "lucide-react";
import Link from "next/link";

interface DashboardErrorProps {
  error: Error & { digest?: string };
  reset: () => void;
}

export default function DashboardError({ error, reset }: DashboardErrorProps) {
  useEffect(() => {
    // Log runtime errors to console for diagnostics
    console.error("Dashboard error captured:", error);
  }, [error]);

  return (
    <div className="min-h-[70vh] flex items-center justify-center p-6">
      <div className="bg-vbg-surface-secondary border border-vbg-border-subtle rounded-vbg  max-w-lg w-full p-8 text-center space-y-6">
        <div className="w-12 h-12 mx-auto rounded-full bg-[--color-coral-red]/10 border border-[--color-coral-red]/20 flex items-center justify-center">
          <AlertTriangle className="w-6 h-6 text-red-500" />
        </div>

        <div className="space-y-2">
          <h2 className="text-xl font-semibold text-vbg-text-primary tracking-tight">
            Something went wrong in the dashboard
          </h2>
          <p className="text-sm text-vbg-text-secondary max-w-sm mx-auto">
            {error.message || "An unexpected runtime error occurred while rendering this dashboard view."}
          </p>
          {error.digest && (
            <div className="pt-2">
              <span className="inline-flex items-center px-1.5 py-0.5 rounded-vbg-small border border-vbg-border-subtle bg-vbg-surface-secondary text-xs font-mono text-vbg-text-secondary text-[10px]">
                Digest: {error.digest}
              </span>
            </div>
          )}
        </div>

        <div className="flex items-center justify-center gap-3 pt-2">
          <button
            type="button"
            onClick={() => reset()}
            className="bg-vbg-surface-contrast text-vbg-text-on-contrast rounded-vbg px-3 py-1.5 text-sm font-medium hover:bg-neutral-200 transition-colors flex items-center gap-2"
          >
            <RefreshCw className="w-4 h-4" />
            Try Again
          </button>
          <Link
            href="/dashboard"
            className="bg-vbg-surface-secondary border border-vbg-border-default text-vbg-text-primary rounded-vbg px-3 py-1.5 text-sm font-medium hover:bg-vbg-border-strong transition-colors flex items-center gap-2"
          >
            <LayoutDashboard className="w-4 h-4" />
            Overview
          </Link>
        </div>
      </div>
    </div>
  );
}
