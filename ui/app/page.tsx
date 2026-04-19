import { redirect } from "next/navigation";
import { auth } from "@/lib/auth";
import { LogoIcon } from "@/components/logo";
import { ScrollReveal } from "@/components/landing/scroll-reveal";
import { LandingNavbar } from "@/components/landing/navbar";
import { SignInButton } from "@/components/landing/sign-in-button";
import { ParallaxWrapper } from "@/components/landing/parallax-wrapper";
import { MarqueeSection } from "@/components/landing/marquee-section";
import { TerminalSection } from "@/components/landing/terminal-section";
import { DiscoverySection } from "@/components/landing/discovery-section";

const MOCK_DEVICES = [
  { name: "rpi-gateway-01", status: "online", cpu: "23%", mem: "41%", region: "us-west" },
  { name: "jetson-ai-07", status: "online", cpu: "67%", mem: "72%", region: "eu-central" },
  { name: "esp32-temp-04", status: "degraded", cpu: "89%", mem: "34%", region: "ap-south" },
  { name: "nuc-edge-05", status: "online", cpu: "12%", mem: "28%", region: "us-east" },
  { name: "rpi-sensor-02", status: "online", cpu: "45%", mem: "55%", region: "eu-west" },
];

const CHART_BARS = [40, 55, 38, 72, 65, 48, 82, 58, 45, 90, 75, 62, 50, 68, 78, 42, 85, 55, 70, 60];

const LOG_ENTRIES = [
  { time: "14:32:01", level: "info", msg: "Heartbeat received from rpi-gateway-01", src: "fleet" },
  { time: "14:32:03", level: "warn", msg: "CPU threshold exceeded (89%) on esp32-temp-04", src: "health" },
  { time: "14:32:05", level: "info", msg: "Firmware v2.5.0 verified, initiating install", src: "ota" },
  { time: "14:32:06", level: "info", msg: "Desired state reconciled on jetson-ai-07", src: "reconciler" },
  { time: "14:32:08", level: "info", msg: "OTA rollout 92% complete for fleet-west", src: "deploy" },
];

const ST_DOT: Record<string, string> = {
  online: "bg-emerald-400",
  degraded: "bg-amber-400",
  offline: "bg-zinc-600",
};

