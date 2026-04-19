"use client";

import Link from "next/link";
import { usePathname } from "next/navigation";
import {
  LayoutDashboard,
  Cpu,
  Upload,
  ShieldCheck,
  Settings,
  FileText,
  Plug,
  PanelLeftClose,
  PanelLeftOpen,
  Moon,
  Sun,
} from "lucide-react";
import { cn } from "@/lib/utils";
import { LogoIcon } from "@/components/logo";
import { useState, useEffect, lazy, Suspense } from "react";
import { useTheme } from "next-themes";

// Lazy-load: defers @radix-ui/react-select from initial compile
const OrgSwitcher = lazy(() =>
  import("@/components/org-switcher").then((m) => ({ default: m.OrgSwitcher })),
);
import { Switch } from "@/components/ui/switch";
import { Tooltip, TooltipContent, TooltipTrigger } from "@/components/ui/tooltip";
import { Button } from "@/components/ui/button";
import { Avatar, AvatarFallback } from "@/components/ui/avatar";
import { useSession } from "next-auth/react";

const navigation = [
  { name: "Dashboard", href: "/dashboard", icon: LayoutDashboard },
  { name: "Devices", href: "/devices", icon: Cpu },
  { name: "OTA Updates", href: "/ota", icon: Upload },
  { name: "Certificates", href: "/certificates", icon: ShieldCheck },
  { name: "Integrations", href: "/integrations", icon: Plug },
  { name: "Audit Log", href: "/audit", icon: FileText },
  { name: "Settings", href: "/settings", icon: Settings },
];

