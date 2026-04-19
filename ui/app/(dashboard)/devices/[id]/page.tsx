"use client";

import { useQuery, useMutation, useQueryClient } from "@tanstack/react-query";
import { useSession } from "next-auth/react";
import { useParams, useRouter } from "next/navigation";
import {
  getDevice,
  getDeviceManifest,
  getDeviceLogs,
  deleteDevice,
  updateDeviceManifest,
  updateDeviceLabels,
} from "@/lib/api/devices";
import { usePollingHistory } from "@/lib/hooks/use-polling-history";
import { MetricCard } from "@/components/metric-card";
import dynamic from "next/dynamic";

const Sparkline = dynamic(
  () => import("@/components/sparkline").then((m) => m.Sparkline),
  { ssr: false },
);
const DeviceResourceChart = dynamic(
  () => import("@/components/device-resource-chart").then((m) => m.DeviceResourceChart),
  { loading: () => <Skeleton className="h-[250px] w-full" />, ssr: false },
);
import { StateBadge } from "@/components/state-badge";
import { ConfirmDialog } from "@/components/confirm-dialog";
import { PageHeader } from "@/components/page-header";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import {
  Card,
  CardContent,
  CardHeader,
  CardTitle,
} from "@/components/ui/card";
import { Tabs, TabsContent, TabsList, TabsTrigger } from "@/components/ui/tabs";
import { ScrollArea } from "@/components/ui/scroll-area";
import { Skeleton } from "@/components/ui/skeleton";
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from "@/components/ui/select";
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuSeparator,
  DropdownMenuTrigger,
} from "@/components/ui/dropdown-menu";
import { DeviceDetailSkeleton } from "@/components/loading-skeleton";
import {
  Cpu,
  HardDrive,
  MemoryStick,
  Thermometer,
  Clock,
  ArrowLeft,
  MoreVertical,
  Trash2,
  Tag,
  Search,
  RefreshCw,
  Plus,
  X,
} from "lucide-react";
import { formatDistanceToNow } from "date-fns";
import { useState, useEffect, useCallback, useRef } from "react";
import { toast } from "sonner";

const Editor = dynamic(() => import("@monaco-editor/react"), { ssr: false });

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

const LEVEL_COLORS: Record<string, string> = {
  ERROR: "text-red-600 dark:text-red-400",
  WARN: "text-yellow-600 dark:text-yellow-400",
  INFO: "text-blue-600 dark:text-blue-400",
  DEBUG: "text-gray-500",
};