export default async function LandingPage() {
  const session = await auth();
  if (session?.user && !(session as { error?: string }).error) {
    redirect("/dashboard");
  }
  return (
    <ScrollReveal>
      <div className="relative min-h-screen bg-background text-foreground [overflow-x:clip]">
        {/* Atmosphere */}
        <div className="pointer-events-none fixed inset-0 z-0">
          <div className="absolute -top-40 right-1/4 h-[700px] w-[700px] rounded-full bg-cyan-400/[0.08] dark:bg-cyan-500/[0.07] blur-[200px]" />
          <div className="absolute top-2/3 -left-20 h-[600px] w-[600px] rounded-full bg-violet-400/[0.06] dark:bg-violet-500/[0.05] blur-[180px]" />
          <div className="absolute bottom-0 right-0 h-[500px] w-[500px] rounded-full bg-emerald-400/[0.05] dark:bg-emerald-500/[0.03] blur-[160px]" />
          <div
            className="absolute inset-0 dark:hidden opacity-[0.03]"
            style={{
              backgroundImage:
                "linear-gradient(rgba(0,0,0,.06) 1px, transparent 1px), linear-gradient(90deg, rgba(0,0,0,.06) 1px, transparent 1px)",
              backgroundSize: "80px 80px",
            }}
          />
          <div
            className="absolute inset-0 hidden dark:block opacity-[0.02]"
            style={{
              backgroundImage:
                "linear-gradient(rgba(255,255,255,.1) 1px, transparent 1px), linear-gradient(90deg, rgba(255,255,255,.1) 1px, transparent 1px)",
              backgroundSize: "80px 80px",
            }}
          />
        </div>

        <LandingNavbar />

        <section className="relative z-10 mx-auto max-w-7xl px-6 pt-32 pb-24 sm:px-12 lg:px-16 lg:pt-40 min-h-screen flex items-center">
          <div className="grid lg:grid-cols-[1fr_1.2fr] gap-16 lg:gap-24 items-center w-full">
            <div className="reveal reveal-up">
              <p className="text-sm font-semibold text-primary uppercase tracking-widest mb-6">
                Kubernetes-style orchestration for IoT
              </p>
              <h1 className="text-4xl font-bold tracking-tight sm:text-5xl lg:text-6xl leading-[1.1]">
                Deploy to thousands.
                <br />
                <span className="text-gradient">Monitor everything.</span>
              </h1>
              <p className="mt-6 max-w-lg text-lg text-muted-foreground leading-relaxed">
                Sub-5MB agents that self-heal. Declarative YAML config. OTA
                updates with automatic rollback. Built for ARM, x86, and
                everything in between.
              </p>
              <div className="mt-10 flex flex-wrap gap-4">
                <SignInButton size="lg" className="px-10 text-base font-bold h-13">
                  Get Started
                </SignInButton>
                <a
                  href="#terminal"
                  className="inline-flex items-center justify-center rounded-md border border-input bg-background px-10 text-base h-13 font-medium hover:bg-accent hover:text-accent-foreground transition-colors"
                >
                  See It In Action
                </a>
              </div>
            </div>

            {/* Dashboard Mockup with parallax */}
            <div className="reveal reveal-right hidden lg:block">
              <ParallaxWrapper>
                <DashboardMockup />
              </ParallaxWrapper>
            </div>
          </div>
        </section>

        <section className="relative z-10 mx-auto max-w-6xl px-6 sm:px-12 lg:px-16">
          <div className="reveal reveal-up grid grid-cols-2 lg:grid-cols-4 gap-px rounded-2xl border border-border bg-border overflow-hidden shadow-sm shadow-black/[0.03] dark:shadow-none">
            {[
              { value: "1,847", label: "Devices Managed", suffix: "" },
              { value: "99.9", label: "Uptime SLA", suffix: "%" },
              { value: "< 5", label: "Agent Binary", suffix: " MB" },
              { value: "30", label: "Reconcile Cycle", suffix: "s" },
            ].map((s) => (
              <div key={s.label} className="bg-card px-6 py-8 sm:px-8 sm:py-10 text-center">
                <div className="text-3xl sm:text-4xl lg:text-5xl font-black tracking-tight tabular-nums">
                  {s.value}
                  <span className="text-primary">{s.suffix}</span>
                </div>
                <div className="mt-2 text-sm text-muted-foreground font-medium uppercase tracking-wider">
                  {s.label}
                </div>
              </div>
            ))}
          </div>
        </section>

        {/* ══════════════════════════════════════════════
         *  SUPPORTED PLATFORMS (client component - rAF marquees)
         * ══════════════════════════════════════════════ */}
        <section className="relative z-10 pt-28 lg:pt-36">
          <div className="reveal reveal-up text-center mb-12 px-6">
            <SectionLabel>Compatibility</SectionLabel>
            <h2 className="text-3xl sm:text-4xl font-bold tracking-tight">
              Runs <span className="text-gradient">everywhere</span>
            </h2>
            <p className="mt-3 text-lg text-muted-foreground max-w-xl mx-auto">
              From single-board computers to industrial gateways - one agent binary, every platform.
            </p>
          </div>
          <MarqueeSection />
        </section>

        {/* ══════════════════════════════════════════════
         *  FEATURES - 3 Cards
         * ══════════════════════════════════════════════ */}
        <section id="features" className="relative z-10 mx-auto max-w-7xl px-6 py-32 lg:py-40 sm:px-12 lg:px-16">
          <div className="reveal reveal-up text-center mb-20">
            <SectionLabel>Platform</SectionLabel>
            <h2 className="text-3xl sm:text-4xl lg:text-5xl font-bold tracking-tight">
              Everything you need for
              <span className="text-gradient"> edge operations</span>
            </h2>
            <p className="mt-4 text-lg text-muted-foreground max-w-2xl mx-auto">
              A complete platform for managing IoT devices at any scale - from prototypes to production fleets.
            </p>
          </div>

          <div className="grid gap-6 lg:grid-cols-3">
            {/* Fleet Dashboard */}
            <div className="reveal reveal-up group relative rounded-2xl border border-border bg-card shadow-sm shadow-black/[0.03] dark:shadow-none p-8 overflow-hidden transition-all duration-300 hover:border-primary/30 hover:shadow-lg hover:shadow-primary/5 hover:-translate-y-1">
              <div className="absolute top-0 left-0 right-0 h-px bg-gradient-to-r from-transparent via-primary/40 to-transparent opacity-0 group-hover:opacity-100 transition-opacity duration-300" />
              <SectionLabel>Fleet Dashboard</SectionLabel>
              <h3 className="text-2xl font-bold mb-3">Every device. One view.</h3>
              <p className="text-base text-muted-foreground leading-relaxed mb-8">
                Real-time visibility into your entire fleet. Health scores, resource
                metrics, connection status - filter and drill into any device instantly.
              </p>
              <div className="rounded-xl bg-slate-50 dark:bg-[#0c0c16] border border-border dark:border-white/10 p-4">
                <div className="grid grid-cols-3 gap-2 mb-3">
                  {[
                    { v: "1,847", l: "total", c: "text-foreground" },
                    { v: "1,823", l: "online", c: "text-emerald-600 dark:text-emerald-400" },
                    { v: "24", l: "attention", c: "text-amber-600 dark:text-amber-400" },
                  ].map((m) => (
                    <div key={m.l} className="rounded-lg bg-slate-50 dark:bg-white/[0.07] px-3 py-2 text-center">
                      <div className={`text-sm font-bold ${m.c}`}>{m.v}</div>
                      <div className="text-[11px] text-muted-foreground">{m.l}</div>
                    </div>
                  ))}
                </div>
                <div className="space-y-1.5">
                  {MOCK_DEVICES.map((d) => (
                    <div key={d.name} className="flex items-center gap-3 rounded-lg bg-slate-50 dark:bg-white/[0.05] px-3 py-2">
                      <div className={`h-2 w-2 rounded-full shrink-0 ${ST_DOT[d.status]}`} />
                      <span className="text-sm text-slate-600 dark:text-zinc-300 font-mono truncate">{d.name}</span>
                      <span className="ml-auto text-xs text-slate-400 dark:text-zinc-500 shrink-0 font-mono">{d.cpu}</span>
                    </div>
                  ))}
                </div>
              </div>
            </div>

            {/* OTA Updates */}
            <div id="updates" className="reveal reveal-up group relative rounded-2xl border border-border bg-card shadow-sm shadow-black/[0.03] dark:shadow-none p-8 overflow-hidden transition-all duration-300 hover:border-primary/30 hover:shadow-lg hover:shadow-primary/5 hover:-translate-y-1" style={{ transitionDelay: "100ms" }}>
              <div className="absolute top-0 left-0 right-0 h-px bg-gradient-to-r from-transparent via-primary/40 to-transparent opacity-0 group-hover:opacity-100 transition-opacity duration-300" />
              <SectionLabel>OTA Updates</SectionLabel>
              <h3 className="text-2xl font-bold mb-3">Ship fearlessly.</h3>
              <p className="text-base text-muted-foreground leading-relaxed mb-8">
                Rolling, canary, and immediate deployment strategies. Signed artifacts
                with integrity verification. Automatic rollback on failure.
              </p>
              <div className="rounded-xl bg-slate-50 dark:bg-[#0c0c16] border border-border dark:border-white/10 p-5">
                <div className="flex items-center justify-between mb-3">
                  <span className="text-sm text-foreground font-medium font-mono">firmware-v2.5.0</span>
                  <span className="text-xs text-primary font-semibold px-2.5 py-1 rounded-full bg-primary/10">Rolling</span>
                </div>
                <div className="h-2.5 bg-slate-200 dark:bg-white/10 rounded-full overflow-hidden mb-3">
                  <div className="h-full rounded-full bg-gradient-to-r from-primary/80 to-primary w-[78%] landing-progress" />
                </div>
                <div className="flex justify-between text-sm text-muted-foreground mb-4">
                  <span>78% complete</span>
                  <span>3 remaining</span>
                </div>
                <div className="flex gap-1">
                  {Array.from({ length: 16 }).map((_, i) => (
                    <div
                      key={i}
                      className={`h-2 flex-1 rounded-full transition-all duration-500 ${
                        i < 12
                          ? "bg-emerald-500/60"
                          : i < 13
                            ? "bg-primary/60 landing-pulse"
                            : "bg-slate-200 dark:bg-white/10"
                      }`}
                    />
                  ))}
                </div>
              </div>
            </div>

            {/* Zero-Trust Security */}
            <div id="security" className="reveal reveal-up group relative rounded-2xl border border-border bg-card shadow-sm shadow-black/[0.03] dark:shadow-none p-8 overflow-hidden transition-all duration-300 hover:border-primary/30 hover:shadow-lg hover:shadow-primary/5 hover:-translate-y-1" style={{ transitionDelay: "200ms" }}>
              <div className="absolute top-0 left-0 right-0 h-px bg-gradient-to-r from-transparent via-primary/40 to-transparent opacity-0 group-hover:opacity-100 transition-opacity duration-300" />
              <SectionLabel>Zero-Trust Security</SectionLabel>
              <h3 className="text-2xl font-bold mb-3">Secured from day one.</h3>
              <p className="text-base text-muted-foreground leading-relaxed mb-8">
                Mutual TLS on every connection. Encrypted VPN tunnels. Role-based
                access control with comprehensive audit trail.
              </p>
              <div className="flex items-center justify-center py-8">
                <div className="relative">
                  <div className="absolute -inset-16 rounded-full border border-primary/[0.06]" />
                  <div className="absolute -inset-11 rounded-full border border-primary/10" />
                  <div className="absolute -inset-16 rounded-full border border-primary/[0.08] landing-ring" />
                  <div className="absolute -inset-11 rounded-full border border-primary/[0.06] landing-ring" style={{ animationDelay: "1s" }} />
                  <div className="relative h-24 w-24 rounded-2xl bg-gradient-to-br from-primary/25 to-primary/[0.03] border border-primary/25 flex items-center justify-center shadow-lg shadow-primary/10">
                    <svg width="44" height="44" viewBox="0 0 24 24" fill="none">
                      <path
                        d="M12 2L4 6v5c0 5.55 3.84 10.74 8 12 4.16-1.26 8-6.45 8-12V6l-8-4z"
                        stroke="currentColor" className="text-primary" strokeWidth="1.5"
                        fill="currentColor" fillOpacity="0.1"
                      />
                      <path
                        d="M9 12l2 2 4-4"
                        stroke="currentColor" className="text-primary" strokeWidth="2"
                        strokeLinecap="round" strokeLinejoin="round"
                      />
                    </svg>
                  </div>
                </div>
              </div>
            </div>
          </div>
        </section>

        {/* ══════════════════════════════════════════════
         *  DEVICE AUTO-DISCOVERY (client component)
         * ══════════════════════════════════════════════ */}
        <section id="discovery" className="relative z-10 mx-auto max-w-7xl px-6 pt-28 lg:pt-36 pb-8 sm:px-12 lg:px-16">
          <div className="reveal reveal-up text-center mb-16">
            <SectionLabel>Auto-Discovery</SectionLabel>
            <h2 className="text-3xl sm:text-4xl lg:text-5xl font-bold tracking-tight">
              Devices find <span className="text-gradient">their way home</span>
            </h2>
            <p className="mt-4 text-lg text-muted-foreground max-w-2xl mx-auto">
              Zero-touch provisioning. Devices automatically discover, register, and sync
              with your controller - no manual configuration required.
            </p>
          </div>
          <DiscoverySection />
        </section>

        {/* ══════════════════════════════════════════════
         *  INTERACTIVE TERMINAL (client component)
         * ══════════════════════════════════════════════ */}
        <section id="terminal" className="relative z-10 mt-32 lg:mt-40">
          <div className="absolute inset-0 bg-slate-50 dark:bg-transparent" />
          <div className="absolute inset-0 dark:bg-card/60" />
          <div className="relative mx-auto max-w-7xl px-6 py-24 sm:px-12 lg:px-16 lg:py-32">
            <div className="reveal reveal-up text-center mb-16">
              <SectionLabel>Live Demo</SectionLabel>
              <h2 className="text-3xl sm:text-4xl lg:text-5xl font-bold tracking-tight">
                See it in action
              </h2>
              <p className="mt-4 text-lg text-muted-foreground max-w-2xl mx-auto">
                Connect to any device via web terminal or SSH. Deploy firmware updates.
                Monitor fleet health - all from a single interface.
              </p>
            </div>
            <TerminalSection />
          </div>
        </section>

        {/* ══════════════════════════════════════════════
         *  DECLARATIVE CONFIG + OBSERVABILITY
         * ══════════════════════════════════════════════ */}
        <section id="deep-dive" className="relative z-10 mx-auto max-w-7xl px-6 py-32 lg:py-40 sm:px-12 lg:px-16">
          <div className="reveal reveal-up text-center mb-20">
            <SectionLabel>Under the Hood</SectionLabel>
            <h2 className="text-3xl sm:text-4xl lg:text-5xl font-bold tracking-tight">
              Declarative control.
              <span className="text-gradient"> Total visibility.</span>
            </h2>
            <p className="mt-4 text-lg text-muted-foreground max-w-2xl mx-auto">
              Define desired state in YAML. Monitor everything with centralized logs and real-time metrics.
            </p>
          </div>

          <div className="grid lg:grid-cols-2 gap-8">
            {/* Declarative Config */}
            <div className="reveal reveal-left">
              <div className="h-full rounded-2xl border border-border bg-card shadow-sm shadow-black/[0.03] dark:shadow-none p-8 lg:p-10">
                <SectionLabel>Declarative Config</SectionLabel>
                <h3 className="text-2xl font-bold mb-3">Define once. Converge always.</h3>
                <p className="text-base text-muted-foreground leading-relaxed mb-8">
                  Push YAML manifests describing desired device state. The agent
                  reconciles automatically - services, files, network config.
                </p>
                <div className="rounded-xl bg-slate-50 dark:bg-[#0c0c16] border border-border dark:border-white/10 p-5 font-mono text-sm leading-relaxed overflow-hidden">
                  <div><span className="text-violet-600 dark:text-violet-400">kind</span><span className="text-slate-400 dark:text-zinc-600">:</span> <span className="text-emerald-600 dark:text-emerald-400">DeviceManifest</span></div>
                  <div><span className="text-violet-600 dark:text-violet-400">spec</span><span className="text-slate-400 dark:text-zinc-600">:</span></div>
                  <div className="pl-4"><span className="text-violet-600 dark:text-violet-400">services</span><span className="text-slate-400 dark:text-zinc-600">:</span></div>
                  <div className="pl-6"><span className="text-slate-400 dark:text-zinc-600">- </span><span className="text-slate-500 dark:text-zinc-400">name:</span> <span className="text-cyan-700 dark:text-cyan-300">sensor-agent</span></div>
                  <div className="pl-8"><span className="text-slate-500 dark:text-zinc-400">state:</span> <span className="text-emerald-600 dark:text-emerald-400">running</span></div>
                  <div className="pl-8"><span className="text-slate-500 dark:text-zinc-400">restart:</span> <span className="text-amber-600 dark:text-amber-300">on-failure</span></div>
                  <div className="pl-6"><span className="text-slate-400 dark:text-zinc-600">- </span><span className="text-slate-500 dark:text-zinc-400">name:</span> <span className="text-cyan-700 dark:text-cyan-300">data-relay</span></div>
                  <div className="pl-8"><span className="text-slate-500 dark:text-zinc-400">state:</span> <span className="text-emerald-600 dark:text-emerald-400">running</span></div>
                  <div className="pl-4"><span className="text-violet-600 dark:text-violet-400">files</span><span className="text-slate-400 dark:text-zinc-600">:</span></div>
                  <div className="pl-6"><span className="text-slate-400 dark:text-zinc-600">- </span><span className="text-slate-500 dark:text-zinc-400">path:</span> <span className="text-cyan-700 dark:text-cyan-300">/etc/edge/config.yaml</span></div>
                  <div className="pl-8"><span className="text-slate-500 dark:text-zinc-400">mode:</span> <span className="text-amber-600 dark:text-amber-300">0644</span></div>
                </div>
              </div>
            </div>

            {/* Observability */}
            <div className="reveal reveal-right">
              <div className="h-full rounded-2xl border border-border bg-card shadow-sm shadow-black/[0.03] dark:shadow-none p-8 lg:p-10">
                <SectionLabel>Observability</SectionLabel>
                <h3 className="text-2xl font-bold mb-3">Logs, metrics, insights.</h3>
                <p className="text-base text-muted-foreground leading-relaxed mb-8">
                  Centralized log aggregation, health dashboards, and real-time
                  alerting. Everything you need to understand your fleet.
                </p>
                <div className="rounded-xl bg-slate-50 dark:bg-[#0c0c16] border border-border dark:border-white/10 p-5 font-mono overflow-hidden">
                  <div className="flex items-center gap-2 mb-4">
                    <div className="h-2 w-2 rounded-full bg-emerald-400 landing-pulse" />
                    <span className="text-xs text-slate-500 dark:text-muted-foreground uppercase tracking-wider font-semibold">
                      Live Log Stream
                    </span>
                  </div>
                  <div className="space-y-2">
                    {LOG_ENTRIES.map((e, i) => (
                      <div key={i} className="flex items-start gap-3 text-sm leading-relaxed">
                        <span className="text-slate-400 dark:text-muted-foreground/40 shrink-0 tabular-nums">{e.time}</span>
                        <span className={`shrink-0 uppercase font-semibold tracking-wide text-xs mt-0.5 ${
                          e.level === "warn"
                            ? "text-amber-600 dark:text-amber-400"
                            : "text-emerald-600 dark:text-emerald-400/70"
                        }`}>
                          {e.level}
                        </span>
                        <span className="text-slate-600 dark:text-muted-foreground">{e.msg}</span>
                      </div>
                    ))}
                  </div>
                </div>
              </div>
            </div>
          </div>
        </section>

        {/* ══════════════════════════════════════════════
         *  CTA
         * ══════════════════════════════════════════════ */}
        <section className="relative z-10 mx-auto max-w-5xl px-6 pb-32 sm:px-12">
          <div className="reveal reveal-scale relative overflow-hidden rounded-3xl border border-border bg-card shadow-sm shadow-black/[0.03] dark:shadow-none">
            <div className="absolute inset-0 bg-gradient-to-br from-primary/[0.06] via-transparent to-violet-500/[0.04] dark:from-primary/[0.08] dark:to-violet-500/[0.05]" />
            <div className="absolute top-0 left-1/2 h-px w-2/3 -translate-x-1/2 bg-gradient-to-r from-transparent via-primary/50 to-transparent" />
            <div className="absolute bottom-0 left-1/2 h-px w-1/3 -translate-x-1/2 bg-gradient-to-r from-transparent via-primary/20 to-transparent" />

            <div className="relative z-10 flex flex-col items-center py-20 lg:py-28 px-8 text-center">
              <LogoIcon size={56} />
              <h2 className="mt-8 text-3xl sm:text-4xl lg:text-5xl font-bold tracking-tight">
                Ready to take control?
              </h2>
              <p className="mt-5 max-w-md text-lg text-muted-foreground">
                Sign in to your fleet dashboard or create a new account to get
                started with EdgeGuardian.
              </p>
              <SignInButton
                size="lg"
                className="mt-10 px-12 h-13 text-base font-bold shadow-lg shadow-primary/25 hover:shadow-xl hover:shadow-primary/30"
              >
                Get Started
              </SignInButton>
              <p className="mt-5 text-sm text-muted-foreground">
                Google &amp; GitHub SSO available
              </p>
            </div>
          </div>
        </section>

        {/* ══════════════════════════════════════════════
         *  FOOTER
         * ══════════════════════════════════════════════ */}
        <footer className="relative z-10 border-t border-border py-10 px-6 sm:px-12">
          <div className="reveal reveal-up mx-auto max-w-7xl">
            <div className="flex flex-col items-center justify-between gap-6 sm:flex-row">
              <div className="flex items-center gap-3">
                <LogoIcon size={24} />
                <span className="text-base font-semibold text-muted-foreground">EdgeGuardian</span>
              </div>
              <div className="flex items-center gap-6 text-sm text-muted-foreground">
                <a href="/terms" className="hover:text-foreground transition-colors">Terms &amp; Legal</a>
                <a href="#features" className="hover:text-foreground transition-colors">Features</a>
                <a href="#terminal" className="hover:text-foreground transition-colors">Demo</a>
              </div>
            </div>
            <div className="mt-6 pt-6 border-t border-border/50 flex flex-col items-center gap-3 sm:flex-row sm:justify-between">
              <p className="text-xs text-muted-foreground">
                &copy; {new Date().getFullYear()} EdgeGuardian. All rights reserved.
              </p>
              <p className="text-xs text-muted-foreground/60 text-center sm:text-right leading-relaxed">
                Currently in beta. Use at your own risk. No warranties expressed or implied. <a href="/terms" className="underline underline-offset-2 hover:text-muted-foreground transition-colors">Full terms</a>.
              </p>
            </div>
          </div>
        </footer>
      </div>
    </ScrollReveal>
  );
}

