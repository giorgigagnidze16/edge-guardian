"use client";

import { useQuery } from "@tanstack/react-query";
import { useSession } from "next-auth/react";
import { useParams, useRouter } from "next/navigation";
import {
  getDeployment,
  getDeploymentDevices,
  type DeploymentDeviceStatus,
} from "@/lib/api/ota";
import { useOrganization } from "@/lib/hooks/use-organization";
import { PageHeader } from "@/components/page-header";
import { StateBadge } from "@/components/state-badge";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Progress } from "@/components/ui/progress";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from "@/components/ui/table";
import { Skeleton } from "@/components/ui/skeleton";
import { ArrowLeft } from "lucide-react";
import { formatDistanceToNow } from "date-fns";

export default function DeploymentDetailPage() {
  const { deploymentId } = useParams<{ deploymentId: string }>();
  const { data: session } = useSession();
  const { orgId } = useOrganization();
  const router = useRouter();
  const token = session?.accessToken ?? "";
  const depId = Number(deploymentId);

  const { data: deployment, isLoading } = useQuery({
    queryKey: ["deployment", orgId, depId],
    queryFn: () => getDeployment(token, orgId!, depId),
    enabled: !!token && !!orgId && !!depId,
    refetchInterval: 10_000,
  });

  const { data: devices, isLoading: devicesLoading } = useQuery({
    queryKey: ["deployment-devices", orgId, depId],
    queryFn: () => getDeploymentDevices(token, orgId!, depId),
    enabled: !!token && !!orgId && !!depId,
    refetchInterval: 10_000,
  });

  if (isLoading) {
    return (
      <div className="space-y-6">
        <Skeleton className="h-8 w-64" />
        <Skeleton className="h-48 w-full" />
      </div>
    );
  }

  if (!deployment) {
    return (
      <div className="space-y-6">
        <PageHeader title="Deployment Not Found" />
      </div>
    );
  }

  const totalDevices = devices?.length ?? deployment.totalDevices ?? 0;
  const stateCounts = (devices ?? []).reduce(
    (acc, d) => {
      acc[d.state] = (acc[d.state] ?? 0) + 1;
      return acc;
    },
    {} as Record<string, number>,
  );
  const completed = stateCounts["COMPLETED"] ?? 0;
  const failed = stateCounts["FAILED"] ?? 0;
  const overallProgress =
    totalDevices > 0 ? Math.round((completed / totalDevices) * 100) : 0;

  return (
    <div className="space-y-6">
      <div className="flex items-center gap-4">
        <Button variant="ghost" size="icon" onClick={() => router.push("/ota")}>
          <ArrowLeft className="h-4 w-4" />
        </Button>
        <div>
          <h1 className="text-2xl font-bold">Deployment #{deployment.id}</h1>
          <p className="text-sm text-muted-foreground">
            Strategy: {deployment.strategy} | Created{" "}
            {formatDistanceToNow(new Date(deployment.createdAt), {
              addSuffix: true,
            })}
          </p>
        </div>
        <div className="ml-auto">
          <StateBadge state={deployment.state} />
        </div>
      </div>

      {/* Progress summary */}
      <Card>
        <CardHeader>
          <CardTitle>Overall Progress</CardTitle>
        </CardHeader>
        <CardContent className="space-y-4">
          <div className="flex items-center gap-4">
            <Progress value={overallProgress} className="flex-1" />
            <span className="text-sm font-medium">{overallProgress}%</span>
          </div>
          <div className="flex flex-wrap gap-4 text-sm">
            {Object.entries(stateCounts).map(([state, count]) => (
              <div key={state} className="flex items-center gap-2">
                <StateBadge state={state} />
                <span className="text-muted-foreground">{count}</span>
              </div>
            ))}
            <div className="flex items-center gap-2">
              <span className="text-muted-foreground">Total: {totalDevices}</span>
            </div>
          </div>
        </CardContent>
      </Card>

      {/* Per-device table */}
      <Card>
        <CardHeader>
          <CardTitle>Device Status</CardTitle>
        </CardHeader>
        <CardContent>
          <div className="rounded-xl border border-border/50 overflow-hidden">
            <Table>
              <TableHeader>
                <TableRow>
                  <TableHead>Hostname</TableHead>
                  <TableHead>Device ID</TableHead>
                  <TableHead>State</TableHead>
                  <TableHead>Progress</TableHead>
                  <TableHead>Error</TableHead>
                  <TableHead>Updated</TableHead>
                </TableRow>
              </TableHeader>
              <TableBody>
                {devicesLoading ? (
                  Array.from({ length: 3 }).map((_, i) => (
                    <TableRow key={i}>
                      {Array.from({ length: 6 }).map((_, j) => (
                        <TableCell key={j}>
                          <Skeleton className="h-4 w-full" />
                        </TableCell>
                      ))}
                    </TableRow>
                  ))
                ) : !devices || devices.length === 0 ? (
                  <TableRow>
                    <TableCell
                      colSpan={6}
                      className="py-8 text-center text-sm text-muted-foreground"
                    >
                      No devices targeted by this deployment yet.
                    </TableCell>
                  </TableRow>
                ) : (
                  devices.map((d) => (
                    <TableRow key={d.deviceId}>
                      <TableCell className="font-medium">{d.hostname}</TableCell>
                      <TableCell className="font-mono text-xs">
                        {d.deviceId}
                      </TableCell>
                      <TableCell>
                        <StateBadge state={d.state} />
                      </TableCell>
                      <TableCell className="w-32">
                        <div className="flex items-center gap-2">
                          <Progress value={d.progress} className="h-2" />
                          <span className="text-xs text-muted-foreground">
                            {d.progress}%
                          </span>
                        </div>
                      </TableCell>
                      <TableCell>
                        {d.errorMessage ? (
                          <span className="text-xs text-destructive">
                            {d.errorMessage}
                          </span>
                        ) : (
                          <span className="text-muted-foreground">-</span>
                        )}
                      </TableCell>
                      <TableCell>
                        {formatDistanceToNow(new Date(d.updatedAt), {
                          addSuffix: true,
                        })}
                      </TableCell>
                    </TableRow>
                  ))
                )}
              </TableBody>
            </Table>
          </div>
        </CardContent>
      </Card>
    </div>
  );
}
