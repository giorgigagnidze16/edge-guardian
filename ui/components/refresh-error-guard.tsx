"use client";

import { useSession, signIn } from "next-auth/react";
import { useEffect } from "react";

export function RefreshErrorGuard() {
  const { data: session } = useSession();
  useEffect(() => {
    if ((session as { error?: string } | null)?.error === "RefreshTokenError") {
      signIn("keycloak");
    }
  }, [session]);
  return null;
}
