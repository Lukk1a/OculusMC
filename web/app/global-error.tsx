"use client";

import { useEffect } from "react";
import { AlertOctagon, RefreshCw } from "lucide-react";

interface GlobalErrorProps {
  error: Error & { digest?: string };
  reset: () => void;
}

export default function GlobalError({ error, reset }: GlobalErrorProps) {
  useEffect(() => {
    // Log root-level uncaught exceptions
    console.error("Global application error captured:", error);
  }, [error]);

  return (
    <html lang="en">
      <body className="bg-[#000000] text-[#ededed] min-h-screen flex items-center justify-center p-6 antialiased font-sans m-0">
        <div className="max-w-md w-full rounded-xl bg-[#0a0a0a] border border-[#171717] p-8 text-center space-y-6 ">
          <div className="w-14 h-14 mx-auto rounded-full bg-[#ef4444]/10 border border-[#ef4444]/20 flex items-center justify-center">
            <AlertOctagon className="w-7 h-7 text-[#ef4444]" />
          </div>

          <div className="space-y-2">
            <h1 className="text-2xl font-bold text-white tracking-tight">
              Application Error
            </h1>
            <p className="text-sm text-vbg-text-secondary">
              {error.message || "A critical application error occurred. The application was unable to render."}
            </p>
            {error.digest && (
              <div className="pt-2">
                <span className="inline-block px-2 py-0.5 rounded text-[11px] font-mono bg-[#0a0a0a] border border-[#171717] text-vbg-text-secondary">
                  Digest: {error.digest}
                </span>
              </div>
            )}
          </div>

          <div className="pt-2">
            <button
              type="button"
              onClick={() => reset()}
              className="w-full py-2.5 px-4 rounded-md font-medium text-sm bg-[#ededed] text-[#000000] hover:brightness-105 active:scale-[0.98] transition cursor-pointer flex items-center justify-center gap-2"
            >
              <RefreshCw className="w-4 h-4" />
              Reload Application
            </button>
          </div>
        </div>
      </body>
    </html>
  );
}
