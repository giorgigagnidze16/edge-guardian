"use client";

import { useState, useEffect, useRef } from "react";

const hub = { x: 400, y: 250 };
const nodes = [
  { x: 140, y: 70, name: "rpi-gateway-01", delay: 0, status: "Connected", color: "#10b981" },
  { x: 675, y: 75, name: "jetson-ai-07", delay: 300, status: "Syncing", color: "#06b6d4" },
  { x: 670, y: 300, name: "esp32-temp-04", delay: 600, status: "Connected", color: "#10b981" },
  { x: 620, y: 440, name: "nuc-edge-05", delay: 900, status: "Connected", color: "#10b981" },
  { x: 170, y: 435, name: "rpi-sensor-02", delay: 1200, status: "Discovered", color: "#f59e0b" },
  { x: 135, y: 295, name: "android-field", delay: 1500, status: "Syncing", color: "#06b6d4" },
];

export function DiscoverySection() {
  const ref = useRef<HTMLDivElement>(null);
  const [active, setActive] = useState(false);

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

  return (
    <div ref={ref} className="reveal reveal-scale">
      <div className="mx-auto max-w-4xl">
        <svg viewBox="0 0 800 500" className="w-full h-auto">
          <defs>
            <filter id="pulse-glow">
              <feGaussianBlur stdDeviation="3" result="blur" />
              <feMerge>
                <feMergeNode in="blur" />
                <feMergeNode in="SourceGraphic" />
              </feMerge>
            </filter>
            <radialGradient id="hub-bg" cx="50%" cy="50%" r="50%">
              <stop offset="0%" stopColor="var(--color-primary)" stopOpacity="0.15" />
              <stop offset="100%" stopColor="var(--color-primary)" stopOpacity="0" />
            </radialGradient>
          </defs>

          {nodes.map((n, i) => {
            const len = Math.hypot(hub.x - n.x, hub.y - n.y);
            return (
              <g key={`l-${i}`}>
                <line x1={n.x} y1={n.y} x2={hub.x} y2={hub.y}
                  stroke="var(--color-primary)" strokeOpacity="0.06" strokeWidth="1" />
                <line x1={n.x} y1={n.y} x2={hub.x} y2={hub.y}
                  stroke="var(--color-primary)" strokeOpacity="0.3" strokeWidth="1.5"
                  strokeDasharray={len} strokeDashoffset={active ? 0 : len}
                  style={{ transition: `stroke-dashoffset 1.5s ease-out ${n.delay + 400}ms` }} />
              </g>
            );
          })}

          <circle cx={hub.x} cy={hub.y} r="80" fill="url(#hub-bg)"
            style={{ opacity: active ? 1 : 0, transition: "opacity 1s" }} />

          {active && (
            <>
              <circle cx={hub.x} cy={hub.y} r="44" fill="none"
                stroke="var(--color-primary)" strokeWidth="1"
                className="discovery-ring" style={{ transformOrigin: `${hub.x}px ${hub.y}px` }} />
              <circle cx={hub.x} cy={hub.y} r="44" fill="none"
                stroke="var(--color-primary)" strokeWidth="1"
                className="discovery-ring" style={{ transformOrigin: `${hub.x}px ${hub.y}px`, animationDelay: "1.5s" }} />
            </>
          )}

          <circle cx={hub.x} cy={hub.y} r="36"
            fill="var(--color-primary)" fillOpacity="0.1"
            stroke="var(--color-primary)" strokeOpacity="0.5" strokeWidth="2"
            style={{
              opacity: active ? 1 : 0,
              transform: active ? "scale(1)" : "scale(0.3)",
              transformOrigin: `${hub.x}px ${hub.y}px`,
              transition: "all 0.8s cubic-bezier(0.16,1,0.3,1)",
            }} />

          <path d="M400,237 l-12,6 v10 c0,8 5.5,14 12,16 c6.5,-2 12,-8 12,-16 v-10 z"
            fill="var(--color-primary)" fillOpacity="0.25" stroke="var(--color-primary)"
            strokeWidth="1.5" strokeLinejoin="round"
            style={{ opacity: active ? 1 : 0, transition: "opacity 0.6s ease-out 300ms" }} />
          <path d="M394,253 l4,4 l8,-8" fill="none" stroke="var(--color-primary)"
            strokeWidth="2" strokeLinecap="round" strokeLinejoin="round"
            style={{ opacity: active ? 1 : 0, transition: "opacity 0.6s ease-out 500ms" }} />

          <text x={hub.x} y={hub.y + 55} textAnchor="middle" fill="var(--color-primary)"
            style={{ fontSize: "13px", fontWeight: 700, fontFamily: "var(--font-sans)",
              opacity: active ? 1 : 0, transition: "opacity 0.6s ease-out 200ms" }}>
            EdgeGuardian Controller
          </text>

          {nodes.map((n, i) => {
            const isLeft = n.x < hub.x;
            const lx = isLeft ? n.x - 18 : n.x + 18;
            const anchor: "end" | "start" = isLeft ? "end" : "start";
            return (
              <g key={`d-${i}`}>
                <circle cx={n.x} cy={n.y} r="10"
                  fill="var(--color-card)" stroke="var(--color-primary)"
                  strokeOpacity="0.5" strokeWidth="2"
                  style={{
                    opacity: active ? 1 : 0,
                    transform: active ? "scale(1)" : "scale(0)",
                    transformOrigin: `${n.x}px ${n.y}px`,
                    transition: `all 0.5s cubic-bezier(0.16,1,0.3,1) ${n.delay + 200}ms`,
                  }} />
                <circle cx={n.x} cy={n.y} r="4" fill="var(--color-primary)"
                  style={{ opacity: active ? 1 : 0, transition: `opacity 0.4s ${n.delay + 400}ms` }} />
                <text x={lx} y={n.y - 2} textAnchor={anchor} fill="var(--color-foreground)"
                  style={{ fontSize: "12px", fontWeight: 600, fontFamily: "var(--font-mono)",
                    opacity: active ? 1 : 0, transition: `opacity 0.5s ${n.delay + 600}ms` }}>
                  {n.name}
                </text>
                <text x={lx} y={n.y + 14} textAnchor={anchor} fill={n.color}
                  style={{ fontSize: "10px", fontWeight: 600, letterSpacing: "0.05em",
                    opacity: active ? 1 : 0, transition: `opacity 0.5s ${n.delay + 1400}ms` }}>
                  ● {n.status}
                </text>
              </g>
            );
          })}

          {/* Traveling pulse dots */}
          {active && nodes.map((n, i) => (
            <circle key={`p-${i}`} r="3" fill="var(--color-primary)" filter="url(#pulse-glow)">
              <animateMotion dur={`${2 + i * 0.3}s`} repeatCount="indefinite"
                path={`M${n.x},${n.y} L${hub.x},${hub.y}`} />
            </circle>
          ))}
        </svg>
      </div>
    </div>
  );
}
