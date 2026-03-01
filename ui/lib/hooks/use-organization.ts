"use client";

import {
  createContext,
  useContext,
  useState,
  useEffect,
  useCallback,
  type ReactNode,
} from "react";
import { useSession } from "next-auth/react";
import { useQuery } from "@tanstack/react-query";
import { getMe, type MeResponse } from "@/lib/api/organizations";
import React from "react";

interface OrganizationContextValue {
  me: MeResponse | undefined;
  isLoading: boolean;
  currentOrg: MeResponse["organizations"][number] | undefined;
  orgId: number | undefined;
  setOrgId: (id: number) => void;
}

const OrganizationContext = createContext<OrganizationContextValue>({
  me: undefined,
  isLoading: true,
  currentOrg: undefined,
  orgId: undefined,
  setOrgId: () => {},
});

export function OrganizationProvider({ children }: { children: ReactNode }) {
  const { data: session } = useSession();
  const token = session?.accessToken;

  const { data: me, isLoading } = useQuery({
    queryKey: ["me"],
    queryFn: () => getMe(token!),
    enabled: !!token,
  });

  const [orgId, setOrgIdRaw] = useState<number | undefined>(undefined);

  useEffect(() => {
    if (me && !orgId) {
      const stored = typeof window !== "undefined"
        ? localStorage.getItem("edgeguardian-org-id")
        : null;
      const storedId = stored ? parseInt(stored, 10) : undefined;
      const found = me.organizations.find((o) => o.id === storedId);
      setOrgIdRaw(found ? found.id : me.organizations[0]?.id);
    }
  }, [me, orgId]);

  const setOrgId = useCallback((id: number) => {
    setOrgIdRaw(id);
    if (typeof window !== "undefined") {
      localStorage.setItem("edgeguardian-org-id", String(id));
    }
  }, []);

  const currentOrg = me?.organizations.find((o) => o.id === orgId);

  return React.createElement(
    OrganizationContext.Provider,
    { value: { me, isLoading, currentOrg, orgId, setOrgId } },
    children,
  );
}

export function useOrganization() {
  return useContext(OrganizationContext);
}
