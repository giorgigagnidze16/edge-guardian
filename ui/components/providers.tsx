"use client";

import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { SessionProvider, useSession, signIn } from "next-auth/react";
import { ThemeProvider, useTheme } from "next-themes";
import { useState, useEffect } from "react";
import { Toaster } from "@/components/toaster";
import { OrganizationProvider } from "@/lib/hooks/use-organization";
import { TooltipProvider } from "@/components/ui/tooltip";

function ThemeCookieSync() {
  const { resolvedTheme } = useTheme();
  useEffect(() => {
    if (resolvedTheme) {
      document.cookie = `eg-theme=${resolvedTheme};path=/;max-age=31536000;SameSite=Lax`;
    }
  }, [resolvedTheme]);
  return null;
}

function RefreshErrorGuard() {
  const { data: session } = useSession();
  useEffect(() => {
    if ((session as { error?: string } | null)?.error === "RefreshTokenError") {
      signIn("keycloak");
    }
  }, [session]);
  return null;
}

export function Providers({ children }: { children: React.ReactNode }) {
  const [queryClient] = useState(
    () =>
      new QueryClient({
        defaultOptions: {
          queries: {
            staleTime: 30 * 1000,
            retry: 1,
          },
        },
      }),
  );

  return (
    <ThemeProvider attribute="class" defaultTheme="dark" enableSystem>
      <SessionProvider refetchInterval={4 * 60} refetchOnWindowFocus>
        <QueryClientProvider client={queryClient}>
          <TooltipProvider>
            <OrganizationProvider>
              {children}
              <RefreshErrorGuard />
              <ThemeCookieSync />
              <Toaster />
            </OrganizationProvider>
          </TooltipProvider>
        </QueryClientProvider>
      </SessionProvider>
    </ThemeProvider>
  );
}
