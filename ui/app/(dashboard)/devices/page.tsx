"use client";

import { useQuery } from "@tanstack/react-query";
import { useSession } from "next-auth/react";
import { useRouter } from "next/navigation";
import { listDevices, type Device } from "@/lib/api/devices";
import { DataTable, type Column } from "@/components/data-table";
import { StateBadge } from "@/components/state-badge";
import { Badge } from "@/components/ui/badge";
import { formatDistanceToNow } from "date-fns";
import { Cpu } from "lucide-react";

const columns: Column<Device>[] = [
  {
    header: "Device ID",
    cell: (row) => (
      <span className="font-mono text-xs">{row.deviceId}</span>
    ),
  },
  {
    header: "Hostname",
    accessorKey: "hostname",
  },
  {
    header: "State",
    cell: (row) => <StateBadge state={row.state} />,
    className: "w-28",
  },
  {
    header: "Architecture",
    cell: (row) => (
      <Badge variant="secondary" className="font-mono text-xs">
        {row.os}/{row.architecture}
      </Badge>
    ),
  },
  {
    header: "Version",
    cell: (row) => (
      <span className="font-mono text-xs">{row.agentVersion}</span>
    ),
  },
  {
    header: "Last Heartbeat",
    cell: (row) =>
      row.lastHeartbeat
        ? formatDistanceToNow(new Date(row.lastHeartbeat), { addSuffix: true })
        : "Never",
  },
  {
    header: "Labels",
    cell: (row) => {
      const entries = Object.entries(row.labels ?? {});
      if (entries.length === 0) return <span className="text-muted-foreground">-</span>;
      return (
        <div className="flex flex-wrap gap-1">
          {entries.slice(0, 3).map(([k, v]) => (
            <Badge key={k} variant="outline" className="text-xs">
              {k}={v}
            </Badge>
          ))}
          {entries.length > 3 && (
            <Badge variant="outline" className="text-xs">
              +{entries.length - 3}
            </Badge>
          )}
        </div>
      );
    },
  },
];

export default function DevicesPage() {
  const { data: session } = useSession();
  const router = useRouter();

  const { data: devices, isLoading } = useQuery({
    queryKey: ["devices"],
    queryFn: () => listDevices(session?.accessToken ?? ""),
    enabled: !!session?.accessToken,
  });

  return (
    <div className="space-y-6">
      <div className="flex items-center justify-between">
        <h1 className="text-2xl font-bold">Devices</h1>
        <p className="text-sm text-muted-foreground">
          {devices?.length ?? 0} device{(devices?.length ?? 0) !== 1 ? "s" : ""} registered
        </p>
      </div>

      <DataTable
        columns={columns}
        data={devices ?? []}
        isLoading={isLoading}
        searchKey="hostname"
        searchPlaceholder="Search by hostname..."
        onRowClick={(device) => router.push(`/devices/${device.deviceId}`)}
        emptyTitle="No devices found"
        emptyDescription="Register your first device to see it here."
      />
    </div>
  );
}