export default function DeviceDetailPage() {
  const { id } = useParams<{ id: string }>();
  const { data: session } = useSession();
  const router = useRouter();
  const queryClient = useQueryClient();
  const token = session?.accessToken ?? "";

  const history = usePollingHistory();

  const { data: device, isLoading } = useQuery({
    queryKey: ["device", id],
    queryFn: () => getDevice(token, id),
    enabled: !!token && !!id,
    refetchInterval: 10_000,
  });

  const supportsTemperature = device?.os !== "windows";

  useEffect(() => {
    if (device?.status) {
      history.push("cpu", device.status.cpuUsagePercent);
      const memPct = device.status.memoryTotalBytes
        ? (device.status.memoryUsedBytes / device.status.memoryTotalBytes) * 100
        : null;
      history.push("memory", memPct);
      if (supportsTemperature) {
        history.push("temp", device.status.temperatureCelsius);
      }
    }
  }, [device, history, supportsTemperature]);

  // Delete
  const [deleteOpen, setDeleteOpen] = useState(false);
  const deleteMutation = useMutation({
    mutationFn: () => deleteDevice(token, id),
    onSuccess: () => {
      toast.success("Device deleted");
      router.push("/devices");
    },
    onError: (err: Error) => toast.error(err.message),
  });

  // Labels
  const [editingLabels, setEditingLabels] = useState(false);
  const [newLabelKey, setNewLabelKey] = useState("");
  const [newLabelValue, setNewLabelValue] = useState("");
  const [localLabels, setLocalLabels] = useState<Record<string, string>>({});

  useEffect(() => {
    if (device) setLocalLabels(device.labels ?? {});
  }, [device]);

  const labelsMutation = useMutation({
    mutationFn: (labels: Record<string, string>) =>
      updateDeviceLabels(token, id, labels),
    onSuccess: () => {
      toast.success("Labels updated");
      queryClient.invalidateQueries({ queryKey: ["device", id] });
      setEditingLabels(false);
    },
    onError: (err: Error) => toast.error(err.message),
  });

  // Logs state
  const [logSearch, setLogSearch] = useState("");
  const [logLevel, setLogLevel] = useState("");
  const [logTimeRange, setLogTimeRange] = useState("1h");

  const getLogTimeStart = () => {
    const map: Record<string, number> = {
      "15m": 15 * 60 * 1000,
      "1h": 60 * 60 * 1000,
      "6h": 6 * 60 * 60 * 1000,
      "24h": 24 * 60 * 60 * 1000,
      "7d": 7 * 24 * 60 * 60 * 1000,
    };
    return new Date(Date.now() - (map[logTimeRange] ?? map["1h"])).toISOString();
  };

  const { data: logsResponse, isLoading: logsLoading } = useQuery({
    queryKey: ["device-logs", id, logLevel, logSearch, logTimeRange],
    queryFn: () =>
      getDeviceLogs(token, id, {
        limit: 200,
        level: logLevel || undefined,
        search: logSearch || undefined,
        start: getLogTimeStart(),
      }),
    enabled: !!token && !!id,
    refetchInterval: 10_000,
  });

  const logLines: { timestamp: string; line: string }[] = [];
  if (logsResponse && typeof logsResponse === "object") {
    const resp = logsResponse as {
      data?: { result?: { values?: [string, string][] }[] };
    };
    for (const stream of resp?.data?.result ?? []) {
      for (const [ts, line] of stream.values ?? []) {
        logLines.push({ timestamp: ts, line });
      }
    }
  }

  // Manifest state
  const { data: manifest, isLoading: manifestLoading } = useQuery({
    queryKey: ["device-manifest", id],
    queryFn: () => getDeviceManifest(token, id),
    enabled: !!token && !!id,
  });

  const initialYaml =
    typeof manifest === "string"
      ? manifest
      : manifest
        ? JSON.stringify(manifest, null, 2)
        : "";
  const [editorValue, setEditorValue] = useState<string | undefined>(undefined);
  const currentValue = editorValue ?? initialYaml;
  const hasManifestChanges = editorValue !== undefined && editorValue !== initialYaml;

  const manifestMutation = useMutation({
    mutationFn: (yaml: string) => updateDeviceManifest(token, id, yaml),
    onSuccess: () => {
      toast.success("Manifest saved");
      queryClient.invalidateQueries({ queryKey: ["device-manifest", id] });
      setEditorValue(undefined);
    },
    onError: (err: Error) => toast.error(err.message),
  });

  // Ctrl+S shortcut for manifest
  useEffect(() => {
    const handler = (e: KeyboardEvent) => {
      if ((e.ctrlKey || e.metaKey) && e.key === "s") {
        e.preventDefault();
        if (hasManifestChanges && currentValue) {
          manifestMutation.mutate(currentValue);
        }
      }
    };
    window.addEventListener("keydown", handler);
    return () => window.removeEventListener("keydown", handler);
  }, [hasManifestChanges, currentValue, manifestMutation]);

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

  // Build chart data from history
  const chartData = history.get("cpu").map((cpu, i) => ({
    index: i,
    cpu,
    memory: history.get("memory")[i] ?? 0,
  }));

  return (
    <div className="space-y-6">
      {/* Header */}
      <div className="flex items-center gap-4">
        <Button variant="ghost" size="icon" onClick={() => router.push("/devices")}>
          <ArrowLeft className="h-4 w-4" />
        </Button>
        <div className="flex-1">
          <div className="flex items-center gap-3">
            <h1 className="text-2xl font-bold">
              {device.hostname || device.deviceId}
            </h1>
            <StateBadge state={device.state} />
          </div>
          <p className="font-mono text-sm text-muted-foreground">
            {device.deviceId}
          </p>
        </div>
        <DropdownMenu>
          <DropdownMenuTrigger asChild>
            <Button variant="outline" size="icon">
              <MoreVertical className="h-4 w-4" />
            </Button>
          </DropdownMenuTrigger>
          <DropdownMenuContent align="end">
            <DropdownMenuItem onClick={() => setEditingLabels(true)}>
              <Tag className="mr-2 h-4 w-4" />
              Edit Labels
            </DropdownMenuItem>
            <DropdownMenuSeparator />
            <DropdownMenuItem
              className="text-destructive"
              onClick={() => setDeleteOpen(true)}
            >
              <Trash2 className="mr-2 h-4 w-4" />
              Delete Device
            </DropdownMenuItem>
          </DropdownMenuContent>
        </DropdownMenu>
      </div>

      {/* Metric cards */}
      <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-5">
        <MetricCard
          title="CPU Usage"
          value={`${status?.cpuUsagePercent?.toFixed(1) ?? "--"}%`}
          description="Current utilization"
          icon={Cpu}
          iconColor="text-blue-500"
          chart={<Sparkline data={history.get("cpu")} color="#3b82f6" />}
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
          chart={<Sparkline data={history.get("memory")} color="#a855f7" />}
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
        {supportsTemperature && (
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
            chart={<Sparkline data={history.get("temp")} color="#ef4444" />}
          />
        )}
        <MetricCard
          title="Uptime"
          value={status?.uptimeSeconds ? formatUptime(status.uptimeSeconds) : "--"}
          description={
            device.lastHeartbeat
              ? `Last seen ${formatDistanceToNow(new Date(device.lastHeartbeat), { addSuffix: true })}`
              : "Never seen"
          }
          icon={Clock}
          iconColor="text-green-500"
        />
      </div>

      {/* Tabs */}
      <Tabs defaultValue="overview" className="space-y-4">
        <TabsList>
          <TabsTrigger value="overview">Overview</TabsTrigger>
          <TabsTrigger value="logs">Logs</TabsTrigger>
          <TabsTrigger value="manifest">Manifest</TabsTrigger>
          <TabsTrigger value="ota">OTA History</TabsTrigger>
        </TabsList>

        {/* Overview Tab */}
        <TabsContent value="overview" className="space-y-4">
          <div className="grid gap-4 lg:grid-cols-2">
            {/* Resource chart */}
            <Card>
              <CardHeader>
                <CardTitle>Resource Usage</CardTitle>
              </CardHeader>
              <CardContent>
                <DeviceResourceChart
                  data={chartData.length > 1 ? chartData : [{ index: 0, cpu: status?.cpuUsagePercent ?? 0, memory: memPercent }]}
                />
              </CardContent>
            </Card>

            {/* Device info */}
            <Card>
              <CardHeader>
                <CardTitle>Device Information</CardTitle>
              </CardHeader>
              <CardContent>
                <dl className="grid grid-cols-2 gap-x-4 gap-y-3 text-sm">
                  <dt className="text-muted-foreground">OS / Arch</dt>
                  <dd className="font-mono">{device.os}/{device.architecture}</dd>
                  <dt className="text-muted-foreground">Agent Version</dt>
                  <dd className="font-mono">{device.agentVersion}</dd>
                  <dt className="text-muted-foreground">Uptime</dt>
                  <dd>{status?.uptimeSeconds ? formatUptime(status.uptimeSeconds) : "--"}</dd>
                  <dt className="text-muted-foreground">Last Heartbeat</dt>
                  <dd>{device.lastHeartbeat ? formatDistanceToNow(new Date(device.lastHeartbeat), { addSuffix: true }) : "Never"}</dd>
                  <dt className="text-muted-foreground">Registered</dt>
                  <dd>{formatDistanceToNow(new Date(device.registeredAt), { addSuffix: true })}</dd>
                  <dt className="text-muted-foreground">Reconcile Status</dt>
                  <dd><Badge variant="outline">{status?.reconcileStatus ?? "unknown"}</Badge></dd>
                </dl>
              </CardContent>
            </Card>
          </div>

          {/* Labels */}
          <Card>
            <CardHeader className="flex flex-row items-center justify-between">
              <CardTitle>Labels</CardTitle>
              {!editingLabels && (
                <Button variant="outline" size="sm" onClick={() => setEditingLabels(true)}>
                  <Tag className="mr-2 h-3 w-3" />
                  Edit
                </Button>
              )}
            </CardHeader>
            <CardContent>
              {editingLabels ? (
                <div className="space-y-3">
                  <div className="flex flex-wrap gap-2">
                    {Object.entries(localLabels).map(([k, v]) => (
                      <Badge key={k} variant="outline" className="gap-1 font-mono text-xs">
                        {k}={v}
                        <button
                          onClick={() => {
                            const next = { ...localLabels };
                            delete next[k];
                            setLocalLabels(next);
                          }}
                        >
                          <X className="h-3 w-3" />
                        </button>
                      </Badge>
                    ))}
                  </div>
                  <div className="flex gap-2">
                    <Input
                      placeholder="key"
                      value={newLabelKey}
                      onChange={(e) => setNewLabelKey(e.target.value)}
                      className="w-32"
                    />
                    <Input
                      placeholder="value"
                      value={newLabelValue}
                      onChange={(e) => setNewLabelValue(e.target.value)}
                      className="w-32"
                    />
                    <Button
                      variant="outline"
                      size="sm"
                      disabled={!newLabelKey}
                      onClick={() => {
                        setLocalLabels({ ...localLabels, [newLabelKey]: newLabelValue });
                        setNewLabelKey("");
                        setNewLabelValue("");
                      }}
                    >
                      <Plus className="h-3 w-3" />
                    </Button>
                  </div>
                  <div className="flex gap-2">
                    <Button
                      size="sm"
                      onClick={() => labelsMutation.mutate(localLabels)}
                      disabled={labelsMutation.isPending}
                    >
                      {labelsMutation.isPending ? "Saving..." : "Save Labels"}
                    </Button>
                    <Button
                      variant="outline"
                      size="sm"
                      onClick={() => {
                        setEditingLabels(false);
                        setLocalLabels(device.labels ?? {});
                      }}
                    >
                      Cancel
                    </Button>
                  </div>
                </div>
              ) : Object.entries(device.labels ?? {}).length > 0 ? (
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
        </TabsContent>

        {/* Logs Tab */}
        <TabsContent value="logs" className="space-y-4">
          <div className="flex flex-wrap items-center gap-2">
            <div className="relative w-64">
              <Search className="absolute left-3 top-1/2 h-4 w-4 -translate-y-1/2 text-muted-foreground" />
              <Input
                placeholder="Search logs..."
                value={logSearch}
                onChange={(e) => setLogSearch(e.target.value)}
                className="pl-9"
              />
            </div>
            <Select value={logLevel} onValueChange={setLogLevel}>
              <SelectTrigger className="w-32">
                <SelectValue placeholder="All Levels" />
              </SelectTrigger>
              <SelectContent>
                <SelectItem value="all">All Levels</SelectItem>
                <SelectItem value="ERROR">ERROR</SelectItem>
                <SelectItem value="WARN">WARN</SelectItem>
                <SelectItem value="INFO">INFO</SelectItem>
                <SelectItem value="DEBUG">DEBUG</SelectItem>
              </SelectContent>
            </Select>
            <Select value={logTimeRange} onValueChange={setLogTimeRange}>
              <SelectTrigger className="w-28">
                <SelectValue />
              </SelectTrigger>
              <SelectContent>
                <SelectItem value="15m">15 min</SelectItem>
                <SelectItem value="1h">1 hour</SelectItem>
                <SelectItem value="6h">6 hours</SelectItem>
                <SelectItem value="24h">24 hours</SelectItem>
                <SelectItem value="7d">7 days</SelectItem>
              </SelectContent>
            </Select>
            <Badge variant="secondary">{logLines.length} lines</Badge>
          </div>
          <Card>
            <CardContent className="p-0">
              {logsLoading ? (
                <div className="space-y-2 p-4">
                  {Array.from({ length: 10 }).map((_, i) => (
                    <Skeleton key={i} className="h-5 w-full" />
                  ))}
                </div>
              ) : logLines.length === 0 ? (
                <p className="p-8 text-center text-sm text-muted-foreground">
                  No log entries found.
                </p>
              ) : (
                <ScrollArea className="h-[500px]">
                  <pre className="p-4 text-xs font-mono leading-relaxed">
                    {logLines.map((entry, i) => {
                      const levelMatch = entry.line.match(/level=(\w+)/);
                      const lineLevel = levelMatch?.[1] ?? "";
                      const colorClass = LEVEL_COLORS[lineLevel] ?? "text-foreground";
                      return (
                        <div key={i} className={`${colorClass} hover:bg-muted rounded px-1`}>
                          {entry.line}
                        </div>
                      );
                    })}
                  </pre>
                </ScrollArea>
              )}
            </CardContent>
          </Card>
        </TabsContent>

        {/* Manifest Tab */}
        <TabsContent value="manifest" className="space-y-4">
          <div className="flex items-center justify-between">
            <p className="text-sm text-muted-foreground">
              Edit the YAML manifest for this device. Press Ctrl+S to save.
            </p>
            <Button
              onClick={() => manifestMutation.mutate(currentValue)}
              disabled={!hasManifestChanges || manifestMutation.isPending}
            >
              {manifestMutation.isPending ? "Saving..." : "Save Manifest"}
            </Button>
          </div>
          <Card>
            <CardContent className="p-0">
              {manifestLoading ? (
                <Skeleton className="h-[500px] w-full" />
              ) : (
                <Editor
                  height="500px"
                  defaultLanguage="yaml"
                  defaultValue={initialYaml}
                  onChange={(value) => setEditorValue(value ?? "")}
                  theme="vs-dark"
                  options={{
                    minimap: { enabled: false },
                    fontSize: 13,
                    lineNumbers: "on",
                    scrollBeyondLastLine: false,
                    wordWrap: "on",
                    tabSize: 2,
                  }}
                />
              )}
            </CardContent>
          </Card>
        </TabsContent>

        {/* OTA History Tab */}
        <TabsContent value="ota">
          <Card>
            <CardContent className="p-6">
              <p className="text-sm text-muted-foreground">
                OTA deployment history for this device will appear here once deployments target it.
              </p>
            </CardContent>
          </Card>
        </TabsContent>
      </Tabs>

      {/* Delete dialog */}
      <ConfirmDialog
        open={deleteOpen}
        onOpenChange={setDeleteOpen}
        title="Delete Device"
        description={`Are you sure you want to delete ${device.hostname}? This action cannot be undone.`}
        confirmLabel="Delete"
        variant="destructive"
        onConfirm={() => deleteMutation.mutate()}
        loading={deleteMutation.isPending}
      />
    </div>
  );
}
