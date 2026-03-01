"use client";

import { useOrganization } from "@/lib/hooks/use-organization";
import { Building2 } from "lucide-react";
import { Badge } from "@/components/ui/badge";
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from "@/components/ui/select";

export function OrgSwitcher() {
  const { me, currentOrg, orgId, setOrgId } = useOrganization();

  if (!me || me.organizations.length === 0) {
    return null;
  }

  return (
    <Select
      value={orgId ? String(orgId) : undefined}
      onValueChange={(val) => setOrgId(Number(val))}
    >
      <SelectTrigger className="w-full">
        <div className="flex items-center gap-2 truncate">
          <Building2 className="h-4 w-4 shrink-0" />
          <SelectValue placeholder="Select organization" />
        </div>
      </SelectTrigger>
      <SelectContent>
        {me.organizations.map((org) => (
          <SelectItem key={org.id} value={String(org.id)}>
            <div className="flex items-center gap-2">
              <span>{org.name}</span>
              <Badge variant="secondary" className="ml-auto text-[10px]">
                {org.role}
              </Badge>
            </div>
          </SelectItem>
        ))}
      </SelectContent>
    </Select>
  );
}
