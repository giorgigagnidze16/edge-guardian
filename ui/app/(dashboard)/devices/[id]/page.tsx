"use client";

import { useQuery } from "@tanstack/react-query";
import { useSession } from "next-auth/react";
import { useParams, useRouter } from "next/navigation";
import { getDevice } from "@/lib/api/devices";
import { getDeviceManifest } from "@/lib/api/devices";
import { MetricCard } from "@/components/metric-card";
import { StateBadge } from "@/components/state-badge";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import {
  Card,
  CardContent,
  CardHeader,
  CardTitle,
} from "@/components/ui/card";
import { DeviceDetailSkeleton } from "@/components/loading-skeleton";
import {
  Cpu,
  HardDrive,
  MemoryStick,
  Thermometer,
  Clock,
  ArrowLeft,
  FileCode,
  ScrollText,
} from "lucide-react";
import { formatDistanceToNow } from "date-fns";
import {
  Area,
  AreaChart,
  ResponsiveContainer,
  Tooltip,
  XAxis,
  YAxis,
} from "recharts";

function formatBytes(bytes: number): string {
  if (bytes === 0) return "0 B";
  const k = 1024;
  const sizes = ["B", "KB", "MB", "GB", "TB"];
  const i = Math.floor(Math.log(bytes) / Math.log(k));
  return `${(bytes / Math.pow(k, i)).toFixed(1)} ${sizes[i]}`;
}

function formatUptime(seconds: number): string {
  const days = Math.floor(seconds / 86400);
  const hours = Math.floor((seconds % 86400) / 3600);
  if (days > 0) return `${days}d ${hours}h`;
  const minutes = Math.floor((seconds % 3600) / 60);
  return `${hours}h ${minutes}m`;
}

