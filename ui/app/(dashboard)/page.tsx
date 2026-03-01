"use client";

import { useQuery } from "@tanstack/react-query";
import { useSession } from "next-auth/react";
import { listDevices } from "@/lib/api/devices";
import { MetricCard } from "@/components/metric-card";
import { MetricCardSkeleton } from "@/components/loading-skeleton";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { Cpu, Activity, AlertTriangle, Wifi } from "lucide-react";

export default function DashboardHome() {
  const { data: session } = useSession();

  const { data: devices, isLoading } = useQuery({
    queryKey: ["devices"],
    queryFn: () => listDevices(session?.accessToken ?? ""),
    enabled: !!session?.accessToken,
  });

  const total = devices?.length ?? 0;
  const online =
    devices?.filter((d) => d.state === "ONLINE").length ?? 0;
  const degraded =
    devices?.filter((d) => d.state === "DEGRADED").length ?? 0;

  return (
    <div className="space-y-6">
      <h1 className="text-2xl font-bold">Fleet Overview</h1>

      <div className="grid gap-4 md:grid-cols-2 lg:grid-cols-4">
        {isLoading ? (
          Array.from({ length: 4 }).map((_, i) => (
            <MetricCardSkeleton key={i} />
          ))
        ) : (
          <>
            <MetricCard
              title="Total Devices"
              value={total}
              description="Across all sites"
              icon={Cpu}
            />
            <MetricCard
              title="Online"
              value={online}
              description="Reporting heartbeat"
              icon={Wifi}
              iconColor="text-green-500"
            />
            <MetricCard
              title="Degraded"
              value={degraded}
              description="Needs attention"
              icon={AlertTriangle}
              iconColor="text-yellow-500"
            />
            <MetricCard
              title="Active Deployments"
              value="--"
              description="OTA in progress"
              icon={Activity}
              iconColor="text-blue-500"
            />
          </>
        )}
      </div>

      <Card>
        <CardHeader>
          <CardTitle>Recent Activity</CardTitle>
        </CardHeader>
        <CardContent>
          <p className="text-sm text-muted-foreground">
            No recent activity to display. Connect devices to get started.
          </p>
        </CardContent>
      </Card>
    </div>
  );
}
