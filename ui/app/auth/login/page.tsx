"use client";

import { signIn } from "next-auth/react";
import { Button } from "@/components/ui/button";
import {
  Card,
  CardContent,
  CardDescription,
  CardHeader,
  CardTitle,
} from "@/components/ui/card";
import { Shield } from "lucide-react";

export default function LoginPage() {
  return (
    <div className="flex min-h-screen items-center justify-center bg-muted">
      <Card className="w-full max-w-md">
        <CardHeader className="text-center">
          <div className="mx-auto mb-4 flex h-12 w-12 items-center justify-center rounded-full bg-primary">
            <Shield className="h-6 w-6 text-primary-foreground" />
          </div>
          <CardTitle className="text-2xl">EdgeGuardian</CardTitle>
          <CardDescription>
            Sign in to manage your IoT fleet
          </CardDescription>
        </CardHeader>
        <CardContent className="space-y-3">
          <Button
            className="w-full"
            onClick={() => signIn("keycloak", { callbackUrl: "/" })}
          >
            Sign in with Keycloak
          </Button>
          <p className="text-center text-xs text-muted-foreground">
            Google and GitHub login available via Keycloak
          </p>
        </CardContent>
      </Card>
    </div>
  );
}
