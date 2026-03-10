"use client";

import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { SessionProvider } from "next-auth/react";
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
      <SessionProvider>
        <QueryClientProvider client={queryClient}>
          <TooltipProvider>
            <OrganizationProvider>
              {children}
              <ThemeCookieSync />
              <Toaster />
            </OrganizationProvider>
          </TooltipProvider>
        </QueryClientProvider>
      </SessionProvider>
    </ThemeProvider>
  );
}
