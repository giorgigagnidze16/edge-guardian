"use client";

import { useEffect, useState, useCallback } from "react";
import { useRouter } from "next/navigation";
import { useTheme } from "next-themes";
import { useQuery } from "@tanstack/react-query";
import { useSession } from "next-auth/react";
import {
  LayoutDashboard,
  Cpu,
  Upload,
  Settings,
  FileText,
  Plug,
  Moon,
  Sun,
  Search,
} from "lucide-react";
import {
  CommandDialog,
  CommandEmpty,
  CommandGroup,
  CommandInput,
  CommandItem,
  CommandList,
  CommandSeparator,
} from "@/components/ui/command";
import { listDevices } from "@/lib/api/devices";

const pages = [
  { name: "Dashboard", href: "/", icon: LayoutDashboard },
  { name: "Devices", href: "/devices", icon: Cpu },
  { name: "OTA Updates", href: "/ota", icon: Upload },
  { name: "Integrations", href: "/integrations", icon: Plug },
  { name: "Audit Log", href: "/audit", icon: FileText },
  { name: "Settings", href: "/settings", icon: Settings },
];

export function CommandPalette() {
  const [open, setOpen] = useState(false);
  const router = useRouter();
  const { theme, setTheme } = useTheme();
  const { data: session } = useSession();

  const { data: devices } = useQuery({
    queryKey: ["devices"],
    queryFn: () => listDevices(session?.accessToken ?? ""),
    enabled: !!session?.accessToken,
  });

  useEffect(() => {
    const down = (e: KeyboardEvent) => {
      if (e.key === "k" && (e.metaKey || e.ctrlKey)) {
        e.preventDefault();
        setOpen((prev) => !prev);
      }
    };
    document.addEventListener("keydown", down);
    return () => document.removeEventListener("keydown", down);
  }, []);

  const runCommand = useCallback(
    (cmd: () => void) => {
      setOpen(false);
      cmd();
    },
    [],
  );

  return (
    <>
      <button
        onClick={() => setOpen(true)}
        className="inline-flex items-center gap-2 rounded-lg border border-border/50 bg-muted/30 px-3 py-1.5 text-sm text-muted-foreground backdrop-blur-sm hover:bg-accent hover:text-accent-foreground transition-all duration-200"
      >
        <Search className="h-3.5 w-3.5" />
        <span className="hidden sm:inline">Search...</span>
        <kbd className="pointer-events-none hidden h-5 select-none items-center gap-1 rounded-md border border-border/50 bg-muted/50 px-1.5 font-mono text-[10px] font-medium sm:flex">
          <span className="text-xs">Ctrl</span>K
        </kbd>
      </button>

      <CommandDialog open={open} onOpenChange={setOpen}>
        <CommandInput placeholder="Type a command or search..." />
        <CommandList>
          <CommandEmpty>No results found.</CommandEmpty>

          <CommandGroup heading="Pages">
            {pages.map((page) => (
              <CommandItem
                key={page.href}
                onSelect={() => runCommand(() => router.push(page.href))}
              >
                <page.icon className="mr-2 h-4 w-4 text-muted-foreground" />
                {page.name}
              </CommandItem>
            ))}
          </CommandGroup>

          {devices && devices.length > 0 && (
            <>
              <CommandSeparator />
              <CommandGroup heading="Devices">
                {devices.slice(0, 10).map((device) => (
                  <CommandItem
                    key={device.deviceId}
                    onSelect={() =>
                      runCommand(() =>
                        router.push(`/devices/${device.deviceId}`),
                      )
                    }
                  >
                    <Cpu className="mr-2 h-4 w-4 text-muted-foreground" />
                    {device.hostname}
                    <span className="ml-auto text-xs text-muted-foreground">
                      {device.state}
                    </span>
                  </CommandItem>
                ))}
              </CommandGroup>
            </>
          )}

          <CommandSeparator />
          <CommandGroup heading="Theme">
            <CommandItem
              onSelect={() =>
                runCommand(() =>
                  setTheme(theme === "dark" ? "light" : "dark"),
                )
              }
            >
              {theme === "dark" ? (
                <Sun className="mr-2 h-4 w-4 text-muted-foreground" />
              ) : (
                <Moon className="mr-2 h-4 w-4 text-muted-foreground" />
              )}
              Toggle {theme === "dark" ? "Light" : "Dark"} Mode
            </CommandItem>
          </CommandGroup>
        </CommandList>
      </CommandDialog>
    </>
  );
}
