"use client";

import { signIn } from "next-auth/react";
import { Button } from "@/components/ui/button";

export function SignInButton(
  props: Omit<React.ComponentProps<typeof Button>, "onClick">,
) {
  return (
    <Button
      {...props}
      onClick={() => signIn("keycloak", { callbackUrl: "/dashboard" })}
    />
  );
}
