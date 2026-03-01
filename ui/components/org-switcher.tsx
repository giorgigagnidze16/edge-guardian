"use client";

import { useState } from "react";
import { Building2, ChevronDown } from "lucide-react";
import { cn } from "@/lib/utils";

interface Org {
  id: number;
  name: string;
  slug: string;
  role: string;
}

interface OrgSwitcherProps {
  organizations: Org[];
  currentOrgId: number | null;
  onSelect: (orgId: number) => void;
}

export function OrgSwitcher({
  organizations,
  currentOrgId,
  onSelect,
}: OrgSwitcherProps) {
  const [open, setOpen] = useState(false);
  const current = organizations.find((o) => o.id === currentOrgId);

  return (
    <div className="relative">
      <button
        onClick={() => setOpen(!open)}
        className="flex w-full items-center gap-2 rounded-md border px-3 py-2 text-sm hover:bg-accent"
      >
        <Building2 className="h-4 w-4" />
        <span className="flex-1 truncate text-left">
          {current?.name ?? "Select organization"}
        </span>
        <ChevronDown className="h-4 w-4" />
      </button>

      {open && (
        <div className="absolute top-full z-50 mt-1 w-full rounded-md border bg-popover shadow-md">
          {organizations.map((org) => (
            <button
              key={org.id}
              onClick={() => {
                onSelect(org.id);
                setOpen(false);
              }}
              className={cn(
                "flex w-full items-center gap-2 px-3 py-2 text-sm hover:bg-accent",
                org.id === currentOrgId && "bg-accent",
              )}
            >
              <Building2 className="h-3 w-3" />
              <span className="flex-1 truncate text-left">{org.name}</span>
              <span className="text-xs text-muted-foreground">{org.role}</span>
            </button>
          ))}
        </div>
      )}
    </div>
  );
}
