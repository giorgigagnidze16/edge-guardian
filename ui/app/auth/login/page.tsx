"use client";

import { useCallback, useEffect, useState } from "react";
import { signIn } from "next-auth/react";
import { useTheme } from "next-themes";
import { Sun, Moon, LogIn } from "lucide-react";
import { Button } from "@/components/ui/button";
import { LogoIcon } from "@/components/logo";

export default function LoginPage() {
  const { setTheme, resolvedTheme } = useTheme();
  const [mounted, setMounted] = useState(false);
  useEffect(() => setMounted(true), []);

  const doSignIn = useCallback(
    () => signIn("keycloak", { callbackUrl: "/dashboard" }),
    [],
  );

  return (
    <div className="relative flex min-h-screen items-center justify-center bg-background">
      {/* Theme toggle */}
      {mounted && (
        <button
          onClick={() => setTheme(resolvedTheme === "dark" ? "light" : "dark")}
          className="absolute right-6 top-6 cursor-pointer rounded-xl p-2.5 text-muted-foreground hover:text-foreground hover:bg-muted/50 transition-all duration-200"
          aria-label="Toggle theme"
        >
          {resolvedTheme === "dark" ? <Sun size={20} /> : <Moon size={20} />}
        </button>
      )}

      {/* Login card */}
      <div className="w-full max-w-sm px-6">
        <div className="flex flex-col items-center text-center">
          {/* Logo */}
          <div className="relative mb-6">
            <div
              className="absolute inset-0 rounded-full bg-primary/10 blur-2xl"
              aria-hidden
            />
            <LogoIcon size={56} className="relative" />
          </div>

          <h1 className="text-2xl font-bold tracking-tight text-foreground">
            Edge<span className="text-primary">Guardian</span>
          </h1>
          <p className="mt-2 text-sm text-muted-foreground">
            Sign in to manage your fleet
          </p>

          {/* Sign in button */}
          <Button
            onClick={doSignIn}
            size="lg"
            className="mt-8 w-full cursor-pointer"
          >
            <LogIn className="mr-2 h-4 w-4" />
            Sign in
          </Button>
        </div>
      </div>
    </div>
  );
}
