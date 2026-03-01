"use client";

import { signIn } from "next-auth/react";
import { Button } from "@/components/ui/button";
import {
  Card,
  CardContent,
  CardDescription,
  CardHeader,
  CardTitle,
} from "@/components/ui/card";
import { Cpu, Upload, Lock, Zap } from "lucide-react";
import { LogoIcon } from "@/components/logo";

export default function LoginPage() {
  return (
    <div className="flex min-h-screen">
      {/* Left branding panel */}
      <div className="hidden lg:flex lg:w-1/2 flex-col justify-between relative overflow-hidden bg-[#07070a] p-12 text-white">
        {/* Ambient glow effects */}
        <div className="absolute -top-40 -left-40 h-80 w-80 rounded-full bg-primary/10 blur-[120px]" />
        <div className="absolute bottom-0 right-0 h-60 w-60 rounded-full bg-violet-500/8 blur-[100px]" />
        <div className="absolute top-1/2 left-1/3 h-40 w-40 rounded-full bg-cyan-500/5 blur-[80px]" />

        {/* Grid overlay */}
        <div
          className="absolute inset-0 opacity-[0.03]"
          style={{
            backgroundImage: "linear-gradient(rgba(255,255,255,.1) 1px, transparent 1px), linear-gradient(90deg, rgba(255,255,255,.1) 1px, transparent 1px)",
            backgroundSize: "60px 60px",
          }}
        />

        <div className="relative z-10">
          <div className="flex items-center gap-3">
            <LogoIcon size={40} />
            <span className="text-xl font-bold tracking-tight">
              Edge<span className="text-primary">Guardian</span>
            </span>
          </div>
        </div>

        <div className="relative z-10 space-y-8">
          <div className="space-y-4">
            <h2 className="text-4xl font-bold leading-tight tracking-tight">
              IoT Fleet Management,
              <br />
              <span className="text-gradient">Container-Free.</span>
            </h2>
            <p className="text-lg text-zinc-400 max-w-md leading-relaxed">
              Deploy, monitor, and manage your edge devices at scale with a
              lightweight, Kubernetes-inspired approach.
            </p>
          </div>

          <div className="space-y-3 stagger-children">
            {[
              { icon: Cpu, text: "Manage thousands of edge devices from a single dashboard", accent: "text-cyan-400" },
              { icon: Upload, text: "Over-the-air updates with rolling and canary strategies", accent: "text-emerald-400" },
              { icon: Lock, text: "Zero-trust security with mTLS and WireGuard VPN", accent: "text-violet-400" },
              { icon: Zap, text: "Sub-5MB agent binary — no containers required", accent: "text-amber-400" },
            ].map(({ icon: Icon, text, accent }) => (
              <div key={text} className="flex items-center gap-4 rounded-xl border border-white/5 bg-white/[0.02] backdrop-blur-sm px-4 py-3">
                <div className="flex h-9 w-9 shrink-0 items-center justify-center rounded-lg bg-white/5">
                  <Icon className={`h-4 w-4 ${accent}`} />
                </div>
                <span className="text-sm text-zinc-300">{text}</span>
              </div>
            ))}
          </div>
        </div>

        <p className="relative z-10 text-xs text-zinc-600">
          &copy; {new Date().getFullYear()} EdgeGuardian
        </p>
      </div>

      {/* Right login panel */}
      <div className="flex flex-1 items-center justify-center bg-background p-6 relative">
        {/* Subtle background glow */}
        <div className="absolute top-1/4 right-1/4 h-60 w-60 rounded-full bg-primary/5 blur-[100px]" />

        <Card className="relative w-full max-w-md border-border/50">
          {/* Top accent line */}
          <div className="absolute inset-x-0 top-0 h-px bg-gradient-to-r from-transparent via-primary/40 to-transparent" />

          <CardHeader className="text-center space-y-4 pb-2">
            <div className="mx-auto">
              <LogoIcon size={56} />
            </div>
            <div className="space-y-1">
              <CardTitle className="text-2xl font-bold tracking-tight">Welcome back</CardTitle>
              <CardDescription className="text-base">
                Sign in to your EdgeGuardian account
              </CardDescription>
            </div>
          </CardHeader>
          <CardContent className="space-y-4 pt-4">
            <Button
              className="w-full"
              size="lg"
              onClick={() => signIn("keycloak", { callbackUrl: "/" })}
            >
              Sign in with Keycloak
            </Button>
            <p className="text-center text-xs text-muted-foreground">
              Google and GitHub login available via Keycloak identity broker
            </p>
          </CardContent>
        </Card>
      </div>
    </div>
  );
}
