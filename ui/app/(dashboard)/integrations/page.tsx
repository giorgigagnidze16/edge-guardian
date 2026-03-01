"use client";

import { useState } from "react";
import { useOrganization } from "@/lib/hooks/use-organization";
import { PageHeader } from "@/components/page-header";
import { Card, CardContent, CardHeader, CardTitle, CardDescription } from "@/components/ui/card";
import { Button } from "@/components/ui/button";
import { Badge } from "@/components/ui/badge";
import { Globe, Github, Radio, Terminal, Copy, Check } from "lucide-react";

const API_BASE = process.env.NEXT_PUBLIC_API_URL ?? "http://localhost:8443";

export default function IntegrationsPage() {
  const { orgId } = useOrganization();
  const [copiedId, setCopiedId] = useState<string | null>(null);

  const copyToClipboard = (text: string, id: string) => {
    navigator.clipboard.writeText(text);
    setCopiedId(id);
    setTimeout(() => setCopiedId(null), 2000);
  };

  const CopyButton = ({ text, id }: { text: string; id: string }) => (
    <Button
      variant="outline"
      size="sm"
      onClick={() => copyToClipboard(text, id)}
    >
      {copiedId === id ? (
        <Check className="mr-1 h-3 w-3" />
      ) : (
        <Copy className="mr-1 h-3 w-3" />
      )}
      {copiedId === id ? "Copied!" : "Copy"}
    </Button>
  );

  const curlExample = `curl -H "Authorization: Bearer YOUR_API_KEY" \\
  ${API_BASE}/api/v1/devices`;

  const githubActionsYaml = `name: OTA Deploy
on:
  push:
    tags: ['v*']

jobs:
  deploy:
    runs-on: ubuntu-latest
    steps:
      - name: Create OTA Artifact
        run: |
          curl -X POST ${API_BASE}/api/v1/organizations/${orgId ?? 1}/ota/artifacts \\
            -H "Authorization: Bearer \${{ secrets.EDGEGUARDIAN_API_KEY }}" \\
            -H "Content-Type: application/json" \\
            -d '{"name":"my-app","version":"\${{ github.ref_name }}","architecture":"arm64"}'`;

  const enrollCommand = `curl -sSL https://get.edgeguardian.io | sh -s -- --token YOUR_ENROLLMENT_TOKEN --api ${API_BASE}`;

  return (
    <div className="space-y-6">
      <PageHeader
        title="Integrations"
        description="Connect EdgeGuardian with your tools and workflows"
      />

      <div className="grid gap-4 md:grid-cols-2">
        {/* REST API */}
        <Card>
          <CardHeader>
            <div className="flex items-center gap-2">
              <Globe className="h-5 w-5 text-primary" />
              <CardTitle>REST API</CardTitle>
            </div>
            <CardDescription>
              Access fleet data programmatically
            </CardDescription>
          </CardHeader>
          <CardContent className="space-y-3">
            <div>
              <p className="mb-1 text-xs font-medium text-muted-foreground">Base URL</p>
              <div className="flex items-center gap-2">
                <code className="rounded-md bg-muted/50 border border-border/50 px-2 py-1 text-sm font-mono text-primary">
                  {API_BASE}
                </code>
                <CopyButton text={API_BASE} id="api-url" />
              </div>
            </div>
            <div>
              <p className="mb-1 text-xs font-medium text-muted-foreground">Example</p>
              <pre className="rounded-lg bg-muted/50 border border-border/50 p-3 text-xs font-mono overflow-x-auto">
                {curlExample}
              </pre>
              <div className="mt-2">
                <CopyButton text={curlExample} id="curl" />
              </div>
            </div>
          </CardContent>
        </Card>

        {/* CI/CD */}
        <Card>
          <CardHeader>
            <div className="flex items-center gap-2">
              <Github className="h-5 w-5" />
              <CardTitle>CI/CD</CardTitle>
              <Badge variant="secondary">GitHub Actions</Badge>
            </div>
            <CardDescription>
              Automate OTA deployments from your CI pipeline
            </CardDescription>
          </CardHeader>
          <CardContent className="space-y-3">
            <pre className="max-h-64 overflow-auto rounded-lg bg-muted/50 border border-border/50 p-3 text-xs font-mono">
              {githubActionsYaml}
            </pre>
            <CopyButton text={githubActionsYaml} id="github-actions" />
          </CardContent>
        </Card>

        {/* MQTT */}
        <Card>
          <CardHeader>
            <div className="flex items-center gap-2">
              <Radio className="h-5 w-5 text-emerald-500" />
              <CardTitle>MQTT</CardTitle>
            </div>
            <CardDescription>
              Real-time device telemetry and commands
            </CardDescription>
          </CardHeader>
          <CardContent className="space-y-3">
            <div>
              <p className="mb-1 text-xs font-medium text-muted-foreground">Broker URL</p>
              <code className="rounded-md bg-muted/50 border border-border/50 px-2 py-1 text-sm font-mono text-primary">
                mqtt://localhost:1883
              </code>
            </div>
            <div>
              <p className="mb-1 text-xs font-medium text-muted-foreground">Topic Patterns</p>
              <div className="space-y-1">
                <code className="block rounded-md bg-muted/50 border border-border/50 px-2 py-1 text-xs font-mono text-primary">
                  edgeguardian/devices/+/heartbeat
                </code>
                <code className="block rounded-md bg-muted/50 border border-border/50 px-2 py-1 text-xs font-mono text-primary">
                  edgeguardian/devices/+/telemetry
                </code>
                <code className="block rounded-md bg-muted/50 border border-border/50 px-2 py-1 text-xs font-mono text-primary">
                  edgeguardian/devices/+/commands
                </code>
              </div>
            </div>
          </CardContent>
        </Card>

        {/* Device Enrollment */}
        <Card>
          <CardHeader>
            <div className="flex items-center gap-2">
              <Terminal className="h-5 w-5 text-amber-500" />
              <CardTitle>Device Enrollment</CardTitle>
            </div>
            <CardDescription>
              Quick-start install command for new devices
            </CardDescription>
          </CardHeader>
          <CardContent className="space-y-3">
            <pre className="rounded-lg bg-muted/50 border border-border/50 p-3 text-xs font-mono overflow-x-auto">
              {enrollCommand}
            </pre>
            <CopyButton text={enrollCommand} id="enroll" />
            <p className="text-xs text-muted-foreground">
              Replace YOUR_ENROLLMENT_TOKEN with a token from Settings &gt; Tokens.
            </p>
          </CardContent>
        </Card>
      </div>
    </div>
  );
}
