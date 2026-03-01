"use client";

import { Sidebar } from "@/components/sidebar";
import { Breadcrumbs } from "@/components/breadcrumbs";
import { ErrorBoundary } from "@/components/error-boundary";
import { Button } from "@/components/ui/button";
import { Menu } from "lucide-react";
import { useState, lazy, Suspense } from "react";

// Lazy-load heavy interactive components out of the initial compile
const CommandPalette = lazy(() =>
  import("@/components/command-palette").then((m) => ({ default: m.CommandPalette })),
);
const UserMenu = lazy(() =>
  import("@/components/user-menu").then((m) => ({ default: m.UserMenu })),
);

// Lazy-load: radix-dialog (sheet) only needed on mobile
const Sheet = lazy(() => import("@/components/ui/sheet").then((m) => ({ default: m.Sheet })));
const SheetContent = lazy(() => import("@/components/ui/sheet").then((m) => ({ default: m.SheetContent })));
const SheetTrigger = lazy(() => import("@/components/ui/sheet").then((m) => ({ default: m.SheetTrigger })));
const SheetTitle = lazy(() => import("@/components/ui/sheet").then((m) => ({ default: m.SheetTitle })));

function MobileSidebar() {
  const [open, setOpen] = useState(false);
  return (
    <Suspense fallback={<Button variant="ghost" size="icon" className="md:hidden"><Menu className="h-5 w-5" /></Button>}>
      <Sheet open={open} onOpenChange={setOpen}>
        <SheetTrigger asChild>
          <Button variant="ghost" size="icon" className="md:hidden">
            <Menu className="h-5 w-5" />
          </Button>
        </SheetTrigger>
        <SheetContent side="left" className="w-64 p-0">
          <SheetTitle className="sr-only">Navigation</SheetTitle>
          <Sidebar />
        </SheetContent>
      </Sheet>
    </Suspense>
  );
}

export default function DashboardLayout({
  children,
}: {
  children: React.ReactNode;
}) {
  return (
    <div className="flex h-screen ambient-glow">
      {/* Desktop sidebar */}
      <div className="hidden md:flex">
        <Sidebar />
      </div>

      <div className="flex flex-1 flex-col overflow-hidden">
        {/* Header */}
        <header className="flex h-14 items-center gap-4 border-b border-border/50 bg-background/80 backdrop-blur-xl px-4 sm:px-6">
          <MobileSidebar />
          <Breadcrumbs />
          <div className="flex-1" />
          <Suspense>
            <CommandPalette />
          </Suspense>
          <Suspense>
            <UserMenu />
          </Suspense>
        </header>

        {/* Main content */}
        <main className="flex-1 overflow-y-auto p-4 sm:p-6">
          <ErrorBoundary>{children}</ErrorBoundary>
        </main>
      </div>
    </div>
  );
}