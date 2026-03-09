"use client";

import { useState, useEffect } from "react";
import { signIn } from "next-auth/react";
import { useTheme } from "next-themes";
import { Sun, Moon } from "lucide-react";
import { Button } from "@/components/ui/button";
import { LogoIcon } from "@/components/logo";

export function LandingNavbar() {
  const [scrolled, setScrolled] = useState(false);
  const { setTheme, resolvedTheme } = useTheme();
  const [mounted, setMounted] = useState(false);

  useEffect(() => {
    setMounted(true);
    const handler = () => setScrolled(window.scrollY > 20);
    handler();
    window.addEventListener("scroll", handler, { passive: true });
    return () => window.removeEventListener("scroll", handler);
  }, []);

  return (
    <nav
      className={`fixed top-0 z-50 w-full transition-all duration-500 ${
        scrolled
          ? "backdrop-blur-xl bg-background/80 border-b border-border/50 shadow-sm"
          : "bg-transparent border-b border-transparent"
      }`}
    >
      <div className="mx-auto grid max-w-7xl grid-cols-3 items-center px-6 py-3.5 sm:px-12 lg:px-16">
        <div className="hidden md:flex items-center gap-8 text-sm font-medium text-muted-foreground">
          {[
            { label: "Features", id: "features" },
            { label: "Discovery", id: "discovery" },
            { label: "Demo", id: "terminal" },
          ].map((link) => (
            <a
              key={link.id}
              href={`#${link.id}`}
              className="cursor-pointer hover:text-foreground transition-colors duration-200"
            >
              {link.label}
            </a>
          ))}
        </div>
        <div className="md:hidden" />

        <a
          href="#"
          onClick={(e) => {
            e.preventDefault();
            window.scrollTo({ top: 0, behavior: "smooth" });
          }}
          className="flex items-center justify-center gap-3 cursor-pointer"
        >
          <LogoIcon size={44} outlined className="-my-2" />
          <span className="text-xl font-bold tracking-tight">
            Edge<span className="text-primary">Guardian</span>
          </span>
        </a>

        <div className="flex items-center justify-end gap-3">
          {mounted && (
            <button
              onClick={() =>
                setTheme(resolvedTheme === "dark" ? "light" : "dark")
              }
              className="cursor-pointer rounded-xl p-2.5 text-muted-foreground hover:text-foreground hover:bg-muted/50 transition-all duration-200"
              aria-label="Toggle theme"
            >
              {resolvedTheme === "dark" ? (
                <Sun size={20} />
              ) : (
                <Moon size={20} />
              )}
            </button>
          )}
          <Button
            size="default"
            className="px-6"
            onClick={() =>
              signIn("keycloak", { callbackUrl: "/dashboard" })
            }
          >
            Get Started
          </Button>
        </div>
      </div>
    </nav>
  );
}