export function Sidebar() {
  const pathname = usePathname();
  const { theme, setTheme } = useTheme();
  const { data: session } = useSession();
  const [collapsed, setCollapsed] = useState(false);
  const [mounted, setMounted] = useState(false);

  useEffect(() => {
    setMounted(true);
    const stored = localStorage.getItem("sidebar-collapsed");
    if (stored === "true") setCollapsed(true);
  }, []);

  const toggleCollapsed = () => {
    const next = !collapsed;
    setCollapsed(next);
    localStorage.setItem("sidebar-collapsed", String(next));
  };

  const initials =
    session?.user?.name
      ?.split(" ")
      .map((n) => n[0])
      .join("")
      .slice(0, 2)
      .toUpperCase() ?? "?";

  return (
    <div
      className={cn(
        "group/sidebar relative z-10 flex h-full flex-col border-r border-sidebar-border bg-sidebar-background/50 backdrop-blur-xl transition-all duration-300 ease-out overflow-visible",
        collapsed ? "w-16" : "w-64",
      )}
    >
      {/* Expand handle - appears on hover of collapsed rail (Linear pattern) */}
      {collapsed && (
        <Tooltip>
          <TooltipTrigger asChild>
            <button
              onClick={toggleCollapsed}
              className="cursor-pointer absolute -right-4 top-8 z-50 flex h-8 w-8 -translate-y-1/2 items-center justify-center rounded-full border border-sidebar-border bg-sidebar-background text-muted-foreground shadow-md opacity-0 transition-opacity duration-200 hover:bg-accent hover:text-foreground group-hover/sidebar:opacity-100"
            >
              <PanelLeftOpen className="h-4 w-4" />
            </button>
          </TooltipTrigger>
          <TooltipContent side="right">Expand sidebar</TooltipContent>
        </Tooltip>
      )}

      {/* Logo */}
      <div className={cn(
        "flex h-16 items-center border-b border-sidebar-border",
        collapsed ? "justify-center" : "px-4",
      )}>
        {collapsed ? (
          <Tooltip>
            <TooltipTrigger asChild>
              <Link href="/dashboard" className="hover:opacity-80 transition-opacity">
                <LogoIcon size={28} />
              </Link>
            </TooltipTrigger>
            <TooltipContent side="right">Dashboard</TooltipContent>
          </Tooltip>
        ) : (
          <>
            <Link href="/dashboard" className="flex items-center gap-3 hover:opacity-80 transition-opacity">
              <LogoIcon size={34} />
              <span className="text-lg font-bold tracking-tight text-foreground">
                Edge<span className="text-primary">Guardian</span>
              </span>
            </Link>
            <Button
              variant="ghost"
              size="icon"
              className="ml-auto h-7 w-7 text-muted-foreground hover:text-foreground"
              onClick={toggleCollapsed}
            >
              <PanelLeftClose className="h-4 w-4" />
            </Button>
          </>
        )}
      </div>

      {/* Org switcher */}
      {!collapsed && (
        <div className="border-b border-sidebar-border px-3 py-2">
          <Suspense fallback={<div className="h-9 rounded-md bg-muted/30 animate-pulse" />}>
            <OrgSwitcher />
          </Suspense>
        </div>
      )}

      {/* Navigation */}
      <nav className="flex-1 space-y-0.5 p-2">
        {navigation.map((item) => {
          const isActive =
            pathname === item.href ||
            pathname.startsWith(item.href + "/");

          const link = (
            <Link
              key={item.name}
              href={item.href}
              className={cn(
                "group relative flex items-center gap-3 rounded-lg px-3 py-2 text-sm font-medium transition-all duration-200",
                collapsed && "justify-center px-0",
                isActive
                  ? "bg-primary/10 text-primary"
                  : "text-muted-foreground hover:bg-accent hover:text-foreground",
              )}
            >
              {/* Active indicator bar */}
              {isActive && (
                <div className="absolute left-0 top-1/2 h-5 w-0.5 -translate-y-1/2 rounded-full bg-primary" />
              )}
              <item.icon className={cn("h-4 w-4 shrink-0 transition-colors", isActive && "text-primary")} />
              {!collapsed && item.name}
            </Link>
          );

          if (collapsed) {
            return (
              <Tooltip key={item.name}>
                <TooltipTrigger asChild>{link}</TooltipTrigger>
                <TooltipContent side="right">{item.name}</TooltipContent>
              </Tooltip>
            );
          }

          return link;
        })}
      </nav>

      {/* Bottom section */}
      <div className="border-t border-sidebar-border p-3">
        {!collapsed && mounted && (
          <div className="mb-3 flex items-center justify-between rounded-lg bg-muted/30 px-3 py-2">
            <div className="flex items-center gap-2 text-sm text-muted-foreground">
              {theme === "dark" ? (
                <Moon className="h-3.5 w-3.5" />
              ) : (
                <Sun className="h-3.5 w-3.5" />
              )}
              <span className="text-xs">Dark mode</span>
            </div>
            <Switch
              checked={theme === "dark"}
              onCheckedChange={(checked) =>
                setTheme(checked ? "dark" : "light")
              }
            />
          </div>
        )}

        {!collapsed && session?.user && (
          <div className="flex items-center gap-2.5 rounded-lg px-1">
            <Avatar className="h-8 w-8 border border-border">
              <AvatarFallback className="bg-primary/10 text-xs font-semibold text-primary">
                {initials}
              </AvatarFallback>
            </Avatar>
            <div className="min-w-0 flex-1">
              <p className="truncate text-sm font-medium text-foreground">
                {session.user.name}
              </p>
              <p className="truncate text-xs text-muted-foreground">
                {session.user.email}
              </p>
            </div>
          </div>
        )}

        {collapsed && mounted && (
          <Tooltip>
            <TooltipTrigger asChild>
              <button
                onClick={() => setTheme(theme === "dark" ? "light" : "dark")}
                className="flex w-full items-center justify-center rounded-lg py-2 text-muted-foreground hover:bg-accent hover:text-foreground transition-colors mb-2"
              >
                {theme === "dark" ? <Moon className="h-4 w-4" /> : <Sun className="h-4 w-4" />}
              </button>
            </TooltipTrigger>
            <TooltipContent side="right">Toggle theme</TooltipContent>
          </Tooltip>
        )}

        {collapsed && session?.user && (
          <Tooltip>
            <TooltipTrigger asChild>
              <div className="flex justify-center">
                <Avatar className="h-8 w-8 border border-border">
                  <AvatarFallback className="bg-primary/10 text-xs font-semibold text-primary">
                    {initials}
                  </AvatarFallback>
                </Avatar>
              </div>
            </TooltipTrigger>
            <TooltipContent side="right">{session.user.name}</TooltipContent>
          </Tooltip>
        )}
      </div>
    </div>
  );
}
