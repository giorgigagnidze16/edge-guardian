"use client";

import { useQuery, useMutation, useQueryClient } from "@tanstack/react-query";
import { useSession } from "next-auth/react";
import {
  listArtifacts,
  listDeployments,
  createDeployment,
  type OtaArtifact,
  type OtaDeployment,
} from "@/lib/api/ota";
import { DataTable, type Column } from "@/components/data-table";
import { StateBadge } from "@/components/state-badge";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import {
  Card,
  CardContent,
  CardHeader,
  CardTitle,
} from "@/components/ui/card";
import { Input } from "@/components/ui/input";
import { Upload, Rocket } from "lucide-react";
import { useState } from "react";
import { formatDistanceToNow } from "date-fns";

function formatBytes(bytes: number): string {
  if (bytes === 0) return "0 B";
  const k = 1024;
  const sizes = ["B", "KB", "MB", "GB"];
  const i = Math.floor(Math.log(bytes) / Math.log(k));
  return `${(bytes / Math.pow(k, i)).toFixed(1)} ${sizes[i]}`;
}

const artifactColumns: Column<OtaArtifact>[] = [
  { header: "Name", accessorKey: "name" },
  {
    header: "Version",
    cell: (row) => (
      <Badge variant="secondary" className="font-mono text-xs">
        {row.version}
      </Badge>
    ),
  },
  {
    header: "Arch",
    cell: (row) => (
      <Badge variant="outline" className="font-mono text-xs">
        {row.architecture}
      </Badge>
    ),
  },
  {
    header: "Size",
    cell: (row) => formatBytes(row.size),
  },
  {
    header: "Uploaded",
    cell: (row) =>
      formatDistanceToNow(new Date(row.createdAt), { addSuffix: true }),
  },
];

const deploymentColumns: Column<OtaDeployment>[] = [
  {
    header: "ID",
    cell: (row) => (
      <span className="font-mono text-xs">#{row.id}</span>
    ),
  },
  {
    header: "Artifact",
    cell: (row) => (
      <span className="font-mono text-xs">#{row.artifactId}</span>
    ),
  },
  {
    header: "Strategy",
    cell: (row) => (
      <Badge variant="outline">{row.strategy}</Badge>
    ),
  },
  {
    header: "State",
    cell: (row) => <StateBadge state={row.state} />,
  },
  {
    header: "Labels",
    cell: (row) => {
      const entries = Object.entries(row.labelSelector ?? {});
      if (entries.length === 0)
        return <span className="text-muted-foreground">all</span>;
      return (
        <div className="flex flex-wrap gap-1">
          {entries.map(([k, v]) => (
            <Badge key={k} variant="outline" className="text-xs font-mono">
              {k}={v}
            </Badge>
          ))}
        </div>
      );
    },
  },
  {
    header: "Created",
    cell: (row) =>
      formatDistanceToNow(new Date(row.createdAt), { addSuffix: true }),
  },
];

export default function OTAPage() {
  const { data: session } = useSession();
  // Using org 1 as default for now; will be dynamic with org-switcher
  const orgId = 1;

  const { data: artifacts, isLoading: artifactsLoading } = useQuery({
    queryKey: ["ota-artifacts", orgId],
    queryFn: () => listArtifacts(session?.accessToken ?? "", orgId),
    enabled: !!session?.accessToken,
  });

  const { data: deployments, isLoading: deploymentsLoading } = useQuery({
    queryKey: ["ota-deployments", orgId],
    queryFn: () => listDeployments(session?.accessToken ?? "", orgId),
    enabled: !!session?.accessToken,
  });

  return (
    <div className="space-y-8">
      <div className="flex items-center justify-between">
        <h1 className="text-2xl font-bold">OTA Updates</h1>
        <Button>
          <Upload className="mr-2 h-4 w-4" />
          Upload Artifact
        </Button>
      </div>

      {/* Artifacts */}
      <Card>
        <CardHeader>
          <CardTitle>Artifacts</CardTitle>
        </CardHeader>
        <CardContent>
          <DataTable
            columns={artifactColumns}
            data={artifacts ?? []}
            isLoading={artifactsLoading}
            searchKey="name"
            searchPlaceholder="Search artifacts..."
            emptyTitle="No artifacts"
            emptyDescription="Upload your first OTA artifact to get started."
          />
        </CardContent>
      </Card>

      {/* Deployments */}
      <Card>
        <CardHeader className="flex flex-row items-center justify-between">
          <CardTitle>Deployments</CardTitle>
          <Button variant="outline" size="sm">
            <Rocket className="mr-2 h-4 w-4" />
            New Deployment
          </Button>
        </CardHeader>
        <CardContent>
          <DataTable
            columns={deploymentColumns}
            data={deployments ?? []}
            isLoading={deploymentsLoading}
            emptyTitle="No deployments"
            emptyDescription="Create a deployment to push artifacts to your devices."
          />
        </CardContent>
      </Card>
    </div>
  );
}