export default function DeviceDetailPage() {
  const { id } = useParams<{ id: string }>();
  const { data: session } = useSession();
  const router = useRouter();

  const { data: device, isLoading } = useQuery({
    queryKey: ["device", id],
    queryFn: () => getDevice(session?.accessToken ?? "", id),
    enabled: !!session?.accessToken && !!id,
    refetchInterval: 30_000,
  });

  const { data: manifest } = useQuery({
    queryKey: ["device-manifest", id],
    queryFn: () => getDeviceManifest(session?.accessToken ?? "", id),
    enabled: !!session?.accessToken && !!id,
  });

  if (isLoading || !device) {
    return <DeviceDetailSkeleton />;
  }

  const status = device.status;
  const memPercent = status?.memoryTotalBytes
    ? Math.round((status.memoryUsedBytes / status.memoryTotalBytes) * 100)
    : 0;
  const diskPercent = status?.diskTotalBytes
    ? Math.round((status.diskUsedBytes / status.diskTotalBytes) * 100)
    : 0;

  return (
    <div className="space-y-6">
      {/* Header */}
      <div className="flex items-center gap-4">
        <Button variant="ghost" size="icon" onClick={() => router.back()}>
          <ArrowLeft className="h-4 w-4" />
        </Button>
        <div>
          <div className="flex items-center gap-3">
            <h1 className="text-2xl font-bold">
              {device.hostname || device.deviceId}
            </h1>
            <StateBadge state={device.state} />
          </div>
          <p className="text-sm text-muted-foreground font-mono">
            {device.deviceId}
          </p>
        </div>
      </div>

      {/* Metric cards */}
      <div className="grid gap-4 md:grid-cols-2 lg:grid-cols-4">
        <MetricCard
          title="CPU Usage"
          value={`${status?.cpuUsagePercent?.toFixed(1) ?? "--"}%`}
          description="Current utilization"
          icon={Cpu}
          iconColor="text-blue-500"
        />
        <MetricCard
          title="Memory"
          value={`${memPercent}%`}
          description={
            status
              ? `${formatBytes(status.memoryUsedBytes)} / ${formatBytes(status.memoryTotalBytes)}`
              : "--"
          }
          icon={MemoryStick}
          iconColor="text-purple-500"
        />
        <MetricCard
          title="Disk"
          value={`${diskPercent}%`}
          description={
            status
              ? `${formatBytes(status.diskUsedBytes)} / ${formatBytes(status.diskTotalBytes)}`
              : "--"
          }
          icon={HardDrive}
          iconColor="text-orange-500"
        />
        <MetricCard
          title="Temperature"
          value={
            status?.temperatureCelsius != null
              ? `${status.temperatureCelsius.toFixed(1)}C`
              : "--"
          }
          description="Board sensor"
          icon={Thermometer}
          iconColor="text-red-500"
        />
      </div>

      {/* Info + chart row */}
      <div className="grid gap-4 lg:grid-cols-2">
        {/* Device info */}
        <Card>
          <CardHeader>
            <CardTitle>Device Information</CardTitle>
          </CardHeader>
          <CardContent>
            <dl className="grid grid-cols-2 gap-x-4 gap-y-3 text-sm">
              <dt className="text-muted-foreground">OS / Arch</dt>
              <dd className="font-mono">
                {device.os}/{device.architecture}
              </dd>
              <dt className="text-muted-foreground">Agent Version</dt>
              <dd className="font-mono">{device.agentVersion}</dd>
              <dt className="text-muted-foreground">Uptime</dt>
              <dd>
                {status?.uptimeSeconds
                  ? formatUptime(status.uptimeSeconds)
                  : "--"}
              </dd>
              <dt className="text-muted-foreground">Last Heartbeat</dt>
              <dd>
                {device.lastHeartbeat
                  ? formatDistanceToNow(new Date(device.lastHeartbeat), {
                      addSuffix: true,
                    })
                  : "Never"}
              </dd>
              <dt className="text-muted-foreground">Registered</dt>
              <dd>
                {formatDistanceToNow(new Date(device.registeredAt), {
                  addSuffix: true,
                })}
              </dd>
              <dt className="text-muted-foreground">Reconcile Status</dt>
              <dd>
                <Badge variant="outline">
                  {status?.reconcileStatus ?? "unknown"}
                </Badge>
              </dd>
            </dl>
          </CardContent>
        </Card>

        {/* Resource usage chart (placeholder with current snapshot) */}
        <Card>
          <CardHeader className="flex flex-row items-center justify-between">
            <CardTitle>Resource Usage</CardTitle>
            <Clock className="h-4 w-4 text-muted-foreground" />
          </CardHeader>
          <CardContent>
            <ResponsiveContainer width="100%" height={200}>
              <AreaChart
                data={[
                  {
                    name: "Now",
                    cpu: status?.cpuUsagePercent ?? 0,
                    memory: memPercent,
                    disk: diskPercent,
                  },
                ]}
              >
                <XAxis dataKey="name" />
                <YAxis domain={[0, 100]} tickFormatter={(v) => `${v}%`} />
                <Tooltip formatter={(v: number) => `${v.toFixed(1)}%`} />
                <Area
                  type="monotone"
                  dataKey="cpu"
                  stroke="#3b82f6"
                  fill="#3b82f6"
                  fillOpacity={0.2}
                  name="CPU"
                />
                <Area
                  type="monotone"
                  dataKey="memory"
                  stroke="#a855f7"
                  fill="#a855f7"
                  fillOpacity={0.2}
                  name="Memory"
                />
                <Area
                  type="monotone"
                  dataKey="disk"
                  stroke="#f97316"
                  fill="#f97316"
                  fillOpacity={0.1}
                  name="Disk"
                />
              </AreaChart>
            </ResponsiveContainer>
          </CardContent>
        </Card>
      </div>

      {/* Labels */}
      <Card>
        <CardHeader>
          <CardTitle>Labels</CardTitle>
        </CardHeader>
        <CardContent>
          {Object.entries(device.labels ?? {}).length > 0 ? (
            <div className="flex flex-wrap gap-2">
              {Object.entries(device.labels).map(([k, v]) => (
                <Badge key={k} variant="outline" className="font-mono text-xs">
                  {k}={v}
                </Badge>
              ))}
            </div>
          ) : (
            <p className="text-sm text-muted-foreground">
              No labels assigned to this device.
            </p>
          )}
        </CardContent>
      </Card>

      {/* Manifest preview */}
      <Card>
        <CardHeader className="flex flex-row items-center justify-between">
          <CardTitle>Current Manifest</CardTitle>
          <div className="flex gap-2">
            <Button
              variant="outline"
              size="sm"
              onClick={() => router.push(`/devices/${id}/logs`)}
            >
              <ScrollText className="mr-2 h-4 w-4" />
              View Logs
            </Button>
            <Button
              variant="outline"
              size="sm"
              onClick={() => router.push(`/devices/${id}/manifest`)}
            >
              <FileCode className="mr-2 h-4 w-4" />
              Edit Manifest
            </Button>
          </div>
        </CardHeader>
        <CardContent>
          {manifest ? (
            <pre className="max-h-64 overflow-auto rounded-md bg-muted p-4 text-xs font-mono">
              {typeof manifest === "string"
                ? manifest
                : JSON.stringify(manifest, null, 2)}
            </pre>
          ) : (
            <p className="text-sm text-muted-foreground">
              No manifest assigned to this device.
            </p>
          )}
        </CardContent>
      </Card>
    </div>
  );
}
