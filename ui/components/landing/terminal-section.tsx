"use client";

import { useState, useEffect, useRef } from "react";

const TERMINAL_LINES: Array<{
  type: "cmd" | "out" | "gap";
  text: string;
  cls?: string;
}> = [
  { type: "cmd", text: "ssh edge@rpi-gateway-01" },
  { type: "out", text: "Connected to rpi-gateway-01 (192.168.1.42)", cls: "text-emerald-600 dark:text-emerald-400" },
  { type: "gap", text: "" },
  { type: "cmd", text: "edgeguard status" },
  { type: "out", text: "\u25cf EdgeGuardian Agent v2.4.1", cls: "font-semibold text-cyan-700 dark:text-cyan-300" },
  { type: "out", text: "  Status:     active (running)" },
  { type: "out", text: "  Uptime:     14d 6h 32m" },
  { type: "out", text: "  Fleet:      connected (1,847 devices)" },
  { type: "out", text: "  Last sync:  2s ago" },
  { type: "gap", text: "" },
  { type: "cmd", text: "edgeguard deploy --rolling firmware-v2.5.0" },
  { type: "out", text: "\u2713 Artifact signature verified (sha256:a3f8\u2026c291)", cls: "text-emerald-600 dark:text-emerald-400" },
  { type: "out", text: "\u25b8 Rolling deployment to 847 devices\u2026", cls: "text-cyan-700 dark:text-cyan-400" },
  { type: "out", text: "  \u2588\u2588\u2588\u2588\u2588\u2588\u2588\u2588\u2588\u2588\u2588\u2588\u2588\u2588\u2588\u2588\u2591\u2591\u2591\u2591  78% complete", cls: "text-cyan-600 dark:text-cyan-300/80" },
  { type: "out", text: "  661 updated \u00b7 183 pending \u00b7 3 queued" },
];

export function TerminalSection() {
  const ref = useRef<HTMLDivElement>(null);
  const [active, setActive] = useState(false);
  const [visibleCount, setVisibleCount] = useState(0);

  useEffect(() => {
    const el = ref.current;
    if (!el) return;
    const io = new IntersectionObserver(
      ([entry]) => {
        if (entry.isIntersecting) {
          setActive(true);
          io.disconnect();
        }
      },
      { threshold: 0.15 },
    );
    io.observe(el);
    return () => io.disconnect();
  }, []);

  useEffect(() => {
    if (!active) return;
    const timers: ReturnType<typeof setTimeout>[] = [];
    let cumulative = 400;

    TERMINAL_LINES.forEach((line, i) => {
      const delay =
        line.type === "cmd"
          ? 500 + line.text.length * 22
          : line.type === "gap"
            ? 150
            : 90;
      cumulative += delay;
      timers.push(setTimeout(() => setVisibleCount(i + 1), cumulative));
    });

    return () => timers.forEach(clearTimeout);
  }, [active]);

  return (
    <div ref={ref} className="reveal reveal-scale">
      <div className="mx-auto max-w-4xl rounded-2xl bg-white dark:bg-[#0c0c16] border border-border dark:border-white/10 shadow-xl shadow-black/[0.06] dark:shadow-black/40 overflow-hidden">
        <div className="flex items-center gap-2.5 px-5 py-3.5 border-b border-border dark:border-white/[0.08] bg-slate-50 dark:bg-white/[0.04]">
          <div className="h-3 w-3 rounded-full bg-red-500/80" />
          <div className="h-3 w-3 rounded-full bg-amber-500/80" />
          <div className="h-3 w-3 rounded-full bg-emerald-500/80" />
          <span className="ml-4 text-xs text-muted-foreground dark:text-zinc-500 tracking-wide font-mono">
            edge@rpi-gateway-01 &mdash; edgeguard
          </span>
        </div>

        <div className="p-6 sm:p-8 font-mono text-sm sm:text-base leading-relaxed min-h-[380px]">
          {TERMINAL_LINES.slice(0, visibleCount).map((line, i) => {
            if (line.type === "gap") return <div key={i} className="h-3" />;
            return (
              <div
                key={i}
                className={`${line.cls || "text-slate-600 dark:text-zinc-400"} ${
                  i === visibleCount - 1
                    ? "animate-[fadeIn_0.15s_ease-out]"
                    : ""
                }`}
              >
                {line.type === "cmd" && (
                  <span className="text-emerald-600 dark:text-emerald-400 select-none">
                    ${" "}
                  </span>
                )}
                {line.text}
              </div>
            );
          })}
          {visibleCount > 0 && (
            <span
              className="inline-block w-2.5 h-5 bg-emerald-600 dark:bg-emerald-400/90 mt-1 align-middle"
              style={{ animation: "cursorBlink 1s step-end infinite" }}
            />
          )}
        </div>
      </div>
    </div>
  );
}
