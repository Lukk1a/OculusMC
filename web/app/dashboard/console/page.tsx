"use client";

import { useEffect, useState, useRef, KeyboardEvent } from "react";
import { Terminal as TerminalIcon, ChevronRight } from "lucide-react";
import { consoleWs } from "@/lib/ws";
import { hasNode } from "@/lib/api";

const ANSI_COLORS: Record<string, string> = {
  "30": "var(--color-foreground)", // Map black to fg for visibility
  "31": "#ef4444", // red
  "32": "#10b981", // emerald
  "33": "#eab308", // yellow
  "34": "#3b82f6", // blue
  "35": "#d946ef", // fuchsia
  "36": "#06b6d4", // cyan
  "37": "#f4f4f5", // zinc-100
  "90": "#71717a", // zinc-500
  "91": "#f87171", // red-400
  "92": "#34d399", // emerald-400
  "93": "#facc15", // yellow-400
  "94": "#60a5fa", // blue-400
  "95": "#e879f9", // fuchsia-400
  "96": "#22d3ee", // cyan-400
  "97": "#fafafa", // zinc-50
};

function AnsiText({ text }: { text: string }) {
  // Basic ANSI regex parser
  const parts = text.split(/(\x1b\[[0-9;]*m)/g);

  const result = [];
  let currentColor: string | undefined = undefined;
  let currentKey = 0;

  for (const part of parts) {
    if (part.startsWith("\x1b[")) {
      const code = part.replace("\x1b[", "").replace("m", "");
      if (code === "0" || code === "") {
        currentColor = undefined;
      } else {
        const codes = code.split(";");
        for (const c of codes) {
          if (ANSI_COLORS[c]) {
            currentColor = ANSI_COLORS[c];
          }
        }
      }
    } else if (part) {
      result.push(
        <span key={currentKey++} style={{ color: currentColor }}>
          {part}
        </span>
      );
    }
  }

  return <>{result}</>;
}

interface LogEntry {
  id: string;
  text: string;
}

export default function ConsolePage() {
  const [logs, setLogs] = useState<LogEntry[]>([]);
  const [input, setInput] = useState("");
  const [tabCompletions, setTabCompletions] = useState<string[]>([]);
  const [history, setHistory] = useState<string[]>([]);
  const [historyIndex, setHistoryIndex] = useState(-1);
  const bottomRef = useRef<HTMLDivElement>(null);
  const inputRef = useRef<HTMLInputElement>(null);

  useEffect(() => {
    consoleWs.connect();

    const unsubscribe = consoleWs.subscribe((data) => {
      if (data.type === "hello") {
        setLogs((prev) => [
          ...prev,
          {
            id: Math.random().toString(),
            text: "\x1b[36m[System]\x1b[0m Connected to Oculus Console.",
          },
        ]);
      } else if (data.type === "log") {
        const message = data.message || data.data || data.text || "";
        setLogs((prev) => [
          ...prev,
          { id: Math.random().toString(), text: message },
        ]);
      } else if (data.type === "tab_completion") {
        const completions = data.completions || data.results || data.matches || [];
        setTabCompletions(completions);
      }
    });

    return () => {
      unsubscribe();
      consoleWs.disconnect();
    };
  }, []);

  useEffect(() => {
    if (bottomRef.current) {
      bottomRef.current.scrollIntoView({ behavior: "smooth" });
    }
  }, [logs, tabCompletions]);

  const handleConsoleClick = () => {
    const selection = window.getSelection();
    if (!selection || selection.toString().length === 0) {
      inputRef.current?.focus();
    }
  };

  const handleKeyDown = (e: KeyboardEvent<HTMLInputElement>) => {
    if (e.key === "Enter") {
      e.preventDefault();
      if (!input.trim()) return;

      setLogs((prev) => [
        ...prev,
        { id: Math.random().toString(), text: `\x1b[90m> ${input}\x1b[0m` },
      ]);

      consoleWs.send({ type: "command", command: input });
      setHistory((prev) => [...prev, input]);
      setHistoryIndex(-1);
      setInput("");
      setTabCompletions([]);
    } else if (e.key === "Tab") {
      e.preventDefault();
      if (input.trim()) {
        consoleWs.send({ type: "tab_complete", command: input });
      }
    } else if (e.key === "ArrowUp") {
      e.preventDefault();
      if (historyIndex < history.length - 1) {
        const newIndex = historyIndex + 1;
        setHistoryIndex(newIndex);
        setInput(history[history.length - 1 - newIndex]);
      }
    } else if (e.key === "ArrowDown") {
      e.preventDefault();
      if (historyIndex > 0) {
        const newIndex = historyIndex - 1;
        setHistoryIndex(newIndex);
        setInput(history[history.length - 1 - newIndex]);
      } else if (historyIndex === 0) {
        setHistoryIndex(-1);
        setInput("");
      }
    } else if (e.key === "Escape") {
      setTabCompletions([]);
    }
  };

  const handleCompletionClick = (completion: string) => {
    setInput(completion);
    setTabCompletions([]);
    inputRef.current?.focus();
  };

  return (
    <div className="flex h-full flex-col">
      <div className="flex h-full flex-col overflow-hidden border border-border bg-card">
        
        {/* Terminal Header */}
        <div className="flex shrink-0 items-center justify-between border-b border-border bg-[#0a0a0a] px-4 py-3">
          <div className="flex items-center gap-2">
            <TerminalIcon className="h-4 w-4 text-muted-foreground" />
            <h1 className="text-sm font-medium tracking-tight text-foreground">
              Server Terminal
            </h1>
          </div>
          <div className="flex gap-1.5 opacity-50">
            <div className="h-2.5 w-2.5 rounded-full bg-border" />
            <div className="h-2.5 w-2.5 rounded-full bg-border" />
            <div className="h-2.5 w-2.5 rounded-full bg-border" />
          </div>
        </div>

        {/* Terminal Output Area */}
        <div 
          className="flex-1 overflow-y-auto bg-[#0a0a0a] p-4 font-mono text-[13px] leading-relaxed text-foreground antialiased selection:bg-secondary"
          onClick={handleConsoleClick}
        >
          <div className="flex flex-col gap-1">
            {logs.map((log) => (
              <div key={log.id} className="break-words whitespace-pre-wrap">
                <AnsiText text={log.text} />
              </div>
            ))}
            <div ref={bottomRef} className="h-1" />
          </div>
        </div>

        {/* Input Area (Sticky Bottom) */}
        <div className="relative shrink-0 border-t border-border bg-[#0a0a0a]">
          
          {/* Tab Completion Overlay */}
          {tabCompletions.length > 0 && (
            <div className="absolute bottom-full left-0 flex max-h-48 w-full flex-wrap gap-1.5 overflow-y-auto border-t border-border bg-[#0a0a0a] p-2">
              {tabCompletions.map((comp, idx) => (
                <button
                  key={idx}
                  onClick={() => handleCompletionClick(comp)}
                  className="rounded bg-[#0a0a0a] px-2.5 py-1 font-mono text-[13px] text-muted-foreground transition-colors hover:bg-secondary hover:text-foreground focus:outline-none focus: focus:ring-border"
                >
                  {comp}
                </button>
              ))}
            </div>
          )}

          {hasNode("dashboard.console.execute") ? (
            <div className="flex w-full items-center px-4 py-3">
              <ChevronRight className="mr-2 h-4 w-4 shrink-0 text-muted-foreground" />
              <input
                ref={inputRef}
                type="text"
                value={input}
                onChange={(e) => {
                  setInput(e.target.value);
                  if (tabCompletions.length > 0) setTabCompletions([]);
                }}
                onKeyDown={handleKeyDown}
                className="flex-1 bg-transparent font-mono text-[13px] text-foreground outline-none placeholder:text-muted-foreground/50"
                placeholder="Type a command... (Press Tab to autocomplete)"
                spellCheck={false}
                autoComplete="off"
                autoFocus
              />
            </div>
          ) : (
            <div className="flex w-full items-center px-4 py-3 opacity-50">
              <ChevronRight className="mr-2 h-4 w-4 shrink-0 text-muted-foreground" />
              <input
                type="text"
                disabled
                className="flex-1 cursor-not-allowed bg-transparent font-mono text-[13px] text-muted-foreground outline-none"
                placeholder="You do not have permission to execute commands."
              />
            </div>
          )}
        </div>

      </div>
    </div>
  );
}