/* ═══════════════════════════════════════════════════════════
 *  Static sub-components (server-rendered, zero JS)
 * ═══════════════════════════════════════════════════════════ */

function SectionLabel({ children }: { children: React.ReactNode }) {
  return (
    <div className="text-sm text-primary font-semibold uppercase tracking-widest mb-4">
      {children}
    </div>
  );
}

function DashboardMockup() {
  return (
    <div className="relative">
      <div className="absolute inset-0 -m-12 rounded-3xl bg-primary/[0.05] dark:bg-cyan-500/[0.07] blur-[100px]" />
      <div className="relative" style={{ perspective: "1200px" }}>
        <div
          className="rounded-2xl border border-slate-200 dark:border-white/10 bg-white dark:bg-[#0c0c16] overflow-hidden shadow-2xl shadow-black/10 dark:shadow-black/50"
          style={{ animation: "floatDashboard 6s ease-in-out infinite" }}
        >
          <div className="flex items-center gap-2 px-5 py-3 border-b border-slate-200 dark:border-white/[0.08] bg-slate-50 dark:bg-white/[0.04]">
            <div className="h-3 w-3 rounded-full bg-red-400/80" />
            <div className="h-3 w-3 rounded-full bg-amber-400/80" />
            <div className="h-3 w-3 rounded-full bg-emerald-400/80" />
            <span className="ml-4 text-xs text-muted-foreground dark:text-zinc-500 tracking-wide">EdgeGuardian Dashboard</span>
          </div>
          <div className="flex">
            <div className="w-12 sm:w-14 shrink-0 border-r border-slate-200 dark:border-white/[0.08] py-4 flex flex-col items-center gap-3">
              <div className="w-6 h-6 rounded-lg bg-primary/20 flex items-center justify-center">
                <div className="w-3 h-3 rounded bg-primary/50" />
              </div>
              {[1, 2, 3, 4, 5].map((i) => (
                <div key={i} className={`w-6 h-1 rounded-full ${i === 2 ? "bg-primary/30" : "bg-slate-200 dark:bg-white/10"}`} />
              ))}
            </div>
            <div className="flex-1 p-4 sm:p-5 min-w-0">
              <div className="grid grid-cols-3 gap-2.5 mb-4">
                {[
                  { label: "Devices", val: "1,847" },
                  { label: "Online", val: "1,823" },
                  { label: "Deploying", val: "12" },
                ].map((m) => (
                  <div key={m.label} className="rounded-xl bg-slate-50 dark:bg-white/[0.06] border border-slate-200 dark:border-white/[0.08] p-3">
                    <div className="text-[10px] text-slate-500 dark:text-zinc-500 uppercase tracking-wider font-medium">{m.label}</div>
                    <div className="text-base sm:text-lg font-bold mt-1 text-primary">{m.val}</div>
                  </div>
                ))}
              </div>
              <div className="rounded-xl bg-slate-50 dark:bg-white/[0.06] border border-slate-200 dark:border-white/[0.08] p-3 sm:p-4 mb-4">
                <div className="text-[10px] text-slate-500 dark:text-zinc-500 uppercase tracking-wider mb-3 font-medium">Fleet CPU &mdash; 24h</div>
                <div className="flex items-end gap-[3px] h-14 sm:h-16">
                  {CHART_BARS.map((h, i) => (
                    <div
                      key={i}
                      className="flex-1 rounded-t landing-bar"
                      style={{
                        height: `${h}%`,
                        background: h > 80
                          ? "linear-gradient(to top, rgba(251,191,36,0.6), rgba(251,191,36,0.15))"
                          : "linear-gradient(to top, rgba(6,182,212,0.6), rgba(6,182,212,0.1))",
                        animationDelay: `${800 + i * 50}ms`,
                      }}
                    />
                  ))}
                </div>
              </div>
              <div className="space-y-1.5">
                {MOCK_DEVICES.slice(0, 4).map((d) => (
                  <div key={d.name} className="flex items-center gap-2.5 rounded-lg bg-slate-50 dark:bg-white/[0.05] px-3 py-2">
                    <div className={`h-2 w-2 rounded-full shrink-0 ${ST_DOT[d.status]}`} />
                    <span className="text-xs text-slate-600 dark:text-zinc-400 font-mono truncate">{d.name}</span>
                    <span className="ml-auto text-[11px] text-slate-400 dark:text-zinc-600 shrink-0 font-mono">{d.cpu}</span>
                  </div>
                ))}
              </div>
            </div>
          </div>
        </div>
      </div>
    </div>
  );
}
