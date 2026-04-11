"use client";

import { useQuery } from "@tanstack/react-query";
import { useSession } from "next-auth/react";
import dynamic from "next/dynamic";
import { listDevices } from "@/lib/api/devices";
import { listDeployments } from "@/lib/api/ota";
import { listAuditLog } from "@/lib/api/organizations";
import { useOrganization } from "@/lib/hooks/use-organization";
import { MetricCard } from "@/components/metric-card";
import { MetricCardSkeleton } from "@/components/loading-skeleton";
import { PageHeader } from "@/components/page-header";
import { StateBadge } from "@/components/state-badge";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { Badge } from "@/components/ui/badge";
import { Skeleton } from "@/components/ui/skeleton";
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from "@/components/ui/table";
import { Cpu, Wifi, Upload, Activity, AlertTriangle } from "lucide-react";
import Link from "next/link";
import { formatDistanceToNow } from "date-fns";

// Lazy-load recharts-heavy components (saves ~800 modules from initial bundle)
const FleetHealthChart = dynamic(
  () => import("@/components/fleet-health-chart").then((m) => m.FleetHealthChart),
  { loading: () => <Skeleton className="h-[300px] w-full" />, ssr: false },
);
const ResourceUtilizationChart = dynamic(
  () => import("@/components/resource-utilization-chart").then((m) => m.ResourceUtilizationChart),
  { loading: () => <Skeleton className="h-[250px] w-full" />, ssr: false },
);
const MiniRingChart = dynamic(
  () => import("@/components/mini-ring-chart").then((m) => m.MiniRingChart),
  { ssr: false },
);

export default function DashboardHome() {
  const { data: session } = useSession();
  const { orgId } = useOrganization();
  const token = session?.accessToken ?? "";

  const { data: devices, isLoading: devicesLoading } = useQuery({
    queryKey: ["devices"],
    queryFn: () => listDevices(token),
    enabled: !!token,
  });

  const { data: deployments, isLoading: deploymentsLoading } = useQuery({
    queryKey: ["deployments", orgId],
    queryFn: () => listDeployments(token),
    enabled: !!token && !!orgId,
  });

  const { data: auditLog } = useQuery({
    queryKey: ["audit-log", orgId],
    queryFn: () => listAuditLog(token, { size: 10 }),
    enabled: !!token && !!orgId,
  });

  const isLoading = devicesLoading || deploymentsLoading;
  const total = devices?.length ?? 0;
  const online = devices?.filter((d) => d.state === "ONLINE").length ?? 0;
  const degraded = devices?.filter((d) => d.state === "DEGRADED").length ?? 0;
  const offline = devices?.filter((d) => d.state === "OFFLINE").length ?? 0;
  const activeDeployments =
    deployments?.filter((d) => d.state !== "COMPLETED" && d.state !== "FAILED")
      .length ?? 0;
  const avgCpu =
    devices && devices.length > 0
      ? Math.round(
          devices.reduce((sum, d) => sum + (d.status?.cpuUsagePercent ?? 0), 0) /
            devices.filter((d) => d.status?.cpuUsagePercent != null).length || 0,
        )
      : 0;

  const needsAttention =
    devices?.filter(
      (d) =>
        d.state === "DEGRADED" ||
        (d.status?.cpuUsagePercent ?? 0) > 80 ||
        (d.status?.memoryTotalBytes ? (d.status.memoryUsedBytes / d.status.memoryTotalBytes) * 100 : 0) > 80,
    ) ?? [];

  const recentDeployments = deployments?.slice(0, 5) ?? [];

  return (
    <div className="space-y-6">
      <PageHeader
        title="Fleet Overview"
        description="Monitor your IoT fleet at a glance"
      />

      {/* Row 1: Fleet Summary */}
      <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-4 stagger-children">
        {isLoading ? (
          Array.from({ length: 4 }).map((_, i) => <MetricCardSkeleton key={i} />)
        ) : (
          <>
            <MetricCard
              title="Total Devices"
              value={total}
              description="Across all sites"
              icon={Cpu}
              href="/devices"
            />
            <MetricCard
              title="Online"
              value={`${online} / ${total}`}
              description="Reporting heartbeat"
              icon={Wifi}
              iconColor="text-emerald-500"
              chart={
                total > 0 ? (
                  <MiniRingChart value={online} total={total} />
                ) : undefined
              }
              href="/devices"
            />
            <MetricCard
              title="Active Deployments"
              value={activeDeployments}
              description="OTA in progress"
              icon={Upload}
              iconColor="text-primary"
              href="/ota"
            />
            <MetricCard
              title="Avg CPU Usage"
              value={`${avgCpu}%`}
              description="Fleet average"
              icon={Activity}
              iconColor="text-violet-500"
            />
          </>
        )}
      </div>

      {/* Row 2: Fleet Health */}
      <Card>
        <CardHeader>
          <CardTitle>Fleet Health</CardTitle>
        </CardHeader>
        <CardContent>
          <FleetHealthChart
            online={online}
            degraded={degraded}
            offline={offline}
          />
        </CardContent>
      </Card>

      {/* Row 3: Two columns */}
      <div className="grid gap-4 lg:grid-cols-2">
        {/* Top Resource Consumers */}
        <Card>
          <CardHeader>
            <CardTitle>Top Resource Consumers</CardTitle>
          </CardHeader>
          <CardContent>
            {devices ? (
              <ResourceUtilizationChart devices={devices} />
            ) : (
              <div className="flex h-[250px] items-center justify-center text-sm text-muted-foreground">
                Loading...
              </div>
            )}
          </CardContent>
        </Card>

        {/* Recent Deployments */}
        <Card>
          <CardHeader className="flex flex-row items-center justify-between">
            <CardTitle>Recent Deployments</CardTitle>
            <Link
              href="/ota"
              className="text-sm text-primary hover:text-primary/80 transition-colors"
            >
              View all
            </Link>
          </CardHeader>
          <CardContent>
            {recentDeployments.length > 0 ? (
              <div className="space-y-3">
                {recentDeployments.map((d) => (
                  <div
                    key={d.id}
                    className="flex items-center justify-between rounded-lg border border-border/50 p-3 transition-colors hover:bg-muted/30"
                  >
                    <div>
                      <p className="text-sm font-medium">
                        Deployment #{d.id}
                      </p>
                      <p className="text-xs text-muted-foreground">
                        Strategy: {d.strategy}
                      </p>
                    </div>
                    <Badge
                      variant={
                        d.state === "COMPLETED"
                          ? "default"
                          : d.state === "FAILED"
                            ? "destructive"
                            : "secondary"
                      }
                    >
                      {d.state}
                    </Badge>
                  </div>
                ))}
              </div>
            ) : (
              <p className="text-sm text-muted-foreground">
                No deployments yet
              </p>
            )}
          </CardContent>
        </Card>
      </div>

      {/* Row 4: Two columns */}
      <div className="grid gap-4 lg:grid-cols-2">
        {/* Devices Needing Attention */}
        <Card>
          <CardHeader>
            <CardTitle className="flex items-center gap-2">
              <AlertTriangle className="h-4 w-4 text-yellow-500" />
              Devices Needing Attention
            </CardTitle>
          </CardHeader>
          <CardContent>
            {needsAttention.length > 0 ? (
              <Table>
                <TableHeader>
                  <TableRow>
                    <TableHead>Hostname</TableHead>
                    <TableHead>State</TableHead>
                    <TableHead>CPU</TableHead>
                    <TableHead>Memory</TableHead>
                  </TableRow>
                </TableHeader>
                <TableBody>
                  {needsAttention.slice(0, 5).map((d) => (
                    <TableRow key={d.deviceId}>
                      <TableCell>
                        <Link
                          href={`/devices/${d.deviceId}`}
                          className="font-medium hover:underline"
                        >
                          {d.hostname}
                        </Link>
                      </TableCell>
                      <TableCell>
                        <StateBadge state={d.state} />
                      </TableCell>
                      <TableCell>
                        {d.status?.cpuUsagePercent?.toFixed(0) ?? "--"}%
                      </TableCell>
                      <TableCell>
                        {d.status?.memoryTotalBytes ? Math.round((d.status.memoryUsedBytes / d.status.memoryTotalBytes) * 100) : "--"}%
                      </TableCell>
                    </TableRow>
                  ))}
                </TableBody>
              </Table>
            ) : (
              <p className="text-sm text-muted-foreground">
                All devices are healthy
              </p>
            )}
          </CardContent>
        </Card>

        {/* Recent Activity */}
        <Card>
          <CardHeader className="flex flex-row items-center justify-between">
            <CardTitle>Recent Activity</CardTitle>
            <Link
              href="/audit"
              className="text-sm text-primary hover:text-primary/80 transition-colors"
            >
              View all
            </Link>
          </CardHeader>
          <CardContent>
            {auditLog && auditLog.length > 0 ? (
              <div className="space-y-3">
                {auditLog.map((entry) => (
                  <div
                    key={entry.id}
                    className="flex items-start justify-between gap-2 rounded-lg border border-border/50 p-3 transition-colors hover:bg-muted/30"
                  >
                    <div className="min-w-0 flex-1">
                      <p className="text-sm font-medium">{entry.action}</p>
                      <p className="truncate text-xs text-muted-foreground">
                        {entry.resourceType} {entry.resourceId} by{" "}
                        {entry.userEmail}
                      </p>
                    </div>
                    <span className="shrink-0 text-xs text-muted-foreground">
                      {formatDistanceToNow(new Date(entry.createdAt), {
                        addSuffix: true,
                      })}
                    </span>
                  </div>
                ))}
              </div>
            ) : (
              <p className="text-sm text-muted-foreground">
                No recent activity to display
              </p>
            )}
          </CardContent>
        </Card>
      </div>
    </div>
  );
}
